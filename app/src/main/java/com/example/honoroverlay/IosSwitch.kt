package com.example.honoroverlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import android.widget.Checkable
import androidx.core.graphics.ColorUtils
import kotlin.math.abs

class IosSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), Checkable {

    private var isCheckedState: Boolean = false
    private var progress: Float = 0f
    private var animator: ValueAnimator? = null

    private var downX: Float = 0f
    private var downY: Float = 0f
    private var isDragging: Boolean = false
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.TRANSPARENT
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val thumbStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(18, 0, 0, 0)
    }

    private val trackRect = RectF()
    private val thumbRect = RectF()
    private val shadowRect = RectF()

    private var customOnColor: Int? = null
    private var customOffColor: Int? = null

    var onCheckedChangeListener: ((IosSwitch, Boolean) -> Unit)? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        isFocusable = true

        if (attrs != null) {
            val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.checked))
            try {
                val initialChecked = a.getBoolean(0, false)
                isCheckedState = initialChecked
                progress = if (initialChecked) 1f else 0f
            } finally {
                a.recycle()
            }
        }
    }

    private val isDarkTheme: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val defaultOnColor: Int
        get() = if (isDarkTheme) Color.parseColor("#30D158") else Color.parseColor("#34C759")

    private val defaultOffColor: Int
        get() = if (isDarkTheme) Color.parseColor("#39393D") else Color.parseColor("#C5C5C7")

    fun setOnColor(color: Int) {
        customOnColor = color
        invalidate()
    }

    fun setOffColor(color: Int) {
        customOffColor = color
        invalidate()
    }

    override fun isChecked(): Boolean = isCheckedState

    override fun setChecked(checked: Boolean) {
        setChecked(checked, isAttachedToWindow && isLaidOut)
    }

    fun setChecked(newChecked: Boolean, animate: Boolean) {
        if (isCheckedState != newChecked) {
            isCheckedState = newChecked
            onCheckedChangeListener?.invoke(this, isCheckedState)
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
        }
        val targetProgress = if (isCheckedState) 1f else 0f
        if (animate && isAttachedToWindow) {
            startAnimation(targetProgress)
        } else {
            animator?.cancel()
            progress = targetProgress
            invalidate()
        }
    }

    override fun toggle() {
        setChecked(!isCheckedState, true)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    override fun performClick(): Boolean {
        toggle()
        val handled = super.performClick()
        if (!handled) {
            playSoundEffect(SoundEffectConstants.CLICK)
        }
        return handled
    }

    private fun startAnimation(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 200L
            interpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
            addUpdateListener { va ->
                progress = va.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val defaultWidth = (64f * density).toInt() + paddingLeft + paddingRight
        val defaultHeight = (30f * density).toInt() + paddingTop + paddingBottom

        val width = resolveSize(defaultWidth, widthMeasureSpec)
        val height = resolveSize(defaultHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density

        val availWidth = (width - paddingLeft - paddingRight).toFloat()
        val availHeight = (height - paddingTop - paddingBottom).toFloat()
        if (availWidth <= 0f || availHeight <= 0f) return

        trackRect.set(
            paddingLeft.toFloat(),
            paddingTop.toFloat(),
            (width - paddingRight).toFloat(),
            (height - paddingBottom).toFloat()
        )
        val trackRadius = trackRect.height() / 2f

        val effectiveOnColor = customOnColor ?: defaultOnColor
        val effectiveOffColor = customOffColor ?: defaultOffColor
        trackPaint.color = ColorUtils.blendARGB(effectiveOffColor, effectiveOnColor, progress)
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint)

        val pad = trackRect.height() * (2.2f / 30f)
        val thumbHeight = trackRect.height() - 2f * pad
        val thumbWidth = thumbHeight * 1.56f
        val thumbRadius = thumbHeight / 2f

        val minThumbLeft = trackRect.left + pad
        val maxThumbLeft = trackRect.right - pad - thumbWidth
        val currentThumbLeft = minThumbLeft + (maxThumbLeft - minThumbLeft) * progress

        thumbRect.set(
            currentThumbLeft,
            trackRect.top + pad,
            currentThumbLeft + thumbWidth,
            trackRect.bottom - pad
        )

        val shadowOffsetY = 1.5f * density
        val shadowBlur = 2.5f * density
        shadowPaint.setShadowLayer(shadowBlur, 0f, shadowOffsetY, Color.argb(45, 0, 0, 0))
        shadowRect.set(
            thumbRect.left,
            thumbRect.top + shadowOffsetY * 0.5f,
            thumbRect.right,
            thumbRect.bottom + shadowOffsetY * 0.5f
        )
        canvas.drawRoundRect(shadowRect, thumbRadius, thumbRadius, shadowPaint)

        canvas.drawRoundRect(thumbRect, thumbRadius, thumbRadius, thumbPaint)

        thumbStrokePaint.strokeWidth = 0.5f * density
        canvas.drawRoundRect(thumbRect, thumbRadius, thumbRadius, thumbStrokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        val density = resources.displayMetrics.density
        val pad = trackRect.height() * (2.2f / 30f)
        val thumbHeight = trackRect.height() - 2f * pad
        val thumbWidth = thumbHeight * 1.56f
        val minThumbLeft = trackRect.left + pad
        val maxThumbLeft = trackRect.right - pad - thumbWidth
        val travelDistance = maxThumbLeft - minThumbLeft

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (!isDragging && dx > touchSlop && dx > dy) {
                    isDragging = true
                }
                if (isDragging && travelDistance > 0f) {
                    val rawProgress = (event.x - minThumbLeft - thumbWidth / 2f) / travelDistance
                    progress = rawProgress.coerceIn(0f, 1f)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    val newChecked = progress >= 0.5f
                    val changed = newChecked != isCheckedState
                    setChecked(newChecked, true)
                    if (changed) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        callOnClick()
                    }
                } else {
                    performClick()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    setChecked(isCheckedState, true)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun getAccessibilityClassName(): CharSequence {
        return android.widget.Switch::class.java.name
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true
        info.isChecked = isCheckedState
    }

    override fun onSaveInstanceState(): Parcelable {
        val bundle = Bundle()
        bundle.putParcelable("super_state", super.onSaveInstanceState())
        bundle.putBoolean("is_checked", isCheckedState)
        return bundle
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            val isCheckedSaved = state.getBoolean("is_checked", false)
            setChecked(isCheckedSaved, false)
            super.onRestoreInstanceState(state.getParcelable("super_state"))
        } else {
            super.onRestoreInstanceState(state)
        }
    }
}
