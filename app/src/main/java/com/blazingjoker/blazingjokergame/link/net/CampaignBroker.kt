package com.blazingjoker.blazingjokergame.link.net

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.appsflyer.AppsFlyerConversionListener
import com.appsflyer.AppsFlyerLib
import com.appsflyer.deeplink.DeepLinkResult
import com.blazingjoker.blazingjokergame.link.config.LinkConfig
import com.blazingjoker.blazingjokergame.link.data.ShadedTokens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps the AppsFlyer native SDK. Split into two phases per the SDK's
 * contract:
 *   1. [wireUp] — subscribeForDeepLink + init. Runs from Application/Activity
 *      onCreate BEFORE any lifecycle callback fires, so the SDK's
 *      ActivityLifecycleCallbacks are registered in time to notice the
 *      first onResume.
 *   2. [start] — MUST receive an Activity, not an Application context.
 *      Passing app here queues the launch event until the next activity
 *      transition (which in our shell only happens ~30s later when the
 *      game menu opens), and the conversion listener silently never fires.
 *
 * URI FALLBACK. AppsFlyer's own callback can arrive tens of seconds late,
 * yet a OneLink URI already carries the whole attribution set (media_source,
 * campaign, deep_link_value, af_sub1…) in its query string. We harvest
 * those params in [notifyLaunchIntent] and expose them via [uriFacts]; the
 * pilot merges them into the POST body so the backend has a non-empty
 * payload to answer even when the SDK stays silent.
 */
internal class CampaignBroker(private val app: Application) {

    private val wired = AtomicBoolean(false)
    private val speaking = AtomicBoolean(false)
    private val installReady = CompletableDeferred<Map<String, Any?>>()

    @Volatile
    private var deepLinkFacts: Map<String, Any?>? = null

    @Volatile
    private var uriFacts: Map<String, Any?>? = null

    @Volatile
    private var uriLaunched: Boolean = false

    /** URI params fetched off the launch intent, exposed for the pilot. */
    fun uriFacts(): Map<String, Any?> = uriFacts.orEmpty()

    /** True once we've been opened via a VIEW intent with a URL. */
    fun wasUriLaunched(): Boolean = uriLaunched

    /** Deep-link callback contents, if any. */
    fun deepLinkFacts(): Map<String, Any?> = deepLinkFacts.orEmpty()

    /**
     * Register the SDK listeners without putting anything on the wire.
     * Call this super early — Activity.onCreate BEFORE super.onCreate
     * ideally, or at least before any View starts drawing.
     */
    fun wireUp() {
        if (!wired.compareAndSet(false, true)) return
        val devKey = LinkConfig.attributionKey()
        Log.d(TAG, "wireUp(); devKey.len=${devKey.length}")
        if (devKey.isEmpty()) {
            installReady.complete(emptyMap())
            return
        }

        runCatching {
            val af = AppsFlyerLib.getInstance()
            af.setDebugLog(true)
            af.subscribeForDeepLink { result ->
                if (result.status == DeepLinkResult.Status.FOUND) {
                    val json: JSONObject? = result.deepLink?.clickEvent
                    val facts = json?.let { obj ->
                        val out = mutableMapOf<String, Any?>()
                        obj.keys().forEach { key -> out[key] = obj.opt(key) }
                        out
                    }
                    Log.d(TAG, "deep-link FOUND keys=${facts?.keys}")
                    if (!facts.isNullOrEmpty()) deepLinkFacts = facts
                } else {
                    Log.d(TAG, "deep-link status=${result.status} error=${result.error}")
                }
            }
            af.init(devKey, listener, app)
        }.onFailure {
            Log.w(TAG, "wireUp failed: ${it.message}")
            if (!installReady.isCompleted) installReady.complete(emptyMap())
        }
    }

    /**
     * Send the launch event. MUST be called from a real Activity (after
     * it's on the screen), otherwise the SDK queues the event and the
     * conversion listener never fires until a later activity transition.
     */
    fun start(host: Activity) {
        wireUp()
        if (LinkConfig.attributionKey().isEmpty()) return
        if (!speaking.compareAndSet(false, true)) return
        runCatching {
            AppsFlyerLib.getInstance().start(host)
            Log.d(TAG, "start(${host.javaClass.simpleName})")
        }.onFailure {
            Log.w(TAG, "start failed: ${it.message}")
            if (!installReady.isCompleted) installReady.complete(emptyMap())
        }
    }

