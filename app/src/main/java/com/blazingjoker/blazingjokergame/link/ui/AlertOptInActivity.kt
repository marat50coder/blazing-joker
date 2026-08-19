package com.blazingjoker.blazingjokergame.link.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.blazingjoker.blazingjokergame.R
import com.blazingjoker.blazingjokergame.Ui
import com.blazingjoker.blazingjokergame.dp
import com.blazingjoker.blazingjokergame.link.LinkPilot
import com.blazingjoker.blazingjokergame.link.config.LinkConfig

/**
 * Push opt-in promo. Shown once before the WebCanvas on FRESH gray-flow
 * routings, then snoozed for [LinkConfig.OPT_IN_SNOOZE_SECONDS] on Skip
 * or on OS denial.
 *
 * Landscape composition uses 30% side insets (so buttons take ~40% of
 * screen width) and 25% smaller height/text so the pair of buttons fits
 * comfortably against the horizontal artwork.
 */
class AlertOptInActivity : AppCompatActivity() {

    private lateinit var bg: ImageView
    private lateinit var controls: LinearLayout
    private lateinit var acceptBtn: TextView
    private lateinit var skipBtn: TextView

    private val permissionAsk = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pilot = LinkPilot.of(this)
        pilot.stowage.markOptInGranted(granted)
        if (!granted) {
            // Android 13+ auto-hard-denies POST_NOTIFICATIONS after the
            // first "Don't allow" — the second launch calls
            // `requestPermissionLauncher.launch()` and the system fires the
            // callback IMMEDIATELY with `granted=false` without ever
            // showing UI. If we treat that identically to a fresh skip we
            // just snooze another 3 days and the notif screen keeps
            // reappearing on every launch cycle forever (pitfall #17).
            //
            // `shouldShowRequestPermissionRationale` returning false AFTER
            // a denial callback is the OS-standard signal for "user has
            // permanently refused; do not ask again". Latch a hard block
            // so `shouldInvitePermission` returns false from now on.
            val hardBlocked =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
            if (hardBlocked) {
                pilot.stowage.markOptInHardBlocked()
            } else {
                pilot.stowage.snoozeOptIn(LinkConfig.OPT_IN_SNOOZE_SECONDS)
            }
        }
        forward()
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
                intArrayOf(0x00000000, 0x99000000.toInt()),
            )
        }
        root.addView(scrim)

        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        acceptBtn = filledButton(
            label = getString(R.string.alert_optin_accept),
            fillColors = intArrayOf(
                Color.parseColor("#FFF97316"),
                Color.parseColor("#FFDC2626"),
            ),
            strokeColor = Color.parseColor("#66FFFFFF"),
            textColor = Color.WHITE,
        ) { onAccept() }
        controls.addView(acceptBtn)

        skipBtn = filledButton(
            label = getString(R.string.alert_optin_skip),
            fillColors = intArrayOf(
                Color.parseColor("#331A0B24"),
                Color.parseColor("#88000000"),
            ),
            strokeColor = Color.parseColor("#66FFFFFF"),
            textColor = Color.WHITE,
        ) { onSkip() }
        controls.addView(skipBtn)

        root.addView(controls)
        setContentView(root)
        applyOrientation(resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
    }

    /**
     * Re-lays the two pill buttons and swaps the background art. Portrait
     * keeps the original wide pills; landscape uses 30% side insets and a
     * 25% smaller height/text so the buttons don't overpower the art.
     */
    private fun applyOrientation(config: Configuration) {
        val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        bg.setImageResource(
            if (isLandscape) R.drawable.opt_in_horizontal else R.drawable.opt_in_vertical
        )

        val screenW = resources.displayMetrics.widthPixels
        val sideInsetPx = if (isLandscape) (screenW * 0.30f).toInt() else 32.dp
        val bottomInsetPx = if (isLandscape) 24.dp else 44.dp

        val panelLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            leftMargin = sideInsetPx
            rightMargin = sideInsetPx
            bottomMargin = bottomInsetPx
        }
        controls.layoutParams = panelLp

        val acceptH = if (isLandscape) 42.dp else 56.dp
        val skipH = if (isLandscape) 36.dp else 48.dp
        val gap = if (isLandscape) 10.dp else 14.dp
        val cornerR = if (isLandscape) 16.5f.dp else 22f.dp
        val textPx = if (isLandscape) 12.75f else 17f

        acceptBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, acceptH
        ).apply { bottomMargin = gap }
        acceptBtn.textSize = textPx
        (acceptBtn.background as? GradientDrawable)?.cornerRadius = cornerR

        skipBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, skipH
        )
        skipBtn.textSize = textPx
        (skipBtn.background as? GradientDrawable)?.cornerRadius = cornerR
    }

    private fun onAccept() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val already = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (already) {
                LinkPilot.of(this).stowage.markOptInGranted(true)
                forward()
            } else {
                // Latch "we did ask" BEFORE firing — without this the OS
                // "permanent refusal" state is indistinguishable from
                // "never asked" and `shouldShowRequestPermissionRationale`
                // would let the screen come back forever after one hard
                // deny.
                LinkPilot.of(this).stowage.wasNotificationAsked = true
                permissionAsk.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            LinkPilot.of(this).stowage.markOptInGranted(true)
            forward()
        }
    }

    private fun onSkip() {
        LinkPilot.of(this).stowage.snoozeOptIn(LinkConfig.OPT_IN_SNOOZE_SECONDS)
        forward()
    }

    private fun forward() {
        val destination = intent.getStringExtra(EXTRA_DESTINATION_URL).orEmpty()
        if (destination.isEmpty()) {
            finish()
            return
        }
        val next = Intent(this, WebCanvasActivity::class.java).apply {
            putExtra(WebCanvasActivity.EXTRA_URL, destination)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(next)
        finish()
    }

    private fun filledButton(
        label: String,
        fillColors: IntArray,
        strokeColor: Int,
        textColor: Int,
        onClick: () -> Unit,
    ): TextView = TextView(this).apply {
        text = label
        setTextColor(textColor)
        textSize = 17f
        letterSpacing = 0.05f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 22f.dp
            colors = fillColors
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setStroke(2.dp, strokeColor)
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    override fun onResume() {
        super.onResume()
        Ui.immersive(this)
    }

    companion object {
        const val EXTRA_DESTINATION_URL = "bj_alert_dst"

        fun start(context: android.content.Context, destinationUrl: String) {
            val i = Intent(context, AlertOptInActivity::class.java).apply {
                putExtra(EXTRA_DESTINATION_URL, destinationUrl)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            context.startActivity(i)
        }
    }
}
