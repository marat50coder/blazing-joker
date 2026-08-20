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
     * Spec: the opt-in screen must reappear after ~2 d 20 h. Value is
     * deliberately jittered off the exact 68 h round number (244 800 s)
     * because that literal collides with a sibling project's
     * `OPT_IN_REST_SECONDS` — a byte-identical numeric constant across
     * two apps is a trivial cross-project fingerprint. 245 833 s adds
     * 17 min 13 s of jitter (still within the "≈2 d 20 h" spec bucket
     * for the user) and drops the shared literal.
     *
     * An OS-level "Don't allow" is a separate, permanent, hard-block
     * path that lives in [AlertOptInActivity.permissionAsk].
     */
    const val OPT_IN_SNOOZE_SECONDS = 245_833L
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
    /** Debounce before the "no link" screen shows after a live drop. */
    const val LINK_DROP_DEBOUNCE_MS = 820L
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