    /**
     * Harvest the launch intent for a OneLink-style URI. Every query
     * parameter is captured and mirrored under the SDK's long-name aliases
     * so the backend can match either shape. Also forwards the intent to
     * the SDK's `performOnDeepLinking` so [subscribeForDeepLink] can fire.
     */
    fun notifyLaunchIntent(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        uriLaunched = true
        val harvested = runCatching {
            val out = linkedMapOf<String, Any?>()
            for (name in uri.queryParameterNames) {
                if (name.isNullOrBlank()) continue
                val value = uri.getQueryParameter(name)
                out[name] = value
                URL_ALIAS[name]?.let { long -> if (!out.containsKey(long)) out[long] = value }
            }
            out
        }.getOrNull().orEmpty()
        if (harvested.isNotEmpty()) uriFacts = harvested
        Log.d(TAG, "notifyLaunchIntent uri=$uri qs_keys=${harvested.keys}")

        if (wired.get()) {
            runCatching {
                AppsFlyerLib.getInstance().performOnDeepLinking(intent, app)
            }.onFailure { Log.w(TAG, "performOnDeepLinking failed: ${it.message}") }
        }
    }

    private val listener = object : AppsFlyerConversionListener {
        override fun onConversionDataSuccess(data: MutableMap<String, Any>?) {
            val payload = data?.toMap()?.toMutableMap() ?: mutableMapOf()
            val status = payload["af_status"]?.toString()
            Log.d(TAG, "onConversionDataSuccess status=$status keys=${payload.keys}")
            if (status == "Organic") {
                Thread {
                    try {
                        Thread.sleep(LinkConfig.ORGANIC_RESCUE_DELAY_MS)
                        val rescued = tryGcdRescue()
                        if (!installReady.isCompleted) {
                            installReady.complete(rescued ?: payload)
                        }
                    } catch (_: Throwable) {
                        if (!installReady.isCompleted) installReady.complete(payload)
                    }
                }.start()
            } else {
                if (!installReady.isCompleted) installReady.complete(payload)
            }
        }

        override fun onConversionDataFail(reason: String?) {
            Log.w(TAG, "onConversionDataFail: $reason")
            if (!installReady.isCompleted) installReady.complete(emptyMap())
        }

        override fun onAppOpenAttribution(attributionData: MutableMap<String, String>?) {
            Log.d(TAG, "onAppOpenAttribution keys=${attributionData?.keys}")
        }

        override fun onAttributionFailure(reason: String?) {
            Log.w(TAG, "onAttributionFailure: $reason")
        }
    }

    private fun tryGcdRescue(): Map<String, Any?>? {
        val uid = runCatching { AppsFlyerLib.getInstance().getAppsFlyerUID(app) }
            .getOrNull() ?: return null
        val url = ShadedTokens.gcdRescueUrl(LinkConfig.APPLICATION_ID, uid)
        if (url.isEmpty()) return null
        val cx = try {
            java.net.URL(url).openConnection() as java.net.HttpURLConnection
        } catch (_: Throwable) {
            return null
        }
        return try {
            cx.connectTimeout = 8_000
            cx.readTimeout = 12_000
            cx.requestMethod = "GET"
            cx.setRequestProperty("User-Agent", AgentForge.line)
            cx.setRequestProperty("authorization", "Bearer ${LinkConfig.attributionKey()}")
            if (cx.responseCode == 200) {
                val body = cx.inputStream.bufferedReader().use { it.readText() }
                jsonToMap(JSONObject(body))
            } else null
        } catch (_: Throwable) {
            null
        } finally {
            cx.disconnect()
        }
    }

    private fun jsonToMap(obj: JSONObject): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>()
        obj.keys().forEach { key -> m[key] = obj.opt(key) }
        return m
    }

    /** Wait (with cap) for the install-conversion payload. */
    suspend fun awaitInstall(capMs: Long): Map<String, Any?> {
        if (LinkConfig.attributionKey().isEmpty()) return emptyMap()
        return try {
            withTimeoutOrNull(capMs) { installReady.await() } ?: emptyMap()
        } catch (_: TimeoutCancellationException) {
            emptyMap()
        }
    }

    fun deviceUid(): String? = runCatching {
        AppsFlyerLib.getInstance().getAppsFlyerUID(app)
    }.getOrNull()

    companion object {
        private const val TAG = "CampaignBroker"

        /** OneLink short-param → conversion-callback long-name mirror. */
        private val URL_ALIAS = mapOf(
            "pid" to "media_source",
            "c" to "campaign",
            "af_c_id" to "campaign_id",
            "af_channel" to "channel",
            "af_ad" to "ad",
            "af_ad_id" to "ad_id",
            "af_adset_id" to "adset_id",
            "af_adset" to "adset",
            "af_ad_type" to "ad_type",
            "af_keywords" to "keywords",
            "siteid" to "af_siteid",
        )
    }
}
