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

/**
 * Splash / loading screen.
 *
 * Works in BOTH portrait and landscape (the game itself is portrait-only, but the
 * loading screen supports either orientation and swaps the branded artwork to match).
 * A horizontal progress bar fills strictly left -> right and only reaches 100% in the
 * final moment right before the main menu launches. A "Loading" label animates its dots.
 */
class LoadingActivity : AppCompatActivity() {

    private lateinit var background: ImageView
    private lateinit var progressBar: HorizontalProgressBar
    private lateinit var loadingText: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var dotCount = 0
    private var launched = false

    private val dotRunnable = object : Runnable {
        override fun run() {
            dotCount = (dotCount + 1) % 4
            loadingText.text = getString(R.string.loading) + ".".repeat(dotCount)
            handler.postDelayed(this, 400L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.immersive(this)

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

        // Bottom panel: progress bar + loading label (with a translucent backdrop for legibility)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28.dp, 18.dp, 28.dp, 22.dp)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 26f.dp
                setColor(android.graphics.Color.parseColor("#88000000"))
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
        val barLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 24.dp
        )
        panel.addView(progressBar, barLp)

        root.addView(panel)
        setContentView(root)

        applyOrientationArt(resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationArt(newConfig)
    }

    private fun applyOrientationArt(config: Configuration) {
        if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            background.setImageResource(R.drawable.loading_horizontal)
        } else {
            background.setImageResource(R.drawable.loading_vertical)
        }
    }

    override fun onResume() {
        super.onResume()
        Ui.immersive(this)
        handler.post(dotRunnable)
        startLoadingSequence()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(dotRunnable)
    }

    private var sequenceStarted = false

    private fun startLoadingSequence() {
        if (sequenceStarted) return
        sequenceStarted = true

        // Phase 1: fill up to 90% while "loading" (never completes here).
        val phase1 = ValueAnimator.ofFloat(0f, 0.9f).apply {
            duration = 3200L
            interpolator = DecelerateInterpolator()
            addUpdateListener { progressBar.progress = it.animatedValue as Float }
        }
        // Phase 2: only NOW fill fully, right before launching the game.
        val phase2 = ValueAnimator.ofFloat(0.9f, 1f).apply {
            duration = 450L
            addUpdateListener { progressBar.progress = it.animatedValue as Float }
        }

        phase1.addListener(onEnd = {
            handler.postDelayed({ phase2.start() }, 250L)
        })
        phase2.addListener(onEnd = {
            handler.postDelayed({ goToMenu() }, 200L)
        })
        phase1.start()
    }

    private fun goToMenu() {
        if (launched) return
        launched = true
        startActivity(Intent(this, MainMenuActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}

/** Tiny helper so we can attach lambda listeners without importing AnimatorListenerAdapter everywhere. */
private fun ValueAnimator.addListener(onEnd: () -> Unit) {
    addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) {
            onEnd()
        }
    })
}
