package com.blazingjoker.blazingjokergame

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Menu-side settings surface. Kept fully programmatic (no XML layout)
 * to match the rest of the native UI, and read/writes go directly to
 * [GamePrefs] so the values are visible to Sfx / Analytics / GameView
 * on the very next tick — no restart required.
 *
 * Deliberately narrow scope: nothing on this screen alters the gray
 * flow's routing state. "Reset settings" only wipes the file backing
 * GamePrefs; the link subsystem's attribution latches and cached URLs
 * live in a separate SharedPreferences file (`StowageBox`) and are not
 * user-resettable — reinstall the app to reset those.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.immersive(this)
        Sfx.init(this)
        Analytics.settingsOpened()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#120616"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val bg = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.loading_vertical)
            alpha = 0.35f
        }
        root.addView(bg)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22.dp, 42.dp, 22.dp, 24.dp)
        }

        column.addView(header())
        column.addView(sectionCard(mutableListOf<android.view.View>().apply {
            add(toggleRow(
                title = getString(R.string.settings_sound),
                hint = null,
                initial = GamePrefs.sfxEnabled,
                onChange = {
                    GamePrefs.sfxEnabled = it
                    Sfx.enabled = it
                    if (it) Sfx.play(Sfx.CLICK)
                }
            ))
            add(divider())
            add(toggleRow(
                title = getString(R.string.settings_analytics),
                hint = getString(R.string.settings_analytics_hint),
                initial = GamePrefs.analyticsEnabled,
                onChange = {
                    GamePrefs.analyticsEnabled = it
                    Analytics.applyCollectionPreference(this@SettingsActivity, it)
                }
            ))
        }))

        column.addView(spacer(14.dp))
        column.addView(sectionCard(mutableListOf<android.view.View>().apply {
            add(resetRow())
        }))

        column.addView(spacer(24.dp))
        column.addView(backButton())
        column.addView(spacer(18.dp))
        column.addView(versionLabel())

        scroll.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        )
        root.addView(scroll)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        Ui.immersive(this)
    }

    // ── View builders ──────────────────────────────────────────────

    private fun header(): TextView = TextView(this).apply {
        text = getString(R.string.settings)
        setTextColor(Color.parseColor("#FFFFE08A"))
        textSize = 28f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.10f
        setPadding(6.dp, 4.dp, 0, 22.dp)
        gravity = Gravity.START
    }

    private fun sectionCard(children: List<android.view.View>): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f.dp
                setColor(Color.parseColor("#CC1F0B33"))
                setStroke(2.dp, Color.parseColor("#66F6C13A"))
            }
            setPadding(16.dp, 6.dp, 16.dp, 6.dp)
        }
        for (v in children) card.addView(v)
        return card
    }

    private fun toggleRow(
        title: String,
        hint: String?,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4.dp, 14.dp, 4.dp, 14.dp)
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#FFFFE08A"))
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        if (!hint.isNullOrEmpty()) {
            textCol.addView(TextView(this).apply {
                text = hint
                setTextColor(Color.parseColor("#B9A6DD"))
                textSize = 12f
                setPadding(0, 4.dp, 12.dp, 0)
            })
        }
        row.addView(textCol)

        val sw = Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(sw)
        return row
    }

    private fun resetRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dp, 12.dp, 4.dp, 12.dp)
        }
        row.addView(TextView(this).apply {
            text = getString(R.string.settings_reset)
            setTextColor(Color.parseColor("#FFFFE08A"))
            textSize = 17f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        row.addView(TextView(this).apply {
            text = getString(R.string.settings_reset_hint)
            setTextColor(Color.parseColor("#B9A6DD"))
            textSize = 12f
            setPadding(0, 4.dp, 0, 10.dp)
        })
        row.addView(TextView(this).apply {
            text = getString(R.string.settings_reset).uppercase()
            setTextColor(Color.parseColor("#FFFFE08A"))
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(18.dp, 12.dp, 18.dp, 12.dp)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22f.dp
                setColor(Color.parseColor("#66401515"))
                setStroke(2.dp, Color.parseColor("#88E5462F"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Sfx.play(Sfx.CLICK)
                GamePrefs.resetAll(this@SettingsActivity)
                Sfx.enabled = GamePrefs.sfxEnabled
                Analytics.applyCollectionPreference(this@SettingsActivity, GamePrefs.analyticsEnabled)
                Toast.makeText(
                    this@SettingsActivity,
                    getString(R.string.settings_reset_done),
                    Toast.LENGTH_SHORT,
                ).show()
                recreate()
            }
        })
        return row
    }

    private fun backButton(): TextView = TextView(this).apply {
        text = getString(R.string.settings_back)
        setTextColor(Color.parseColor("#2A0E3F"))
        textSize = 20f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        letterSpacing = 0.10f
        setPadding(0, 14.dp, 0, 14.dp)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 30f.dp
            colors = intArrayOf(
                Color.parseColor("#FFFFE08A"),
                Color.parseColor("#FFF6C13A"),
                Color.parseColor("#FFFF7A18"),
            )
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            setStroke(3.dp, Color.parseColor("#FF7A4A00"))
        }
        isClickable = true
        isFocusable = true
        setOnClickListener {
            Sfx.play(Sfx.CLICK)
            finish()
        }
    }

    private fun versionLabel(): TextView {
        val name = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull().orEmpty()
        val code = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            info.longVersionCode.toInt()
        }.getOrDefault(0)
        return TextView(this).apply {
            text = getString(R.string.settings_version, name, code)
            setTextColor(Color.parseColor("#88B9A6DD"))
            textSize = 12f
            gravity = Gravity.CENTER
        }
    }

    private fun divider(): android.view.View = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            1.dp,
        )
        setBackgroundColor(Color.parseColor("#33F6C13A"))
    }

    private fun spacer(height: Int): android.view.View = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            height,
        )
    }
}
