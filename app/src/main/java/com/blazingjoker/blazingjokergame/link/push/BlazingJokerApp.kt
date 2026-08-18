package com.blazingjoker.blazingjokergame.link.push

import android.app.Application
import com.blazingjoker.blazingjokergame.link.LinkPilot
import com.google.firebase.FirebaseApp

/**
 * Application entry point.
 *
 * Two responsibilities:
 *   1. Initialise Firebase and the FCM notification channel BEFORE any
 *      push arrives. Without this, the very first message the device
 *      receives on a fresh install lands on a service with no channel,
 *      and Android silently drops the tray entry. `HornService.ensure
 *      Channel` is idempotent so calling it here + in the FCM service is
 *      free insurance.
 *   2. Wire AppsFlyer listeners up as early as we can (long before any
 *      activity's `onResume`). The actual `start(Activity)` call still
 *      lives in `LoadingActivity.onResume` — see
 *      [LinkPilot.wireUp] / [LinkPilot.start].
 */
class BlazingJokerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        runCatching {
            if (FirebaseApp.getApps(this).isEmpty()) FirebaseApp.initializeApp(this)
        }
        runCatching { HornService.ensureChannel(this) }
        runCatching { LinkPilot.of(this).wireUp() }
    }
}
