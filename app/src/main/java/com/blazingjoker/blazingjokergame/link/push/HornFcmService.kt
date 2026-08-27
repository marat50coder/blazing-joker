package com.blazingjoker.blazingjokergame.link.push

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.blazingjoker.blazingjokergame.LoadingActivity
import com.blazingjoker.blazingjokergame.R
import com.blazingjoker.blazingjokergame.link.config.LinkConfig
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * FCM receiver — same shape as SkyLadder's NudgeChannel:
 *   • Walk every common URL key (and one nested container) so partner
 *     payloads that do not use `data.url` still route.
 *   • Every message with any of {title, body, url, image} becomes a
 *     visible heads-up notification. Data-only pushes are not dropped.
 *   • Foreground messages are rendered locally (Play Services will not
 *     draw a tray entry while the app is on screen).
 *   • Background `notification` payloads are drawn by Play Services
 *     using [LinkConfig.HORN_CHANNEL_KEY]; we still handle data-only
 *     here when the process is alive.
 */
class HornFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "onNewToken ${token.take(24)}…")
        runCatching { LiveTokenBus.publish(token) }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        super.onMessageReceived(msg)
        val note = msg.notification
        val data = msg.data

        val url = pluckUrl(data).orEmpty()
        val title = note?.title ?: data["title"]
        val body = note?.body ?: data["body"] ?: data["message"]
        val image = note?.imageUrl?.toString()
            ?: data["image"]
            ?: data["picture"]
            ?: data["image_url"]

        Log.d(
            TAG,
            "onMessageReceived title=$title body=$body url=$url image=$image " +
                "dataKeys=${data.keys.joinToString()} hasNotif=${note != null}"
        )

        if (title.isNullOrEmpty() && body.isNullOrEmpty() && url.isEmpty() && image.isNullOrEmpty()) {
            Log.w(TAG, "message with no payload — dropping")
            return
        }

        renderLocalNotification(this, title, body, url, image)
    }

    private fun renderLocalNotification(
        ctx: Context,
        title: String?,
        body: String?,
        deepUrl: String,
        imageUrl: String?,
    ) {
        HornService.ensureChannel(ctx)

        if (!canPostNotifications(ctx)) {
            Log.w(
                TAG,
                "cannot post: POST_NOTIFICATIONS not granted / notifications disabled"
            )
            return
        }

        val launch = Intent(ctx, LoadingActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
            if (deepUrl.isNotEmpty()) putExtra(EXTRA_COLD_TAP_URL, deepUrl)
        }
        val notifId = counter.incrementAndGet()
        val pendingFlags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        val pending = PendingIntent.getActivity(ctx, notifId, launch, pendingFlags)

        val safeTitle = title?.takeIf { it.isNotEmpty() } ?: ctx.getString(R.string.app_name)
        val safeBody = body.orEmpty()

        val builder = NotificationCompat.Builder(ctx, LinkConfig.HORN_CHANNEL_KEY)
            .setSmallIcon(R.drawable.ic_link_flame)
            .setContentTitle(safeTitle)
            .setContentText(safeBody)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_PROMO)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pending)
            .setColor(ctx.getColor(R.color.link_horn_accent))

        if (safeBody.isNotEmpty()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(safeBody))
        }

        val art = imageUrl?.takeIf { it.isNotEmpty() }?.let { fetchArt(it) }
        if (art != null) {
            builder.setLargeIcon(art)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(art)
                    .bigLargeIcon(null as Bitmap?),
            )
        }

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm == null) {
            Log.w(TAG, "NotificationManager missing")
            return
        }
        runCatching {
            nm.notify(notifId, builder.build())
            Log.d(TAG, "posted notif id=$notifId channel=${LinkConfig.HORN_CHANNEL_KEY}")
        }.onFailure { Log.w(TAG, "notify failed: ${it.message}") }
    }

    private fun fetchArt(url: String): Bitmap? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                requestMethod = "GET"
                instanceFollowRedirects = true
            }
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "fetchArt failed: ${t.message}")
            null
        }
    }

    companion object {
        const val EXTRA_COLD_TAP_URL = "bj_link_cold_url"
        private const val TAG = "HornFcm"
        private val counter = AtomicInteger(7100)

        // SkyLadder NudgeChannel._urlKeys + nested containers.
        private val URL_KEYS = arrayOf(
            "url", "deep_link", "deeplink", "target", "link", "landing",
            "target_url", "web_url",
        )
        private val NESTED = arrayOf("payload", "data", "aps")

        fun pluckUrl(data: Map<String, String>): String? {
            for (key in URL_KEYS) {
                val v = data[key]?.trim().orEmpty()
                if (v.isNotEmpty()) return v
            }
            for (container in NESTED) {
                val raw = data[container] ?: continue
                val nested = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                for (key in URL_KEYS) {
                    val v = nested.optString(key).trim()
                    if (v.isNotEmpty()) return v
                }
            }
            return null
        }

        fun canPostNotifications(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    ctx, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return false
            }
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return false
            return nm.areNotificationsEnabled()
        }
    }
}

/**
 * Tiny in-process bus so a live pilot can hear about token rotations
 * without either side importing the other. No weak refs by design — the
 * pilot lifetime is the process lifetime.
 */
internal object LiveTokenBus {
    private val listeners = mutableSetOf<(String) -> Unit>()
    private val lock = Any()

    fun subscribe(cb: (String) -> Unit) {
        synchronized(lock) { listeners.add(cb) }
    }

    fun unsubscribe(cb: (String) -> Unit) {
        synchronized(lock) { listeners.remove(cb) }
    }

    fun publish(token: String) {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { runCatching { it(token) } }
    }
}
