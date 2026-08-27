package com.blazingjoker.blazingjokergame.link.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.blazingjoker.blazingjokergame.R
import com.blazingjoker.blazingjokergame.link.config.LinkConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Firebase Messaging wrapper.
 *
 * `boot()` initialises Firebase (idempotent — safe to call from multiple
 * places), guarantees the notification channel exists, and fetches the
 * current FCM token. All of that is wrapped in try/catch: a device without
 * Google Play services (a rooted emulator, some Huawei-lineage handsets)
 * must not crash the boot pipeline — we just carry on without push.
 *
 * A boot that ran while the device was offline must NOT latch `ready`:
 * `token.await()` fails without a network, and a later online retry
 * would otherwise skip the fetch — backend never receives `push_token`
 * and notifications silently do not arrive.
 */
internal class HornService(private val app: Context) {

    @Volatile
    var token: String? = null
        private set

    @Volatile
    var onTokenRotated: ((String) -> Unit)? = null

    @Volatile
    private var ready = false

    @Volatile
    private var missedToken = false

    @Volatile
    private var busWired = false

    suspend fun boot() {
        if (ready && !token.isNullOrEmpty()) return
        try {
            if (FirebaseApp.getApps(app).isEmpty()) FirebaseApp.initializeApp(app)
            ensureChannel(app)
            wireTokenBus()
            if (token.isNullOrEmpty()) {
                runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = true }
                token = withTimeoutOrNull(TOKEN_WAIT_MS) {
                    runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
                }
                if (token.isNullOrEmpty()) missedToken = true
            }
            val now = token
            if (!now.isNullOrEmpty()) {
                val late = missedToken
                ready = true
                missedToken = false
                if (late) onTokenRotated?.invoke(now)
            }
        } catch (_: Throwable) {
            ready = false
        }
    }

    private fun wireTokenBus() {
        if (busWired) return
        busWired = true
        LiveTokenBus.subscribe { ingestNewToken(it) }
    }

    /** Re-fetch the token (useful after HornFcmService reports a rotation). */
    fun ingestNewToken(newToken: String) {
        if (newToken.isEmpty()) return
        token = newToken
        ready = true
        onTokenRotated?.invoke(newToken)
    }

    companion object {
        private const val TOKEN_WAIT_MS = 3_500L

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val existing = nm.getNotificationChannel(LinkConfig.HORN_CHANNEL_KEY)
            if (existing != null) {
                // Recreate if a previous build left the channel at a
                // silenced importance — ColorOS/Realme persist this and
                // then drop every FCM tray entry.
                if (existing.importance >= NotificationManager.IMPORTANCE_DEFAULT) return
                runCatching { nm.deleteNotificationChannel(LinkConfig.HORN_CHANNEL_KEY) }
            }
            val channel = NotificationChannel(
                LinkConfig.HORN_CHANNEL_KEY,
                context.getString(R.string.link_horn_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.link_horn_channel_desc)
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setSound(
                    android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
            nm.createNotificationChannel(channel)
        }
    }
}
