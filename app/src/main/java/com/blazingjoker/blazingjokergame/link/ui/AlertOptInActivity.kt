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
 * The Accept and Skip buttons are BOTH real filled pills — deliberately
 * NOT a text link with 85% opacity (see the pitfalls doc §12). Visual
 * weight comes from color/size, never from opacity.
 */
class AlertOptInActivity : AppCompatActivity() {

    private lateinit var bg: ImageView

    private val permissionAsk = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pilot = LinkPilot.of(this)
        pilot.stowage.markOptInGranted(granted)
        if (!granted) {
            pilot.stowage.snoozeOptIn(LinkConfig.OPT_IN_SNOOZE_SECONDS)
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

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = 44.dp
            lp.leftMargin = 32.dp
            lp.rightMargin = 32.dp
            layoutParams = lp
        }

        val accept = filledButton(
            label = getString(R.string.alert_optin_accept),
            fillColors = intArrayOf(
                Color.parseColor("#FFF97316"),
                Color.parseColor("#FFDC2626"),
            ),
            strokeColor = Color.parseColor("#66FFFFFF"),
            textColor = Color.WHITE,
        ) { onAccept() }
        controls.addView(
            accept,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 56.dp
            ).apply { bottomMargin = 14.dp }
        )

        val skip = filledButton(
            label = getString(R.string.alert_optin_skip),
            fillColors = intArrayOf(
                Color.parseColor("#331A0B24"),
                Color.parseColor("#88000000"),
            ),
            strokeColor = Color.parseColor("#66FFFFFF"),
            textColor = Color.WHITE,
        ) { onSkip() }
        controls.addView(
            skip,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 48.dp
            )
        )

        root.addView(controls)
        setContentView(root)
        applyOrientationBackground(resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientationBackground(newConfig)
    }

    private fun applyOrientationBackground(config: Configuration) {
        bg.setImageResource(
            if (config.orientation == Configuration.ORIENTATION_LANDSCAPE)
                R.drawable.opt_in_horizontal
            else R.drawable.opt_in_vertical
        )
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
