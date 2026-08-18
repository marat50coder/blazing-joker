package com.blazingjoker.blazingjokergame

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.blazingjoker.blazingjokergame.link.LinkPilot
import com.blazingjoker.blazingjokergame.link.data.Berth
import com.blazingjoker.blazingjokergame.link.push.HornFcmService
import com.blazingjoker.blazingjokergame.link.ui.AlertOptInActivity
import com.blazingjoker.blazingjokergame.link.ui.NoLinkActivity
import com.blazingjoker.blazingjokergame.link.ui.WebCanvasActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Splash / loading screen AND boot dispatcher.
 *
 * On start we:
 *   1. Consume any cold-tap URL from the launching intent and stash it
 *      so the pilot picks it up on its first frame.
 *   2. Show the branded loading art + animated progress bar.
 *   3. Run [LinkPilot.chart] on IO. The pilot's progress callback
 *      raises the bar; the bar only ever reaches 100% right before
 *      the dispatch.
 *   4. Dispatch on the returned [Berth]:
 *        Native      -> MainMenuActivity  (the tower-defence game)
 *        Web         -> AlertOptInActivity or WebCanvasActivity, per
 *                       the stowage's `shouldInvitePermission`
 *        LostSignal  -> NoLinkActivity
 *
 * If credentials are not yet packed (fresh checkout), the pilot short-
 * circuits to Berth.Native and the user plays the game unaffected. This
 * means the game itself remains a self-contained, offline-capable app
 * even without a working backend.
 */
class LoadingActivity : AppCompatActivity() {

    private lateinit var background: ImageView
    private lateinit var progressBar: HorizontalProgressBar
    private lateinit var loadingText: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private var dotCount = 0
    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var chartJob: Job? = null

    // Progress bar has three phases: base warm-up (0 -> ~0.15), pilot-driven
    // (~0.15 -> 0.95), then final flush before dispatch (0.95 -> 1.0). This
    // keeps the bar visibly alive even when the pilot resolves quickly.
    @Volatile
    private var pilotProgress: Float = 0f
    private var barAnimator: ValueAnimator? = null

