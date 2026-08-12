package com.blazingjoker.blazingjokergame.link.store

import android.content.Context
import android.content.SharedPreferences
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
    private val kPendingUrl = "${LinkConfig.KEY_PREFIX}pending"

    // ── Last course ────────────────────────────────────────────────
    var course: LastCourse
        get() = LastCourse.parse(flat.getString(kCourse, null))
        set(value) {
            flat.edit().putString(kCourse, value.wire).apply()
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
     * Never re-ask after a hard OS denial (Android 13+ suppresses the
     * dialog permanently after one "no"). Never re-ask after a grant.
     * Otherwise gated on the snooze timestamp.
     */
    val shouldInvitePermission: Boolean
        get() {
            if (optInGranted) return false
            if (optInHardBlocked) return false
            val until = flat.getLong(kOptInSnooze, 0L)
            return nowSeconds() >= until
        }

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
}
