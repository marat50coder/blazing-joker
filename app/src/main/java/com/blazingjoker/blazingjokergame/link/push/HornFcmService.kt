package com.blazingjoker.blazingjokergame.link.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.blazingjoker.blazingjokergame.LoadingActivity
import com.blazingjoker.blazingjokergame.R
import com.blazingjoker.blazingjokergame.link.config.LinkConfig
import com.blazingjoker.blazingjokergame.link.store.StowageBox
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Handles FCM push messages.
 *
 *   • WARM message (data.url present, notification body present):
 *     Render a local notification with the flame small-icon; tap
 *     re-launches the app with a pending-URL extra so the pilot can pick
 *     it up and route into the WebView on the next boot pass.
 *
 *   • DATA-ONLY message (data.url present, notification body absent):
 *     Stash the URL directly in the stowage. On the next foregrounding
 *     the pilot consumes it.
 *
 *   • COLD-TAP path:
 *     Handled by [LoadingActivity]: it inspects its own intent extras
 *     on onCreate() and forwards to the stowage pending slot before the
 *     pilot runs.
 */
class HornFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Persist NOTHING here — the pilot will re-fetch through HornService
        // on the next foregrounding. But we do notify any live in-process
        // listeners so a running WebCanvas can attach the new token to the
        // next chart refresh without waiting for a fresh cold-launch.
        try {
            // A running process can bind this via a static hook; if nobody
            // is listening we simply lose the rotation event — the next
            // FirebaseMessaging.getToken() call will surface the new value.
            LiveTokenBus.publish(token)
        } catch (_: Throwable) {
            // Ignore — token rotation is not fatal.
        }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val url = msg.data["url"].orEmpty()
        val title = msg.notification?.title ?: msg.data["title"]
        val body = msg.notification?.body ?: msg.data["body"]

        if (title.isNullOrEmpty() && body.isNullOrEmpty() && url.isEmpty()) return

        // Data-only URL delivery — write to stowage, no visible notification.
        if ((title.isNullOrEmpty() && body.isNullOrEmpty()) && url.isNotEmpty()) {
            StowageBox(this).stashPendingUrl(url)
            return
        }

        renderLocalNotification(this, title, body, url)
    }

    private fun renderLocalNotification(
        ctx: Context,
        title: String?,
        body: String?,
        deepUrl: String,
    ) {
        HornService.ensureChannel(ctx)

        val launch = Intent(ctx, LoadingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (deepUrl.isNotEmpty()) putExtra(EXTRA_COLD_TAP_URL, deepUrl)
        }
        val pendingFlags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        val pending = PendingIntent.getActivity(ctx, 0, launch, pendingFlags)

        val note = NotificationCompat.Builder(ctx, LinkConfig.HORN_CHANNEL_KEY)
            .setSmallIcon(R.drawable.ic_link_flame)
            .setContentTitle(title ?: ctx.getString(R.string.app_name))
            .setContentText(body ?: "")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setColor(ctx.getColor(R.color.link_horn_accent))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body ?: ""))
            .build()

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(deepUrl.hashCode().and(0x7FFFFFFF), note)
    }

    companion object {
        const val EXTRA_COLD_TAP_URL = "bj_link_cold_url"
    }
}

/**
 * Tiny in-process bus so a live [HornService] can hear about token rotations
 * without either side importing the other. Weak set of listeners so nothing
 * leaks if the pilot process terminates before FCM does.
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