    private val dotRunnable = object : Runnable {
        override fun run() {
            dotCount = (dotCount + 1) % 4
            loadingText.text = getString(R.string.loading) + ".".repeat(dotCount)
            uiHandler.postDelayed(this, 400L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Ui.immersive(this)

        val pilot = LinkPilot.of(this)

        // CRITICAL: wire the AppsFlyer listeners up BEFORE any lifecycle
        // callback fires. The SDK uses ActivityLifecycleCallbacks to notice
        // the first onResume, and if init() runs after we're already
        // resumed, the launch event is queued to the next transition —
        // ~30 s later when the game menu opens — and the conversion
        // listener silently never fires. Registering here fixes that.
        pilot.wireUp()

        // Forward the launch intent so a OneLink VIEW-intent gets its
        // query params (media_source, campaign, deep_link_value, af_sub1…)
        // harvested up-front. The pilot merges those into the POST body
        // even when the SDK stays silent.
        pilot.notifyLaunchIntent(intent)

        val coldUrl = extractPushUrl(intent)
        if (coldUrl.isNotEmpty()) {
            Log.d(TAG, "cold-tap URL from launch intent: $coldUrl")
            pilot.stowage.stashPendingUrl(coldUrl)
        }

        buildScreen()
        pilot.kickOffAmbient()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val pilot = LinkPilot.of(this)
        pilot.notifyLaunchIntent(intent)
        val coldUrl = extractPushUrl(intent)
        if (coldUrl.isNotEmpty()) {
            Log.d(TAG, "cold-tap URL from onNewIntent: $coldUrl")
            // Push tapped while LoadingActivity is still on screen (rare —
            // usually CLEAR_TASK from the pending intent wipes the whole
            // stack first). Stash the URL and RESTART the pilot so the
            // dispatcher gets a chance to route into WebCanvas with the
            // fresh URL. Without the restart the pilot job just holds the
            // previous verdict and the tap does nothing visible.
            pilot.stowage.stashPendingUrl(coldUrl)
            chartJob?.cancel()
            chartJob = null
            pilotProgress = 0f
            launchPilot()
        }
    }

    private fun buildScreen() {
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#120616"))
        }

        background = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        root.addView(background)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28.dp, 18.dp, 28.dp, 22.dp)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 26f.dp
                setColor(Color.parseColor("#88000000"))
            }
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = 64.dp
            lp.leftMargin = 28.dp
            lp.rightMargin = 28.dp
            layoutParams = lp
        }

        loadingText = TextView(this).apply {
            text = getString(R.string.loading)
            setTextColor(Color.parseColor("#FFFFE08A"))
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(10f, 0f, 3f, Color.parseColor("#CC000000"))
            gravity = Gravity.CENTER
        }
        panel.addView(
            loadingText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dp }
        )

        progressBar = HorizontalProgressBar(this)
        panel.addView(progressBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 24.dp
        ))

        root.addView(panel)
        setContentView(root)
        applyOrientationArt(resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationArt(newConfig)
    }

    private fun applyOrientationArt(config: Configuration) {
        background.setImageResource(
            if (config.orientation == Configuration.ORIENTATION_LANDSCAPE)
                R.drawable.loading_horizontal
            else R.drawable.loading_vertical
        )
    }

    override fun onResume() {
        super.onResume()
        Ui.immersive(this)
        uiHandler.post(dotRunnable)

        // Send the AppsFlyer launch event with a REAL Activity host. Passing
        // Application context here queues the event to the next activity
        // transition and the conversion listener never fires in time.
        LinkPilot.of(this).start(this)

        launchPilot()
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(dotRunnable)
    }

    override fun onDestroy() {
        chartJob?.cancel()
        barAnimator?.cancel()
        loaderScope.cancel()
        super.onDestroy()
    }

    private fun launchPilot() {
        if (chartJob != null) return

        // Warm-up: bar drifts to 12% while the pilot is spinning up so the
        // user never sees a static empty bar even if attribution stalls.
        driveBarTo(0.12f, 900L)

        chartJob = loaderScope.launch {
            val berth = withContext(Dispatchers.IO) {
                LinkPilot.of(this@LoadingActivity).chart { fraction ->
                    pilotProgress = fraction.coerceIn(0f, 0.95f)
                    // Post to UI thread — animator is UI-only.
                    uiHandler.post { driveBarTo(pilotProgress, 380L) }
                }
            }

            // Final flush — bar to 100% right before dispatch. This is the
            // "only in the final moment" fill mandated by the design brief.
            driveBarTo(1f, 380L)
            uiHandler.postDelayed({ dispatch(berth) }, 260L)
        }
    }

    private fun driveBarTo(target: Float, durationMs: Long) {
        val start = progressBar.progress
        val clamped = target.coerceIn(0f, 1f)
        if (clamped <= start) return
        barAnimator?.cancel()
        val anim = ValueAnimator.ofFloat(start, clamped).apply {
            duration = max(120L, durationMs)
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progressBar.progress = it.animatedValue as Float
            }
        }
        barAnimator = anim
        anim.start()
    }

    /**
     * Pull the URL to open out of the launch intent.
     *
     * Two very different paths land here:
     *   1. Our own [HornFcmService] built a PendingIntent whose extras
     *      contain [HornFcmService.EXTRA_COLD_TAP_URL] — that fires only
     *      when the app was foreground OR was fully cold with `data`-only
     *      pushes (Android calls our [FirebaseMessagingService]).
     *   2. Firebase Cloud Messaging delivered a payload that carried a
     *      `notification` block AND the app was in background: the SDK
     *      draws the tray entry ITSELF, our service is bypassed, and the
     *      tap opens the launcher with the `data` KEYS injected as raw
     *      string extras. In that case the URL sits at `url`/`link`/
     *      `deeplink` on the intent, NOT under our custom key.
     *
     * Checking all four keeps every path routing to the same page.
     */
    private fun extractPushUrl(intent: Intent?): String {
        if (intent == null) return ""
        Log.d(
            TAG,
            "extractPushUrl: action=${intent.action} data=${intent.data} " +
                "extras=${intent.extras?.keySet()?.joinToString()}",
        )

        val direct = intent.getStringExtra(HornFcmService.EXTRA_COLD_TAP_URL)
        if (!direct.isNullOrEmpty()) {
            Log.d(TAG, "extractPushUrl: from EXTRA_COLD_TAP_URL → $direct")
            return direct
        }

        val extras = intent.extras
        if (extras != null) {
            for (key in FCM_URL_KEYS) {
                val v = extras.getString(key) ?: continue
                if (v.isEmpty()) continue
                if (looksLikeHttp(v)) {
                    Log.d(TAG, "extractPushUrl: from extras[$key] → $v")
                    return v
                }
                Log.d(TAG, "extractPushUrl: extras[$key]=$v ignored (not http)")
            }
        }

        // Firebase's tray tap intent sometimes attaches the deep-link URI
        // as the intent DATA (VIEW action) rather than as an extras key —
        // depends on whether the console payload had a `notification.click_action`
        // set or a `data.url` alone.
        if (intent.action == Intent.ACTION_VIEW) {
            val fromUri = intent.data?.toString().orEmpty()
            if (fromUri.isNotEmpty() && looksLikeHttp(fromUri) && !isMarketingHost(fromUri)) {
                Log.d(TAG, "extractPushUrl: from intent.data → $fromUri")
                return fromUri
            }
        }
        Log.d(TAG, "extractPushUrl: no URL found")
        return ""
    }

    private fun isMarketingHost(url: String): Boolean {
        // OneLink / AppsFlyer VIEW intents are attribution launches, NOT
        // push taps — those should go through the pilot's attribution
        // path, not the cold-tap shortcut.
        val lower = url.lowercase()
        return lower.contains(".onelink.me") || lower.contains("app.appsflyer.com")
    }

    private fun looksLikeHttp(s: String): Boolean {
        val t = s.trim().lowercase()
        return t.startsWith("http://") || t.startsWith("https://")
    }

    private fun dispatch(berth: Berth) {
        val pilot = LinkPilot.of(this)
        val next: Intent = when (berth) {
            is Berth.Native -> Intent(this, MainMenuActivity::class.java)

            is Berth.Web -> {
                if (!berth.fromColdPush && pilot.stowage.shouldInvitePermission) {
                    Intent(this, AlertOptInActivity::class.java).apply {
                        putExtra(AlertOptInActivity.EXTRA_DESTINATION_URL, berth.url)
                    }
                } else {
                    Intent(this, WebCanvasActivity::class.java).apply {
                        putExtra(WebCanvasActivity.EXTRA_URL, berth.url)
                    }
                }
            }

            is Berth.LostSignal -> Intent(this, NoLinkActivity::class.java)
        }
        next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(next)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    companion object {
        private const val TAG = "LoadingActivity"

        // FCM `notification`-block pushes bypass our FirebaseMessagingService
        // when the app is backgrounded — the data payload keys arrive as
        // raw intent extras on the launcher activity instead. These are
        // the conventional names sending tools use for the deep-link URL.
        private val FCM_URL_KEYS = arrayOf(
            "url", "link", "deeplink", "deep_link", "target_url", "web_url",
        )
    }
}
