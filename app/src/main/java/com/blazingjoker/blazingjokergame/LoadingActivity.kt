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
        // Android 12+ splash bridge — the platform-drawn adaptive icon
        // hands off to our first-frame content without a white bezel.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Ui.immersive(this)

        // Cold-tap push: if the launching intent carries a URL, stash it
        // before the pilot runs so its first check picks it up.
        val coldUrl = intent?.getStringExtra(HornFcmService.EXTRA_COLD_TAP_URL).orEmpty()
        if (coldUrl.isNotEmpty()) {
            LinkPilot.of(this).stowage.stashPendingUrl(coldUrl)
        }

        buildScreen()
        LinkPilot.of(this).kickOffAmbient()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val coldUrl = intent.getStringExtra(HornFcmService.EXTRA_COLD_TAP_URL).orEmpty()
        if (coldUrl.isNotEmpty()) {
            LinkPilot.of(this).stowage.stashPendingUrl(coldUrl)
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
}
