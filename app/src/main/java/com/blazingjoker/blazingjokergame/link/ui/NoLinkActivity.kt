package com.blazingjoker.blazingjokergame.link.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.blazingjoker.blazingjokergame.LoadingActivity
import com.blazingjoker.blazingjokergame.R
import com.blazingjoker.blazingjokergame.Ui
import com.blazingjoker.blazingjokergame.dp
import com.blazingjoker.blazingjokergame.link.LinkPilot
import com.blazingjoker.blazingjokergame.link.data.LastCourse
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * No-connection screen for the gray flow. Retry rebuilds the full boot
 * pipeline by relaunching [LoadingActivity] — do NOT rebuild the WebView
 * directly, because a stale route memory could still trip a chart-fetch
 * failure on the very next request.
 *
 * Landscape composition matches the opt-in screen: 30% side insets and
 * 20% smaller Retry button so the CTA does not overpower the horizontal
 * artwork.
 */
class NoLinkActivity : AppCompatActivity() {

    private lateinit var bg: ImageView
    private lateinit var slot: LinearLayout
    private lateinit var retry: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val autoRetryFired = AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val probeTicker = object : Runnable {
        override fun run() {
            armProbe()
            uiHandler.postDelayed(this, PROBE_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.immersive(this)

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.parseColor("#120616"))
        }

        bg = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        root.addView(bg)

        val scrim = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0x77000000.toInt()),
            )
        }
        root.addView(scrim)

        retry = TextView(this).apply {
            text = getString(R.string.no_link_retry)
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.05f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22f.dp
                colors = intArrayOf(
                    Color.parseColor("#FF16A34A"),
                    Color.parseColor("#FF15803D"),
                )
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setStroke(2.dp, Color.parseColor("#66FFFFFF"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onRetry() }
        }

        slot = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(retry, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(slot)

        setContentView(root)
        applyOrientation(resources.configuration)

        // Never exit the app from the offline screen — the user's
        // contract is "back must not close the game". Send the task to
        // the background instead; the shell resumes intact next time
        // the launcher icon is tapped.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })
    }

    @Deprecated(
        "AndroidX prefers OnBackPressedCallback, but we keep this override " +
            "as a hard fallback for the OEM back-dispatcher races that skip " +
            "the OnBackInvokedDispatcher on some ROMs."
    )
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
    }

    private fun applyOrientation(config: Configuration) {
        val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        bg.setImageResource(
            if (isLandscape) R.drawable.no_link_horizontal else R.drawable.no_link_vertical
        )

        val screenW = resources.displayMetrics.widthPixels
        val sideInsetPx = if (isLandscape) (screenW * 0.30f).toInt() else 40.dp
        val bottomInsetPx = if (isLandscape) 24.dp else 44.dp
        val slotHeightPx = if (isLandscape) 45.dp else 56.dp
        val cornerR = if (isLandscape) 17.6f.dp else 22f.dp
        val textPx = if (isLandscape) 13.6f else 17f

        slot.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, slotHeightPx
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = bottomInsetPx
            leftMargin = sideInsetPx
            rightMargin = sideInsetPx
        }
        retry.textSize = textPx
        (retry.background as? GradientDrawable)?.cornerRadius = cornerR
    }

    private var retryBusy = false

    private fun onRetry() {
        if (retryBusy || isFinishing) return
        retryBusy = true
        retry.isEnabled = false
        retry.alpha = 0.55f

        // Probe off the UI thread so a DNS check cannot freeze the
        // button. If there is no usable internet, stay on this screen —
        // leaving would flash Loading/WebView and "release" the user
        // from NoWifi while they are still offline.
        Thread {
            val online = runCatching {
                LinkPilot.of(this).auditor.hasValidatedInternet()
            }.getOrDefault(false)
            uiHandler.post {
                if (isFinishing) return@post
                if (!online) {
                    Log.d(TAG, "retry: still offline — stay on NoLink")
                    retryBusy = false
                    retry.isEnabled = true
                    retry.alpha = 1f
                    return@post
                }
                leaveNoLink()
            }
        }.start()
    }

    private fun leaveNoLink() {
        val returnUrl = intent.getStringExtra(EXTRA_RETRY_URL).orEmpty()
        val stowage = LinkPilot.of(this).stowage
        val resumeUrl = returnUrl.ifEmpty { stowage.cachedDestination().orEmpty() }
        val inviteWanted = stowage.shouldInvitePermission(this)

        val next: Intent = when {
            resumeUrl.isNotEmpty() && inviteWanted -> {
                Intent(this, AlertOptInActivity::class.java).apply {
                    putExtra(AlertOptInActivity.EXTRA_DESTINATION_URL, resumeUrl)
                }
            }
            resumeUrl.isNotEmpty() && stowage.course == LastCourse.Web -> {
                Intent(this, WebCanvasActivity::class.java).apply {
                    putExtra(WebCanvasActivity.EXTRA_URL, resumeUrl)
                }
            }
            else -> Intent(this, LoadingActivity::class.java)
        }
        next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(next)
        finish()
    }

    override fun onResume() {
        super.onResume()
        Ui.immersive(this)
        registerAutoRetry()
    }

    override fun onPause() {
        super.onPause()
        unregisterAutoRetry()
    }

    override fun onDestroy() {
        unregisterAutoRetry()
        uiHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * Auto-retry, SkyLadder OutageScene shape:
     *   • A live adapter appearing only ARMS a DNS probe — leftover
     *     cellular with no upstream must not bounce the user back onto
     *     the partner site's broken offline chrome.
     *   • A 4 s ticker covers captive-portal Wi-Fi that reports "up"
     *     before the upstream actually routes.
     *   • Manual Retry is unchanged.
     */
    private fun registerAutoRetry() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "adapter up → arm DNS probe")
                armProbe()
            }
        }
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onSuccess { networkCallback = cb }
        uiHandler.removeCallbacks(probeTicker)
        uiHandler.postDelayed(probeTicker, PROBE_TICK_MS)
    }

    private fun armProbe() {
        if (autoRetryFired.get() || isFinishing) return
        Thread {
            val ok = runCatching {
                runBlocking {
                    val auditor = LinkPilot.of(this@NoLinkActivity).auditor
                    auditor.hasCarrier() && auditor.canRouteOut()
                }
            }.getOrDefault(false)
            if (!ok) return@Thread
            uiHandler.post {
                if (isFinishing) return@post
                if (!autoRetryFired.compareAndSet(false, true)) return@post
                val now = SystemClock.elapsedRealtime()
                if (now - lastAutoRetryElapsed < MIN_RETRY_INTERVAL_MS) {
                    autoRetryFired.set(false)
                    Log.d(TAG, "DNS reachable but retry interval not elapsed")
                    return@post
                }
                Log.d(TAG, "DNS reachable → auto-retrying")
                lastAutoRetryElapsed = now
                onRetry()
            }
        }.start()
    }

    private fun unregisterAutoRetry() {
        uiHandler.removeCallbacks(probeTicker)
        val cb = networkCallback ?: return
        networkCallback = null
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching { cm?.unregisterNetworkCallback(cb) }
    }

    companion object {
        private const val TAG = "NoLinkActivity"

        /**
         * Minimum wall-clock interval between two auto-retries, across
         * the whole app. Sized to comfortably exceed the AppsFlyer
         * conversion round-trip + `ORGANIC_RESCUE_DELAY_MS` so a
         * failing SDK boot has time to actually finish its retry
         * before we re-invoke the pipeline.
         */
        private const val MIN_RETRY_INTERVAL_MS = 8_000L
        private const val PROBE_TICK_MS = 4_000L

        /**
         * Wall clock of the last auto-retry across ALL NoLink instances
         * (each retry destroys+recreates the activity, so a per-instance
         * counter would reset every loop).
         */
        @Volatile
        private var lastAutoRetryElapsed: Long = 0L

        const val EXTRA_RETRY_URL = "bj_no_link_retry_url"

        fun start(context: Context) {
            val i = Intent(context, NoLinkActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(i)
        }
    }
}
