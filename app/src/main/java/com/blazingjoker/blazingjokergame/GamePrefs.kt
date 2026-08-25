package com.blazingjoker.blazingjokergame

import android.content.Context
import android.content.SharedPreferences

/**
 * Small typed wrapper around a dedicated SharedPreferences file for the
 * native menu-side settings. Kept intentionally separate from the gray
 * flow's `StowageBox` so wiping "Reset progress" here never touches the
 * link subsystem's attribution latches or cached URLs.
 */
object GamePrefs {

    private const val FILE = "bj_game_prefs"
    private const val KEY_SFX = "sfx_enabled"
    private const val KEY_ANALYTICS = "analytics_enabled"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    private fun ensure(context: Context): SharedPreferences {
        if (!::prefs.isInitialized) init(context)
        return prefs
    }

    var sfxEnabled: Boolean
        get() = prefs.getBoolean(KEY_SFX, true)
        set(v) { prefs.edit().putBoolean(KEY_SFX, v).apply() }

    /**
     * When false, both AppsFlyer and Firebase Analytics collection are
     * paused (see [Analytics.applyCollectionPreference]). Opt-out is
     * user-facing on the Settings screen; default is "on" to match the
     * granted consent captured at install time (Google Play's data
     * privacy label + our privacy policy).
     */
    var analyticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ANALYTICS, true)
        set(v) { prefs.edit().putBoolean(KEY_ANALYTICS, v).apply() }

    fun sfxEnabled(context: Context): Boolean = ensure(context).getBoolean(KEY_SFX, true)
    fun analyticsEnabled(context: Context): Boolean = ensure(context).getBoolean(KEY_ANALYTICS, true)

    /**
     * Wipes settings back to defaults. Intentionally does NOT touch the
     * gray flow's StowageBox — resetting attribution/cached URLs is
     * governed by app reinstall, not a menu toggle.
     */
    fun resetAll(context: Context) {
        ensure(context).edit().clear().apply()
    }
}
