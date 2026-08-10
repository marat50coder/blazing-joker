package com.blazingjoker.blazingjokergame

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * A horizontal progress bar that fills strictly from left to right.
 * Styled to match the Blazing Joker circus theme (gold gradient on a dark track).
 */
class HorizontalProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Progress in the range 0f..1f. */
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC1A0A24")
    }
    private val trackBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FFF6C13A")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFE08A")
    }

    private val trackRect = RectF()
    private val fillRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val w = width.toFloat()
        if (w <= 0f || h <= 0f) return

        val border = h * 0.10f
        trackBorderPaint.strokeWidth = border
        val radius = h / 2f

        // Track
        trackRect.set(border, border, w - border, h - border)
        val trackRadius = trackRect.height() / 2f
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint)

        // Fill (left -> right)
        val fillWidth = (trackRect.width()) * progress
        if (fillWidth > 0f) {
            val right = trackRect.left + fillWidth.coerceAtLeast(trackRect.height())
            fillRect.set(trackRect.left, trackRect.top, right.coerceAtMost(trackRect.right), trackRect.bottom)
            fillPaint.shader = LinearGradient(
                fillRect.left, 0f, fillRect.right, 0f,
                intArrayOf(
                    Color.parseColor("#FFFF7A18"),
                    Color.parseColor("#FFF6C13A"),
                    Color.parseColor("#FFFFE08A")
                ),
                null,
                Shader.TileMode.CLAMP
            )
            val fr = fillRect.height() / 2f
            canvas.drawRoundRect(fillRect, fr, fr, fillPaint)

            // Leading-edge glow highlight
            glowPaint.alpha = 200
            canvas.drawCircle(fillRect.right - fr, fillRect.centerY(), fr * 0.55f, glowPaint)
        }

        // Border on top
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackBorderPaint)
    }
}
