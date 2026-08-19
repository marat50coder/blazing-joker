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
import com.blazingjoker.blazingjokergame.link.LinkPilot
import com.blazingjoker.blazingjokergame.link.data.LastCourse

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

    private fun onRetry() {
        // Prefer resuming the exact URL the user was on when the link
        // dropped — that's what `WebCanvasActivity.forwardToOffline`
        // stashes for us. Going through the full boot pipeline instead
        // would re-run attribution + chart POST, land on the top of the
        // configured entry URL, and drop whatever session the user had.
        // Sibling shells (foollegends OfflinePortal.tryRetry) do this
        // exact hand-off.
        val returnUrl = intent.getStringExtra(EXTRA_RETRY_URL).orEmpty()
        val stowage = LinkPilot.of(this).stowage
        val resumeUrl = returnUrl.ifEmpty { stowage.cachedDestination().orEmpty() }

        val next: Intent = if (resumeUrl.isNotEmpty() && stowage.course == LastCourse.Web) {
            Intent(this, WebCanvasActivity::class.java).apply {
                putExtra(WebCanvasActivity.EXTRA_URL, resumeUrl)
            }
        } else {
            Intent(this, LoadingActivity::class.java)
        }
        next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(next)
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
