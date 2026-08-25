package com.blazingjoker.blazingjokergame

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.appsflyer.AFInAppEventType
import com.appsflyer.AppsFlyerConversionListener
import com.appsflyer.AppsFlyerLib
import com.blazingjoker.blazingjokergame.link.data.ShadedTokens
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase

/**
 * Thin facade in front of the two analytics SDKs the native (white) flow
 * cares about — AppsFlyer for install attribution / lifetime events and
 * Firebase Analytics for in-app event funneling. Both SDKs are
 * initialised on every launch through [bootstrap] from the Application
 * class, independent of the gray-flow's own `CampaignBroker` (which
 * remains gated behind `LinkConfig.credentialsReady`).
 *
 * Consent is respected: when the user flips "Send anonymous analytics"
 * off in Settings, [applyCollectionPreference] pauses AppsFlyer and
 * disables Firebase Analytics collection; flipping it back on resumes
 * both immediately without needing to restart the app.
 */
object Analytics {

    private const val TAG = "Analytics"

    @Volatile private var bootstrapped = false
    private var fa: FirebaseAnalytics? = null

    /** Called exactly once from [BlazingJokerApp.onCreate]. Idempotent. */
    fun bootstrap(app: Application) {
        if (bootstrapped) return
        bootstrapped = true

        runCatching { FirebaseApp.initializeApp(app) }
            .onFailure { Log.w(TAG, "FirebaseApp.initializeApp failed: ${it.message}") }

        fa = runCatching { Firebase.analytics }.getOrNull()
        if (fa == null) Log.w(TAG, "Firebase.analytics unavailable — logging is a no-op")

        val afKey = runCatching { ShadedTokens.attributionKey() }.getOrDefault("")
        if (afKey.isNotEmpty()) {
            runCatching {
                val af = AppsFlyerLib.getInstance()
                af.init(afKey, defaultConversionListener(), app.applicationContext)
                af.start(app.applicationContext)
                Log.d(TAG, "AppsFlyer init + start (key length=${afKey.length})")
            }.onFailure { Log.w(TAG, "AppsFlyer init failed: ${it.message}") }
        } else {
            Log.d(TAG, "AppsFlyer key empty — attribution disabled")
        }

        applyCollectionPreference(app, GamePrefs.analyticsEnabled(app))
    }

    /**
     * Pause / resume both SDKs to match the user's consent toggle. The
     * SDKs are still initialised — they just stop reporting until the
     * user opts back in. AppsFlyer's `stop(true)` is documented as
     * safe to call repeatedly and reversible.
     */
    fun applyCollectionPreference(context: Context, enabled: Boolean) {
        runCatching {
            AppsFlyerLib.getInstance().stop(!enabled, context.applicationContext)
        }.onFailure { Log.w(TAG, "AppsFlyer.stop(${!enabled}) failed: ${it.message}") }
        runCatching {
            fa?.setAnalyticsCollectionEnabled(enabled)
        }.onFailure { Log.w(TAG, "setAnalyticsCollectionEnabled($enabled) failed: ${it.message}") }
        Log.d(TAG, "collection preference applied: enabled=$enabled")
    }

    // ── Event API ─────────────────────────────────────────────────

    fun menuOpened() = log("menu_open")
    fun settingsOpened() = log("settings_open")
    fun gameStart() = log("game_start")
    fun gameWin(wave: Int) = log("game_win", "wave" to wave)
    fun gameLose(wave: Int, endless: Boolean) =
        log("game_lose", "wave" to wave, "endless" to endless)
    fun endlessStarted() = log("endless_started")

    /**
     * Generic key-value event bridged to both SDKs. Firebase gets a
     * proper typed [Bundle]; AppsFlyer gets a `Map<String, Any>` under
     * the same event name so backend joins line up.
     */
    fun log(name: String, vararg params: Pair<String, Any>) {
        val bundle = Bundle().apply {
            for ((k, v) in params) {
                when (v) {
                    is Int -> putInt(k, v)
                    is Long -> putLong(k, v)
                    is Float -> putFloat(k, v)
                    is Double -> putDouble(k, v)
                    is Boolean -> putBoolean(k, v)
                    else -> putString(k, v.toString())
                }
            }
        }
        runCatching { fa?.logEvent(name, bundle) }
        runCatching {
            val map = params.associate { it.first to it.second as Any }
            AppsFlyerLib.getInstance()
                .logEvent(null, name.toAppsFlyerEvent(), map)
        }
    }

    /**
     * Maps a small set of our own event names to AppsFlyer's canonical
     * in-app event catalog (`af_*`); everything else is forwarded as-is
     * so custom funnels stay legible in the AppsFlyer dashboard.
     */
    private fun String.toAppsFlyerEvent(): String = when (this) {
        "game_start" -> AFInAppEventType.LEVEL_ACHIEVED
        "game_win" -> AFInAppEventType.ACHIEVEMENT_UNLOCKED
        "game_lose" -> "af_content_view"
        else -> this
    }

    /**
     * No-op conversion listener. The gray flow has its own listener in
     * `CampaignBroker` that overwrites this one when the pilot inits
     * AppsFlyer for attribution; on the white flow it is enough to
     * simply have SOME listener so `AppsFlyerLib.init` doesn't warn
     * about a missing callback.
     */
    private fun defaultConversionListener() = object : AppsFlyerConversionListener {
        override fun onConversionDataSuccess(data: MutableMap<String, Any>?) {}
        override fun onConversionDataFail(error: String?) {}
        override fun onAppOpenAttribution(data: MutableMap<String, String>?) {}
        override fun onAttributionFailure(error: String?) {}
    }
}
