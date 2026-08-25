package com.blazingjoker.blazingjokergame.link.store

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.blazingjoker.blazingjokergame.link.config.LinkConfig
import com.blazingjoker.blazingjokergame.link.data.LastCourse

/**
 * Persistent state for the gray flow.
 *
 * Everything that reveals no user intent (last course, permission snooze
 * timestamps, expiry timestamps) lives in a plain SharedPreferences file.
 * Anything that would leak the actual destination URL on a `pm-user-cache`
 * dump lives in an encrypted preferences file backed by the AndroidKeyStore
 * master key.
 *
 * Key names are namespaced by [LinkConfig.KEY_PREFIX] — a short random
 * ASCII token unrelated to the app slug. Two sibling apps sharing the same
 * prefix create a trivial cross-app cluster edge.
 */
internal class StowageBox(context: Context) {

    private val app = context.applicationContext

    private val flat: SharedPreferences =
        app.getSharedPreferences(LinkConfig.PREFS_NAME, Context.MODE_PRIVATE)

    // EncryptedSharedPreferences can fail to open when the AndroidKeyStore
    // has been tampered with (device reset, master-key rotation). In that
    // case we blow the file away and rebuild — never crash the process.
    private val secure: SharedPreferences = openSecureOrRebuild()

    private fun openSecureOrRebuild(): SharedPreferences {
        return try {
            val key = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app,
                LinkConfig.SECURE_PREFS_NAME,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Throwable) {
            app.deleteSharedPreferences(LinkConfig.SECURE_PREFS_NAME)
            val key = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app,
                LinkConfig.SECURE_PREFS_NAME,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    private val kCourse = "${LinkConfig.KEY_PREFIX}course"
    private val kCachedUrl = "${LinkConfig.KEY_PREFIX}dst"
    private val kCachedExpiry = "${LinkConfig.KEY_PREFIX}dst_ttl"
    private val kOptInSnooze = "${LinkConfig.KEY_PREFIX}opt_until"
    private val kOptInGranted = "${LinkConfig.KEY_PREFIX}opt_ok"
    private val kOptInHardBlock = "${LinkConfig.KEY_PREFIX}opt_hard_no"
    private val kOptInOsAsked = "${LinkConfig.KEY_PREFIX}opt_os_asked"
    private val kPendingUrl = "${LinkConfig.KEY_PREFIX}pending"
    private val kNonOrganicLatch = "${LinkConfig.KEY_PREFIX}nonorg"
    private val kOrganicLatch = "${LinkConfig.KEY_PREFIX}org"
    private val kReferrerProbed = "${LinkConfig.KEY_PREFIX}ref_done"

    // ── Last course ────────────────────────────────────────────────
    var course: LastCourse
        get() = LastCourse.parse(flat.getString(kCourse, null))
        set(value) {
            flat.edit().putString(kCourse, value.wire).apply()
        }

    // ── Non-organic attribution latch ──────────────────────────────
    /**
     * Sticky "this device has been seen as Non-organic at least once"
     * flag. Any launch that carried a marketing hard signal (OneLink
     * URI, deep-link callback, af_sub / media_source / campaign, or a
     * VIEW-intent-driven cold start) sets this true, and it never goes
     * back to false. Consumed by [LinkPilot] to prevent a subsequent
     * "Organic" AppsFlyer replay from demoting a real non-organic user
     * to sticky Native — the reported problem was: OneLink → gray;
     * device time skip; second launch AppsFlyer replays Organic + the
     * chart backend replies HTTP 404 "No data" → old code committed
     * course=Native and the user was trapped on the white part
     * forever. With this latch the pilot keeps re-fetching until the
     * backend really has a URL to hand back.
     */
    var attributedNonOrganic: Boolean
        get() = flat.getBoolean(kNonOrganicLatch, false)
        set(v) {
            // Never unset — the latch is one-way. If a subsequent
            // launch reports Organic, we still trust the earlier
            // Non-organic verdict.
            if (v) flat.edit().putBoolean(kNonOrganicLatch, true).apply()
        }

    // ── Organic attribution latch ──────────────────────────────────
    /**
     * Sticky "this device has been DEFINITIVELY resolved as Organic"
     * flag. Set the first time the chart backend actually answers and
     * that answer classifies the install as organic (no destination
     * URL, no marketing hard signal). Like [attributedNonOrganic] it is
     * one-way and survives until the app is reinstalled.
     *
     * Once set, [LinkPilot] short-circuits every future launch to the
     * native (white) game and never re-runs the attribution decision —
     * so a device that resolved organic once can never be flipped into
     * the gray flow (even by a later OneLink tap) short of a reinstall.
     * This mirrors the non-organic latch so BOTH verdicts are permanent
     * once the first real `af_status` resolution lands, which is the
     * behavior the field report asked for: "first resolved status wins
     * until reinstall, in both directions".
     *
     * The setter refuses to fire when the non-organic latch is already
     * set — non-organic always wins a tie.
     */
    var attributedOrganic: Boolean
        get() = flat.getBoolean(kOrganicLatch, false)
        set(v) {
            if (v && !attributedNonOrganic) {
                flat.edit().putBoolean(kOrganicLatch, true).apply()
            }
        }

    // ── Install-referrer probe latch ───────────────────────────────
    /**
     * True once we have successfully queried the Google Play Install
     * Referrer at least once. The referrer string is delivered by the
     * Play Store binder at install time and never changes for the
     * lifetime of the install, so a second probe is a pure waste —
     * this flag lets us skip it on every launch after the first.
     */
    var referrerProbed: Boolean
        get() = flat.getBoolean(kReferrerProbed, false)
        set(v) {
            if (v) flat.edit().putBoolean(kReferrerProbed, true).apply()
        }

    // ── Cached destination URL (secure) ────────────────────────────
    fun cachedDestination(): String? = secure.getString(kCachedUrl, null)

    fun cacheDestination(url: String, expiresUnix: Long?) {
        secure.edit().putString(kCachedUrl, url).apply()
        val until = expiresUnix ?: (nowSeconds() + LinkConfig.CACHED_DESTINATION_LIFETIME_SECONDS)
        flat.edit().putLong(kCachedExpiry, until).apply()
    }

    val cachedDestinationExpired: Boolean
        get() = nowSeconds() >= flat.getLong(kCachedExpiry, 0L)

    fun clearCachedDestination() {
        secure.edit().remove(kCachedUrl).apply()
        flat.edit().remove(kCachedExpiry).apply()
    }

    // ── Opt-in permission stage state ─────────────────────────────
    val optInGranted: Boolean get() = flat.getBoolean(kOptInGranted, false)
    fun markOptInGranted(v: Boolean) {
        flat.edit().putBoolean(kOptInGranted, v).apply()
    }

    val optInHardBlocked: Boolean get() = flat.getBoolean(kOptInHardBlock, false)
    fun markOptInHardBlocked() {
        flat.edit().putBoolean(kOptInHardBlock, true).apply()
    }

    fun snoozeOptIn(seconds: Long) {
        flat.edit().putLong(kOptInSnooze, nowSeconds() + seconds).apply()
    }

    /**
     * Should we show the opt-in promo before the WebView?
     *
     * A silent close of the promo (task swipe, home, no button tap) MUST
     * leave every flag untouched so the next launch shows the screen
     * again — user hasn't chosen yet.
     *
     * This overload is kept for callers that cannot supply an Activity
     * (e.g. background work). Prefer the Activity-aware version below —
     * it also reconciles the internal flags with the OS-level permission
     * state and with the system notification switch, so the screen never
     * reappears after the user has already granted (or system-disabled)
     * notifications outside our own UI.
     */
    val shouldInvitePermission: Boolean
        get() {
            val granted = optInGranted
            val hard = optInHardBlocked
            val until = flat.getLong(kOptInSnooze, 0L)
            val now = nowSeconds()
            val decision = when {
                granted -> false
                hard -> false
                else -> now >= until
            }
            Log.d(
                TAG,
                "shouldInvitePermission=$decision (granted=$granted, hardBlocked=$hard, " +
                    "snoozeUntil=$until, now=$now, remaining=${until - now}s)"
            )
            return decision
        }

    /**
     * Activity-aware form. Uses the OS state as the source of truth for
     * "already granted" and "hard-blocked", so a user who granted the
     * permission through system settings (or via Play install-time
     * prompt on API 33+) never sees our screen again — and, conversely,
     * a user who has NOT explicitly acted through our UI keeps seeing it
     * on every cold launch.
     *
     * This is intentionally the ONLY place that mutates the two "done"
     * flags on OS observation. No lifecycle callback that runs without
     * the user pressing Accept or Skip may write to them.
     */
    fun shouldInvitePermission(activity: Activity): Boolean {
        if (optInGranted) {
            Log.d(TAG, "shouldInvitePermission(activity)=false — internal granted")
            return false
        }
        if (optInHardBlocked) {
            Log.d(TAG, "shouldInvitePermission(activity)=false — internal hardBlocked")
            return false
        }
        if (osNotificationsGranted(activity)) {
            Log.d(TAG, "shouldInvitePermission(activity)=false — OS-granted, syncing")
            flat.edit().putBoolean(kOptInGranted, true).apply()
            return false
        }
        if (osNotificationsHardBlocked(activity)) {
            Log.d(TAG, "shouldInvitePermission(activity)=false — OS hard-blocked, syncing")
            flat.edit().putBoolean(kOptInHardBlock, true).apply()
            return false
        }
        val until = flat.getLong(kOptInSnooze, 0L)
        val now = nowSeconds()
        val decision = now >= until
        Log.d(
            TAG,
            "shouldInvitePermission(activity)=$decision (snoozeUntil=$until, now=$now, " +
                "remaining=${until - now}s)"
        )
        return decision
    }

    /** On API 33+ the runtime permission is the authoritative signal. */
    private fun osNotificationsGranted(activity: Activity): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                activity, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * On API 33+ "permanent refusal" reads exactly like "never asked":
     * both return DENIED and `shouldShowRequestPermissionRationale`
     * false. The only reliable separator is a per-app "we did ask at
     * least once" latch — [wasNotificationAsked] — which is only set
     * from the RequestPermission callback (i.e. after a REAL OS dialog).
     *
     * Below API 33 there is no runtime permission; a disabled system
     * switch is the OS "no" signal.
     */
    private fun osNotificationsHardBlocked(activity: Activity): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wasNotificationAsked &&
                !activity.shouldShowRequestPermissionRationale(
                    Manifest.permission.POST_NOTIFICATIONS,
                )
        } else {
            !NotificationManagerCompat.from(activity).areNotificationsEnabled()
        }
    }

    /**
     * True once the OS permission dialog has actually been requested by
     * us at least once. Skip taps never touch this — so a skip cannot
     * fake a "permanent refusal" on subsequent launches.
     */
    var wasNotificationAsked: Boolean
        get() = flat.getBoolean(kOptInOsAsked, false)
        set(v) = flat.edit().putBoolean(kOptInOsAsked, v).apply()

    // ── Cold-tap pending URL (secure, one-shot) ───────────────────
    fun stashPendingUrl(url: String?) {
        val e = secure.edit()
        if (url.isNullOrEmpty()) e.remove(kPendingUrl) else e.putString(kPendingUrl, url)
        e.apply()
    }

    fun consumePendingUrl(): String? {
        val v = secure.getString(kPendingUrl, null)
        if (!v.isNullOrEmpty()) secure.edit().remove(kPendingUrl).apply()
        return v?.takeIf { it.isNotEmpty() }
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    companion object {
        private const val TAG = "StowageBox"
    }
}
