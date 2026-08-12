package com.blazingjoker.blazingjokergame.link.net

import android.app.Application
import com.appsflyer.AppsFlyerConversionListener
import com.appsflyer.AppsFlyerLib
import com.blazingjoker.blazingjokergame.link.config.LinkConfig
import com.blazingjoker.blazingjokergame.link.data.ShadedTokens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Wraps the AppsFlyer native SDK. Collects the install-conversion callback
 * and the (optional) deep-link click event, then folds them into the JSON
 * body that the pilot POSTs to the chart endpoint.
 *
 * ORGANIC RESCUE. AppsFlyer occasionally reports `af_status: "Organic"`
 * on the FIRST callback of genuinely paid installs (SDK timing quirk).
 * When that happens we wait a short beat and re-query the GCD endpoint
 * with the AF UID + app id — that response overrides the initial
 * Organic payload. If the GCD call fails we keep the Organic result,
 * which routes the user into the native game (the safe branch).
 *
 * SHORT-CIRCUIT. When no dev key is packed yet (fresh checkout without
 * shaded credentials), `awaitInstall(...)` completes immediately with
 * an empty map so QA can smoke-test the game path.
 */
internal class CampaignBroker(private val app: Application) {

    private var started = false
    private val installReady = CompletableDeferred<Map<String, Any?>>()

    fun boot() {
        if (started) return
        started = true
        val devKey = LinkConfig.attributionKey()
        if (devKey.isEmpty()) {
            installReady.complete(emptyMap())
            return
        }

        val af = AppsFlyerLib.getInstance()
        af.setDebugLog(false)
        af.init(devKey, listener(), app)
        af.start(app)
    }

    private fun listener(): AppsFlyerConversionListener = object : AppsFlyerConversionListener {
        override fun onConversionDataSuccess(data: MutableMap<String, Any>?) {
            val payload = data?.toMap()?.toMutableMap() ?: mutableMapOf()
            val status = payload["af_status"]?.toString()
            if (status == "Organic") {
                // Kick off the rescue asynchronously so we don't block the
                // callback thread; the deferred is resolved from within.
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
            if (!installReady.isCompleted) installReady.complete(emptyMap())
        }

        override fun onAppOpenAttribution(attributionData: MutableMap<String, String>?) {
            // Warm-open attribution is folded into the body on the next chart
            // fetch through appOpenSnapshot(); nothing to do inline.
        }

        override fun onAttributionFailure(reason: String?) { /* no-op */ }
    }

    private fun tryGcdRescue(): Map<String, Any?>? {
        val uid = runCatching { AppsFlyerLib.getInstance().getAppsFlyerUID(app) }
            .getOrNull() ?: return null
        val url = ShadedTokens.gcdRescueUrl(LinkConfig.APPLICATION_ID, uid)
        if (url.isEmpty()) return null
        // Blocking call — we're already on a background rescue thread.
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

    /**
     * Wait (with cap) for the install-conversion payload. Returning launches
     * pass a tighter cap because a returning install typically re-fires the
     * cached data within a couple seconds; first launches wait longer.
     */
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
}
