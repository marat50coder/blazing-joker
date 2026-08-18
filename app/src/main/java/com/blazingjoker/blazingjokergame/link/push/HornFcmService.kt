package com.blazingjoker.blazingjokergame.link.push

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.blazingjoker.blazingjokergame.LoadingActivity
import com.blazingjoker.blazingjokergame.R
import com.blazingjoker.blazingjokergame.link.config.LinkConfig
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles FCM push messages.
 *
 * Design rules (learned the hard way — silently swallowing tray entries
 * is the single most common cause of "notifications don't work"):
 *
 *   • Every message the SDK hands us that carries ANY of {title, body,
 *     url} produces a visible notification. Data-only payloads with just
 *     a URL fall back to the app name as the title so the tray entry is
 *     never empty. The URL is still stashed so a cold tap can consume
 *     it after re-launch.
 *   • Each notification gets a unique id via [counter] so a fast
 *     sequence of pushes does not clobber the previous one.
 *   • The image URL from either the notification block or the data
 *     block is fetched inline (short timeout) and rendered as a
 *     BigPicture — matches the shape partner networks send.
 *   • Cold-tap URLs travel through [EXTRA_COLD_TAP_URL]; the loading
 *     screen forwards them to the stowage before the pilot runs.
 */
class HornFcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Log.d(TAG, "onNewToken ${token.take(24)}…")
        runCatching { LiveTokenBus.publish(token) }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val note = msg.notification
        val data = msg.data

        val url = (data["url"] ?: data["link"] ?: data["deeplink"]).orEmpty()
        val title = note?.title ?: data["title"]
        val body = note?.body ?: data["body"] ?: data["message"]
        val image = note?.imageUrl?.toString() ?: data["image"] ?: data["picture"]

        Log.d(TAG, "onMessageReceived title=$title body=$body url=$url image=$image")

        if (title.isNullOrEmpty() && body.isNullOrEmpty() && url.isEmpty() && image.isNullOrEmpty()) {
            Log.w(TAG, "message with no payload — dropping")
            return
        }

        // Do NOT stash the URL here — that would rewrite `pendingUrl` for
        // every push received while the app is closed, so simply opening
        // the launcher icon a day later would silently jump into a URL the
        // user never tapped. The URL travels through the PendingIntent's
        // extras and is stashed by LoadingActivity only when the user
        // actually taps the tray entry.

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

        // NEW_TASK + CLEAR_TOP mirrors the magma-coins tray path. Notes:
        //   * CLEAR_TOP + singleTop on LoadingActivity means an existing
        //     LoadingActivity is brought forward and gets `onNewIntent`
        //     with the fresh extras — no re-creation, no lost `putExtra`.
        //   * We deliberately do NOT use CLEAR_TASK: on some OEM skins
        //     (MIUI, ColorOS) the launcher-owned base activity is left
        //     behind by CLEAR_TASK and the extras never reach our
        //     onCreate — the tap opens the launcher intent instead.
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

        runCatching {
            NotificationManagerCompat.from(ctx).notify(notifId, builder.build())
            Log.d(TAG, "posted notif id=$notifId")
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
