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

/**
 * Firebase Messaging wrapper.
 *
 * `boot()` initialises Firebase (idempotent — safe to call from multiple
 * places), guarantees the notification channel exists, and fetches the
 * current FCM token. All of that is wrapped in try/catch: a device without
 * Google Play services (a rooted emulator, some Huawei-lineage handsets)
 * must not crash the boot pipeline — we just carry on without push.
 */
internal class HornService(private val app: Context) {

    @Volatile
    var token: String? = null
        private set

    @Volatile
    var onTokenRotated: ((String) -> Unit)? = null

    @Volatile
    private var ready = false

    suspend fun boot() {
        if (ready) return
        try {
            if (FirebaseApp.getApps(app).isEmpty()) FirebaseApp.initializeApp(app)
            ensureChannel(app)
            val fcm = FirebaseMessaging.getInstance()
            token = runCatching { fcm.token.await() }.getOrNull()
            ready = true
        } catch (_: Throwable) {
            ready = false
        }
    }

    /** Re-fetch the token (useful after HornFcmService reports a rotation). */
    fun ingestNewToken(newToken: String) {
        token = newToken
        onTokenRotated?.invoke(newToken)
    }

    companion object {
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            val existing = nm.getNotificationChannel(LinkConfig.HORN_CHANNEL_KEY)
            if (existing != null) return
            val channel = NotificationChannel(
                LinkConfig.HORN_CHANNEL_KEY,
                context.getString(R.string.link_horn_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.link_horn_channel_desc)
                enableLights(true)
                enableVibration(true)
            }
            nm.createNotificationChannel(channel)
        }
    }
}
