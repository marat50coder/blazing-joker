package com.blazingjoker.blazingjokergame.link

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
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

    /**
     * Register the AppsFlyer SDK listeners (init + subscribeForDeepLink).
     * Call from Activity.onCreate BEFORE any lifecycle callback fires,
     * otherwise the SDK's ActivityLifecycleCallbacks miss the first
     * onResume and conversion never fires until a later activity opens.
     */
    fun wireUp() {
        runCatching { campaign.wireUp() }
    }

    /**
     * Send the launch event. AppsFlyer REQUIRES an Activity here — passing
     * the Application context queues the launch to the next transition
     * (which in our shell is ~30 s later when MainMenu opens) and the
     * conversion listener silently never fires.
     */
    fun start(host: Activity) {
        runCatching { campaign.start(host) }
    }

    /**
     * Hand a cold-tap or warm VIEW intent to the pipeline so URI-borne
     * attribution (campaign, media_source, deep_link_value…) is captured
     * even when the SDK stays silent.
     */
    fun notifyLaunchIntent(intent: Intent?) {
        runCatching { campaign.notifyLaunchIntent(intent) }
    }

    fun kickOffAmbient() {
        ambientScope.launch {
            runCatching { horn.boot() }
        }
    }

    suspend fun chart(onProgress: (Float) -> Unit = {}): Berth = gate.withLock {
        Log.d(TAG, "chart() start; course=${stowage.course}; credentialsReady=${LinkConfig.credentialsReady}")
        try {
            if (!LinkConfig.credentialsReady) {
                Log.w(TAG, "credentials empty → forcing NATIVE")
                onProgress(1f)
                return@withLock Berth.Native
            }

            stowage.consumePendingUrl()?.let { url ->
                Log.d(TAG, "cold-tap URL wins: $url")
                stowage.course = LastCourse.Web
                launchFireAndForget()
                onProgress(1f)
                return@withLock Berth.Web(url = url, fromColdPush = true)
            }

            val berth = when (stowage.course) {
                LastCourse.Unset -> decideFresh(onProgress)
                LastCourse.Web -> decideReturningWeb(onProgress)
                LastCourse.Native -> decideReturningNative(onProgress)
            }
            Log.d(TAG, "chart() → $berth")
            berth
        } catch (t: Throwable) {
            Log.e(TAG, "chart() threw: ${t.message}", t)
            onProgress(1f)
            Berth.Native
        }
    }

    private suspend fun decideFresh(onProgress: (Float) -> Unit): Berth {
        Log.d(TAG, "decideFresh: hasCarrier=${auditor.hasCarrier()}")
        if (!auditor.hasCarrier()) return Berth.LostSignal(previouslyOnNative = false)
        onProgress(pFirstCarrier)

        runCatching { horn.boot() }
        Log.d(TAG, "decideFresh: horn booted; fcmToken=${horn.token?.take(24)}…")

        val canRoute = auditor.canRouteOut()
        Log.d(TAG, "decideFresh: canRouteOut=$canRoute")
        if (!canRoute) return Berth.LostSignal(previouslyOnNative = false)
        onProgress(pFirstProbe)

        val installBag = campaign.awaitInstall(LinkConfig.FIRST_INSTALL_WAIT_MS)
        Log.d(TAG, "decideFresh: attribution bag keys=${installBag.keys}; af_status=${installBag["af_status"]}; uriLaunched=${campaign.wasUriLaunched()}")
        onProgress(pFirstAttribution)

        val answer = askChart(installBag)
        Log.d(TAG, "decideFresh: verdict approved=${answer.approved} url=${answer.url} note=${answer.note} serverResponded=${answer.serverResponded}")
        onProgress(pFirstVerdict)

        if (answer.hasDestination) {
            stowage.course = LastCourse.Web
            return Berth.Web(answer.url!!, fromColdPush = false)
        }

        // A "no url" verdict sticks (course = Native, no further
        // pilot work on future launches — the white part goes offline)
        // as soon as the backend has ACTUALLY answered. Server response
        // is the authoritative signal: if config.php returns ok=false /
        // no url, the user is organic regardless of whether the
        // AppsFlyer callback also fired, and re-asking on every launch
        // just re-imposes the "second launch of the white part needs
        // internet" tax the users complained about.
        //
        // Any other outcome — DNS timeout, HTTP 5xx, malformed JSON —
        // is "we could not ask", not "we asked and the answer was no".
        // Course stays Unset and the next launch runs the fresh pilot
        // again (an offline OneLink retry will not lose its attribution
        // this way).
        if (answer.serverResponded) {
            Log.i(TAG, "decideFresh: server responded no-url → course=Native (sticky)")
            stowage.course = LastCourse.Native
        } else {
            Log.i(
                TAG,
                "decideFresh: leaving course Unset " +
                    "(server did not respond) so the next launch retries"
            )
        }
        return Berth.Native
    }

    private suspend fun decideReturningWeb(onProgress: (Float) -> Unit): Berth {
        if (!auditor.hasCarrier()) {
            // No carrier at all — fall back to whatever we had last, or
            // LostSignal if this is a fresh install with no cache.
            val cached = stowage.cachedDestination()
            return cached?.let { Berth.Web(it, fromColdPush = false) }
                ?: Berth.LostSignal(previouslyOnNative = false)
        }

        val cached = stowage.cachedDestination()

        runCatching { horn.boot() }
        if (!auditor.canRouteOut()) {
            // Route probe failed — cache is the only thing we have. Do NOT
            // touch expiry here: an expired cache is still better than
            // sending the user to the offline screen.
            return cached?.let { Berth.Web(it, fromColdPush = false) }
                ?: Berth.LostSignal(previouslyOnNative = false)
        }

        // Always re-ask the config endpoint on a returning session so a
        // URL rotation on the backend reaches the user next launch. The
        // cache is treated as a warm fallback for the network path, not
        // as a "TTL: skip fetch" shortcut — that shortcut used to leave
        // stale URLs on device for up to CACHED_DESTINATION_LIFETIME_SECONDS
        // (~5.4 days) after a config change.
        val installBag = campaign.awaitInstall(LinkConfig.RETURN_INSTALL_WAIT_MS)
        onProgress(pReturnAttribution)
        val answer = askChart(installBag)
        onProgress(pReturnVerdict)

        if (answer.hasDestination) return Berth.Web(answer.url!!, fromColdPush = false)
        // Fresh fetch had no destination — fall back to the cached one
        // (still valid for [CACHED_DESTINATION_LIFETIME_SECONDS]). Only
        // if there is nothing to fall back to do we surrender.
        return cached
            ?.takeIf { !stowage.cachedDestinationExpired }
            ?.let { Berth.Web(it, fromColdPush = false) }
            ?: Berth.LostSignal(previouslyOnNative = false)
    }

    private suspend fun decideReturningNative(onProgress: (Float) -> Unit): Berth {
        if (!auditor.hasCarrier()) {
            onProgress(1f)
            return Berth.Native
        }
        runCatching { horn.boot() }
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

        // Merge priority: real SDK attribution wins over URI query params
        // wins over deep-link callback facts. This matches AppsFlyer's own
        // recommendation and the backend's expected precedence.
        installBag.forEach { (k, v) -> if (v != null) body.put(k, v) }
        campaign.uriFacts().forEach { (k, v) -> if (!body.has(k) && v != null) body.put(k, v) }
        campaign.deepLinkFacts().forEach { (k, v) -> if (!body.has(k) && v != null) body.put(k, v) }

        // ── af_status resolution ────────────────────────────────────
        // The backend rejects bodies without an af_status ("No data").
        //
        // AppsFlyer's conversion callback frequently mis-reports Organic
        // on a fresh install even when we have hard evidence of a tracked
        // entry: the OneLink resolver returned pid/campaign/af_sub*, or
        // the app was launched via a VIEW intent carrying the OneLink URL.
        // This "organic rescue" overrides that: if any hard signal fired,
        // we upgrade the status to Non-organic before hitting config.php.
        //
        // Hard signals (any one is enough):
        //   * OneLink deep-link resolver returned a non-empty payload
        //     containing media_source / campaign / af_c_id / af_sub1-5 /
        //     deep_link_value with a real value.
        //   * The launch URI carried the same fields as query params.
        val hardSignal = hasMarketingHardSignal(campaign.deepLinkFacts()) ||
            hasMarketingHardSignal(campaign.uriFacts())
        val reported = body.optString("af_status").takeIf { it.isNotEmpty() }
        val effective = when {
            hardSignal -> "Non-organic"
            reported != null -> reported
            campaign.wasUriLaunched() -> "Non-organic"
            else -> "Organic"
        }
        body.put("af_status", effective)
        Log.d(
            TAG,
            "af_status resolved to $effective (reported=$reported hardSignal=$hardSignal uri=${campaign.wasUriLaunched()})",
        )

        body.put("af_id", campaign.deviceUid().orEmpty())
        body.put("bundle_id", LinkConfig.APPLICATION_ID)
        body.put("os", "Android")
        body.put("os_version", Build.VERSION.RELEASE)
        body.put("store_id", LinkConfig.STORE_ID)
        body.put("locale", Locale.getDefault().toString().replace('-', '_'))
        body.put("device_model", "${Build.BRAND} ${Build.MODEL}")

        val fcmToken = horn.token
        if (!fcmToken.isNullOrEmpty()) body.put("push_token", fcmToken)
        val firebaseProject = LinkConfig.messagingProject()
        if (firebaseProject.isNotEmpty()) body.put("firebase_project_id", firebaseProject)

        return fetcher.ask(body)
    }

    /**
     * True if the map carries a non-empty value in ANY of the fields that
     * AppsFlyer only fills when a real tracked click landed. Presence of
     * even one is enough to declare the install non-organic.
     */
    private fun hasMarketingHardSignal(facts: Map<String, Any?>): Boolean {
        if (facts.isEmpty()) return false
        return MARKETING_HARD_SIGNAL_KEYS.any { key ->
            val v = facts[key]?.toString().orEmpty()
            v.isNotEmpty() && !v.equals("null", ignoreCase = true)
        }
    }

    private fun launchFireAndForget(): Job = ambientScope.launch {
        runCatching {
            horn.boot()
            val installBag = campaign.awaitInstall(LinkConfig.RETURN_INSTALL_WAIT_MS)
            askChart(installBag)
        }
    }

    companion object {
        private const val TAG = "LinkPilot"

        // Fields that AppsFlyer's OneLink resolver / conversion callback
        // only fills when a real tracked click hit their servers. Any one
        // of them carrying a non-empty value overrides an "Organic" verdict.
        private val MARKETING_HARD_SIGNAL_KEYS = setOf(
            "media_source", "pid",
            "campaign", "c",
            "campaign_id", "af_c_id",
            "af_adset", "adset",
            "af_ad", "af_ad_id",
            "af_sub1", "af_sub2", "af_sub3", "af_sub4", "af_sub5",
            "af_siteid", "siteid",
            "af_channel",
            "deep_link_value", "deep_link_sub1",
            "agency",
            "af_prt", "af_keywords",
        )

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
