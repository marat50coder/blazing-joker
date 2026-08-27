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
 *   2. Prime the gray-flow subsystem: initialise [FirebaseApp] before
 *      any FCM payload arrives, create the notification channel so the
 *      very first push after install can render a tray entry, and wire
 *      the AppsFlyer conversion + deep-link listeners via
 *      [LinkPilot.wireUp] so the SDK sees the first `onResume` with the
 *      REAL listener in place.
 *   3. Bootstrap Firebase Analytics + consent state through
 *      [Analytics.bootstrap]. It intentionally does NOT touch AppsFlyer
 *      — see the doc-comment on that method for the rationale.
 *
 * Order matters: LinkPilot.wireUp MUST run before Analytics.bootstrap.
 * Otherwise Analytics would call `AppsFlyerLib.start` with the
 * Application context, anchor the SDK to a "no live Activity" state,
 * and the real conversion listener registered by CampaignBroker later
 * would silently never fire — every attribution POST would go out with
 * empty af_sub* fields.
 */
class BlazingJokerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        GamePrefs.init(this)

        runCatching {
            if (FirebaseApp.getApps(this).isEmpty()) FirebaseApp.initializeApp(this)
        }
        runCatching { HornService.ensureChannel(this) }

        runCatching { LinkPilot.of(this).wireUp() }
        Analytics.bootstrap(this)
    }
}
