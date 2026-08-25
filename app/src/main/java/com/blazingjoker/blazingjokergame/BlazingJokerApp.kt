package com.blazingjoker.blazingjokergame

import android.app.Application
import com.blazingjoker.blazingjokergame.link.LinkPilot
import com.blazingjoker.blazingjokergame.link.push.HornService
import com.google.firebase.FirebaseApp

/**
 * Process entry point. Merged from the two former Application classes
 * that lived in the white and gray branches so the merged build has a
 * single boot path.
 *
 * Responsibilities:
 *   1. Warm [GamePrefs] so on-device settings are ready before the very
 *      first Activity queries them (Sfx reads this in a static init).
 *   2. Bootstrap the native-flow analytics stack — Firebase Analytics
 *      and AppsFlyer — on EVERY launch through [Analytics.bootstrap],
 *      independent of the gray-flow credentials gate.
 *   3. Prime the gray-flow subsystem: initialise [FirebaseApp] before
 *      any FCM payload arrives, create the notification channel so the
 *      very first push after install can render a tray entry, and wire
 *      the AppsFlyer conversion listeners (via [LinkPilot.wireUp]) so
 *      the SDK sees the first `onResume` even without a live Activity.
 *
 * The two AppsFlyer inits from Analytics.bootstrap and LinkPilot.wireUp
 * collapse to a single SDK singleton — there is no double-reporting.
 */
class BlazingJokerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        GamePrefs.init(this)

        runCatching {
            if (FirebaseApp.getApps(this).isEmpty()) FirebaseApp.initializeApp(this)
        }
        runCatching { HornService.ensureChannel(this) }

        // Order matters: Analytics.bootstrap installs a no-op AppsFlyer
        // conversion listener; LinkPilot.wireUp then re-inits AppsFlyer
        // with the real gray-flow listener that drives attribution.
        // Reversing this order would leave the gray-flow with a noop
        // listener and break OneLink routing.
        Analytics.bootstrap(this)
        runCatching { LinkPilot.of(this).wireUp() }
    }
}
