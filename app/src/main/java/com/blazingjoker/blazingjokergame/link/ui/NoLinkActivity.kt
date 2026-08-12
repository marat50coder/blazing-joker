package com.blazingjoker.blazingjokergame.link.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.blazingjoker.blazingjokergame.LoadingActivity
import com.blazingjoker.blazingjokergame.R
import com.blazingjoker.blazingjokergame.Ui
import com.blazingjoker.blazingjokergame.dp

/**
 * No-connection screen for the gray flow. Retry rebuilds the full boot
 * pipeline by relaunching [LoadingActivity] — do NOT rebuild the WebView
 * directly, because a stale route memory could still trip a chart-fetch
 * failure on the very next request.
 */
class NoLinkActivity : AppCompatActivity() {

    private lateinit var bg: ImageView

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

        val retry = TextView(this).apply {
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

        val slotLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 56.dp
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = 44.dp
            leftMargin = 40.dp
            rightMargin = 40.dp
        }
        val slot = LinearLayout(this).apply {
            layoutParams = slotLp
            gravity = Gravity.CENTER
            addView(retry, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        root.addView(slot)

        setContentView(root)
        applyOrientation(resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
    }

    private fun applyOrientation(config: Configuration) {
        bg.setImageResource(
            if (config.orientation == Configuration.ORIENTATION_LANDSCAPE)
                R.drawable.no_link_horizontal
            else R.drawable.no_link_vertical
        )
    }

    private fun onRetry() {
        val boot = Intent(this, LoadingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(boot)
        finish()
    }

    override fun onResume() {
        super.onResume()
        Ui.immersive(this)
    }

    companion object {
        const val EXTRA_RETRY_URL = "bj_no_link_retry_url"

        fun start(context: Context) {
            val i = Intent(context, NoLinkActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(i)
        }
    }
}
