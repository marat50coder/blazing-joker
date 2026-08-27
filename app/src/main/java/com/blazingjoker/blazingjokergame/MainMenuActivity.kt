package com.blazingjoker.blazingjokergame

import android.content.Intent
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

class MainMenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.immersive(this)
        Sfx.init(this)
        Analytics.menuOpened()

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#120616"))
        }

        val bg = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(R.drawable.loading_vertical)
        }
        root.addView(bg)

        // Gear icon in the top-right — a plain unicode glyph on a
        // circular pill so we don't need to ship a new vector asset for
        // this update.
        val gear = TextView(this).apply {
            text = "\u2699"
            setTextColor(Color.parseColor("#FFFFE08A"))
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#B32A0E3F"))
                setStroke(2.dp, Color.parseColor("#88F6C13A"))
            }
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.settings)
            setOnClickListener {
                Sfx.play(Sfx.CLICK)
                startActivity(Intent(this@MainMenuActivity, SettingsActivity::class.java))
            }
            layoutParams = FrameLayout.LayoutParams(52.dp, 52.dp).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 20.dp
                marginEnd = 20.dp
            }
        }
        root.addView(gear)

        // Bottom controls
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = 56.dp
            layoutParams = lp
        }

        val play = primaryButton(getString(R.string.play)) {
            Sfx.play(Sfx.CLICK)
            startActivity(Intent(this, GameActivity::class.java))
        }
        controls.addView(play, LinearLayout.LayoutParams(260.dp, 68.dp).apply {
            bottomMargin = 22.dp
        })

        val links = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val privacy = secondaryButton(getString(R.string.privacy_policy)) {
            Sfx.play(Sfx.CLICK)
            openWeb(getString(R.string.privacy_policy), "https://blazingjokker.com/privacy-policy.html")
        }
        val support = secondaryButton(getString(R.string.support)) {
            Sfx.play(Sfx.CLICK)
            openWeb(getString(R.string.support), "https://blazingjokker.com/support.html")
        }
        links.addView(privacy, LinearLayout.LayoutParams(0, 48.dp, 1f).apply {
            marginEnd = 8.dp
        })
        links.addView(support, LinearLayout.LayoutParams(0, 48.dp, 1f).apply {
            marginStart = 8.dp
        })
        controls.addView(links, LinearLayout.LayoutParams(280.dp, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(controls)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        Ui.immersive(this)
    }

    /**
     * The white part must not exit to the launcher on a back tap from
     * the main menu — that reads as "the whole game just closed" to a
     * user who tapped back to dismiss something (keyboard, tooltip,
     * settings). Swallow the event; the OS home button remains the
     * only way to leave the game.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Sfx.play(Sfx.CLICK)
    }

    private fun openWeb(title: String, url: String) {
        val i = Intent(this, WebActivity::class.java)
        i.putExtra(WebActivity.EXTRA_TITLE, title)
        i.putExtra(WebActivity.EXTRA_URL, url)
        startActivity(i)
    }

    private fun primaryButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#2A0E3F"))
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 34f.dp
                colors = intArrayOf(
                    Color.parseColor("#FFFFE08A"),
                    Color.parseColor("#FFF6C13A"),
                    Color.parseColor("#FFFF7A18")
                )
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                setStroke(3.dp, Color.parseColor("#FF7A4A00"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun secondaryButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#FFFFE08A"))
            textSize = 14f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(6.dp, 0, 6.dp, 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f.dp
                setColor(Color.parseColor("#B32A0E3F"))
                setStroke(2.dp, Color.parseColor("#B3F6C13A"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }
}
