package com.blazingjoker.blazingjokergame.link.config

import com.blazingjoker.blazingjokergame.link.data.ShadedTokens

/**
 * Project-wide constants for the gray flow. Public identity values (bundle
 * id, display name, notification-channel key) stay as plain constants —
 * they appear in the manifest and the store listing anyway; encoding them
 * would only look suspicious. Everything else resolves lazily through
 * `ShadedTokens.*` so no plaintext credential ships as a string literal.
 *
 * Timing constants live here as ordinary numbers — every value has an
 * intentionally off-round value so two sibling apps never share the exact
 * same delay tuple.
 */
internal object LinkConfig {

    // Identity
    const val APPLICATION_ID = "com.blazingjoker.blazingjokergame"
    const val DISPLAY_NAME = "BlazingJoker"
    const val STORE_ID = "com.blazingjoker.blazingjokergame"

    // Persistence namespace (kept short & unrelated to the slug)
    const val PREFS_NAME = "bj_link_prefs"
    const val SECURE_PREFS_NAME = "bj_link_secure"
    const val KEY_PREFIX = "z7q_"

    // ── Timings (all off-round; keep well outside sibling-app defaults) ──
    /**
     * Snooze after the user taps Skip on the opt-in stage.
     *
     * Spec: reappear after ~3 days. MUST stay strictly under 259 200 s
     * (exactly 3 d) so a tester who skips then jumps the device clock
     * forward by 3 days actually sees the screen again. 257 903 s is
     * 2 d 23 h 38 m — still "three days" from the user's seat, jittered
     * off the round 3-day literal.
     *
     * Skip must NOT fire the OS permission dialog. An OS-level
     * "Don't allow" after Accept is a separate path in
     * [AlertOptInActivity.permissionAsk].
     */
    const val OPT_IN_SNOOZE_SECONDS = 257_903L
    /** Delay before rescuing an af_status=Organic first callback. */
    const val ORGANIC_RESCUE_DELAY_MS = 6_400L
    /** POST timeout for the chart request. */
    const val CHART_TIMEOUT_MS = 18_000L
    /** How long to wait for AppsFlyer install-conversion on FRESH launch. */
    const val FIRST_INSTALL_WAIT_MS = 26_000L
    /** How long to wait for install-conversion on RETURNING launch. */
    const val RETURN_INSTALL_WAIT_MS = 5_400L
    /** DNS probe timeout — 6 s absorbs slow VPN tunnels without user pain. */
    const val DNS_PROBE_TIMEOUT_MS = 6_200L
    /**
     * Debounce before treating a connectivity-drop burst as real
     * offline. Same shape as SkyLadder's `reachDropDebounceMs`
     * (range 500..1200): long enough that a Wi-Fi → cellular hand-off
     * (`onLost` then `onAvailable` of the other transport) cancels the
     * timer, short enough to feel instant when every adapter is gone.
     * The runnable itself does NOT re-query ConnectivityManager —
     * at the instant of `onLost` the dying network is still reported
     * as active and that lie used to swallow the drop.
     */
    const val LINK_DROP_DEBOUNCE_MS = 710L
    /** How many main-frame redirect-loop retries in the WebView. */
    const val REDIRECT_LOOP_RETRIES = 2
    /** Cached destination lifetime (days: ~5.4). */
    const val CACHED_DESTINATION_LIFETIME_SECONDS = 470_000L

    // ── Notification channel (public — matches AndroidManifest string) ──
    const val HORN_CHANNEL_KEY = "bj_link_horn_v1"
    const val HORN_CHANNEL_LABEL = "Updates"

    // ── Resolvers ─────────────────────────────────────────────────
    fun endpointUrl(): String = ShadedTokens.endpointUrl()
    fun attributionKey(): String = ShadedTokens.attributionKey()
    fun messagingProject(): String = ShadedTokens.messagingProject()
    fun homeUrl(): String = ShadedTokens.homeUrl()

    /**
     * Gate for the whole gray subsystem. Until this returns true the boot
     * pipeline short-circuits straight to the native game — safe default
     * for QA on a checkout without credentials plumbed in.
     */
    val credentialsReady: Boolean
        get() = endpointUrl().isNotEmpty() &&
                attributionKey().isNotEmpty() &&
                messagingProject().isNotEmpty()
}
