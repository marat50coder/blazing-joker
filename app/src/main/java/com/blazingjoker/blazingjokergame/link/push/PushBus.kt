package com.blazingjoker.blazingjokergame.link.push

/**
 * Tiny in-process relay for push-tap URLs that arrive while the WebView
 * shell is alive on screen. Without it the tap intent goes to
 * `LoadingActivity` (the only exported entry point), which then relaunches
 * the pilot and drops a fresh WebCanvasActivity on top of the existing
 * one — visually a "loading → new page" flash for a hand-off that could
 * have been a single `loadUrl()` call on the live WebView.
 *
 * Contract:
 *   • `WebCanvasActivity` sets [shellAlive] = true in onCreate and clears
 *     it in onDestroy. `onWarmUrl` is set in onStart / onNewIntent and
 *     cleared in onStop so a paused shell does not consume URLs meant
 *     for the next foregrounded one.
 *   • `LoadingActivity` calls [handOver] on a cold-tap URL. If a live
 *     shell claims it, LoadingActivity finishes without dispatching so
 *     the shell just loads the URL under whatever the user is currently
 *     reading.
 *
 * The bus is intentionally global + weak-ref free. The shell lifetime IS
 * the process lifetime for this project — there is no scenario where a
 * dangling callback outlives the activity because we clear it on both
 * onStop and onDestroy.
 */
internal object PushBus {

    /** True between WebCanvasActivity.onCreate and onDestroy. */
    @Volatile
    var shellAlive: Boolean = false

    /** Set by the live shell in onStart / cleared in onStop. */
    @Volatile
    var onWarmUrl: ((String) -> Unit)? = null

    /**
     * Try to deliver a push URL to the live shell. Returns true iff a
     * subscriber accepted it; false means the shell is either backgrounded
     * or destroyed and the caller should fall through to its own dispatch
     * (LoadingActivity does this by continuing the pilot pipeline).
     */
    fun handOver(url: String): Boolean {
        if (url.isBlank()) return false
        val listener = onWarmUrl ?: return false
        runCatching { listener(url) }
        return true
    }
}
