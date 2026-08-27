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
import org.json.JSONObject

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
    private val kReferrerFacts = "${LinkConfig.KEY_PREFIX}ref_facts"

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

    /**
     * Flat map of the Google Play Install Referrer facts as parsed by
     * [com.blazingjoker.blazingjokergame.link.net.InstallReferrerProbe]
     * on the first launch. Persisted so subsequent launches (which skip
     * the IPC after [referrerProbed]) still have `pid` / `campaign` /
     * `af_sub1..5` / `af_c_id` / `deep_link_value` available for the
     * `config.php` POST body — otherwise `LinkPilot.askChart` sends
     * mostly empty strings whenever AppsFlyer's conversion callback
     * mis-reports Organic and delivers only `af_status/af_message/
     * is_first_launch`.
     *
     * Stored as a JSON object in the plain SharedPreferences file.
     */
    var installReferrerFacts: Map<String, String>
        get() {
            val raw = flat.getString(kReferrerFacts, null) ?: return emptyMap()
            return runCatching {
                val obj = JSONObject(raw)
                val out = linkedMapOf<String, String>()
                val it = obj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = obj.optString(k, "")
                    if (v.isNotEmpty() && !v.equals("null", ignoreCase = true)) out[k] = v
                }
                out
            }.getOrDefault(emptyMap())
        }
        set(v) {
            if (v.isEmpty()) return
            val obj = JSONObject()
            for ((k, vv) in v) obj.put(k, vv)
            flat.edit().putString(kReferrerFacts, obj.toString()).apply()
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
        // commit() so WebCanvas.onResume immediately after Skip cannot
        // re-read a still-elapsed timestamp (apply() is async).
        flat.edit().putLong(kOptInSnooze, nowSeconds() + seconds).commit()
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
     * Activity-aware form. Mirrors SkyLadder `shouldOfferNudge`:
     *   • already granted (internal OR OS) → never re-offer
     *   • explicit Accept → Don't allow (our hard-block flag) → never
     *   • Skip stamps [kOptInSnooze]; once wall-clock passes that
     *     timestamp the promo shows again on the next launch / resume
     *
     * We do NOT auto-latch [optInHardBlocked] from the OS "denied +
     * no rationale" reading here. On ColorOS/Realme that triple is
     * indistinguishable from "never asked", and writing the flag
     * would permanently kill the Skip → 3-day re-offer. The hard-block
     * flag is only set from [AlertOptInActivity] after a real OS
     * dialog returns denied.
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
