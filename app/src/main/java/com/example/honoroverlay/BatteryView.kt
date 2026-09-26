package com.example.honoroverlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class BatteryView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var level: Int = 100

    private val isDarkTheme: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#30D158")
    }

    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bodyRect = RectF()
    private val fillRect = RectF()
    private val tipRect = RectF()

    fun setBatteryLevel(newLevel: Int) {
        level = newLevel.coerceIn(0, 100)
        fillPaint.color = when {
            level <= 10 -> Color.parseColor("#FF453A")
            level <= 20 -> Color.parseColor("#FF9F0A")
            else -> Color.parseColor("#30D158")
        }
        invalidate()
    }

    fun getBatteryLevel(): Int = level

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density

        val primaryColor = if (isDarkTheme) Color.WHITE else Color.BLACK
        strokePaint.color = primaryColor
        strokePaint.strokeWidth = 1f * density
        tipPaint.color = primaryColor

        val strokeW = strokePaint.strokeWidth
        val tipWidth = 1.5f * density
        val tipHeight = height * 0.36f
        val bodyCorner = 3.5f * density

        val bodyRight = width - tipWidth - 2.5f * density
        bodyRect.set(strokeW / 2, strokeW / 2, bodyRight, height - strokeW / 2)
        canvas.drawRoundRect(bodyRect, bodyCorner, bodyCorner, strokePaint)

        val tipTop = (height - tipHeight) / 2
        tipRect.set(bodyRight + 1.2f * density, tipTop, width - strokeW / 2, tipTop + tipHeight)
        canvas.drawRoundRect(tipRect, 1f * density, 1f * density, tipPaint)

        val innerPadding = 2f * density
        val maxFillWidth = (bodyRight - strokeW / 2) - (strokeW / 2 + innerPadding * 2)
        val currentFillWidth = maxFillWidth * (level / 100f)

        if (currentFillWidth > 0) {
            fillRect.set(
                strokeW / 2 + innerPadding,
                strokeW / 2 + innerPadding,
                strokeW / 2 + innerPadding + currentFillWidth,
                height - strokeW / 2 - innerPadding
            )
            canvas.drawRoundRect(fillRect, 1.8f * density, 1.8f * density, fillPaint)
        }
    }
}
