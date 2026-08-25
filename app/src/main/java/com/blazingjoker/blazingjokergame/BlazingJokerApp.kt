package com.blazingjoker.blazingjokergame

import android.app.Application

/**
 * Process entry point. Two responsibilities:
 *   1. Warm [GamePrefs] so on-device settings are ready before the very
 *      first Activity queries them (Sfx uses this in a static init).
 *   2. Bootstrap the native-flow analytics stack — Firebase Analytics
 *      and AppsFlyer — on EVERY launch, independent of the gray-flow's
 *      credential gate. The gray flow's own boot pipeline
 *      (`LinkPilot` + `CampaignBroker`) still runs its parallel init
 *      inside LoadingActivity; the two AppsFlyer inits collapse to a
 *      single SDK singleton so there is no double reporting.
 */
class BlazingJokerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        GamePrefs.init(this)
        Analytics.bootstrap(this)
    }
}
