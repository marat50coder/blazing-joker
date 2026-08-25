package com.blazingjoker.blazingjokergame.link.net

import android.content.Context
import android.net.Uri
import android.util.Log
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Reads the Google Play Install Referrer via IPC to the Play Store app.
 *
 * The referrer is delivered by the Play Store binder at install time and
 * is available on the very first launch **without a working internet
 * connection** — the IPC is local. This is the ONLY channel that lets a
 * OneLink install be recognised as non-organic when the app is opened
 * before the device comes online: AppsFlyer's own conversion callback
 * needs a working network to reach AF servers, so on offline first-launch
 * the pilot has no other way to know a tracked click landed.
 *
 * The referrer string is a URL-encoded `k=v&k=v` blob (RFC 3986 query
 * syntax). OneLink clicks typically carry `pid`, `c`/`campaign`,
 * `af_c_id`, `media_source`, `af_click_lookback`, `af_sub1..5`,
 * `deep_link_value`, `deep_link_sub1`, etc. Organic installs report an
 * empty referrer or `utm_source=google-play&utm_medium=organic`.
 */
internal class InstallReferrerProbe(context: Context) {

    private val app = context.applicationContext

    /**
     * Connects to the Play Store install-referrer service, reads the
     * referrer URL, parses it, and returns the flat parameter map.
     * Returns `null` if the service was unavailable (e.g. no Play Store,
     * device not Google-certified) or the referrer string was empty.
     *
     * The suspending function completes as soon as the service replies
     * or the connection fails — the caller is expected to wrap it in a
     * `withTimeoutOrNull(...)` so a stuck binder cannot block boot.
     */
    suspend fun probe(): Map<String, String>? = suspendCancellableCoroutine { cont ->
        val client = InstallReferrerClient.newBuilder(app).build()
        cont.invokeOnCancellation { runCatching { client.endConnection() } }
        try {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    val result: Map<String, String>? = try {
                        when (responseCode) {
                            InstallReferrerClient.InstallReferrerResponse.OK -> {
                                val raw = client.installReferrer.installReferrer
                                Log.d(TAG, "onSetupFinished OK; raw=$raw")
                                parse(raw)
                            }
                            else -> {
                                Log.d(TAG, "onSetupFinished code=$responseCode")
                                null
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "install referrer read failed: ${t.message}")
                        null
                    } finally {
                        runCatching { client.endConnection() }
                    }
                    if (cont.isActive) cont.resume(result)
                }

                override fun onInstallReferrerServiceDisconnected() {
                    // Service crashed between startConnection and setup. Not
                    // fatal — the caller's timeout will fire and boot continues.
                    Log.d(TAG, "install referrer service disconnected")
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "install referrer connect failed: ${t.message}")
            runCatching { client.endConnection() }
            if (cont.isActive) cont.resume(null)
        }
    }

    /**
     * Parse a `k=v&k=v` referrer blob into a flat map. Empty keys and
     * empty values are dropped. Values are URL-decoded (Android's Uri
     * parser handles this automatically when the string is embedded in
     * a fake URL).
     */
    private fun parse(raw: String?): Map<String, String>? {
        if (raw.isNullOrEmpty()) return null
        return runCatching {
            val uri = Uri.parse("dummy://ref?$raw")
            val out = linkedMapOf<String, String>()
            for (name in uri.queryParameterNames.orEmpty()) {
                if (name.isNullOrBlank()) continue
                val v = uri.getQueryParameter(name).orEmpty()
                if (v.isNotEmpty() && !v.equals("null", ignoreCase = true)) out[name] = v
            }
            out.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "InstallReferrer"
    }
}
