package com.example.tascyn.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.example.tascyn.R

/**
 * HoldToUnlockButton
 * Custom tactile press-and-hold button for unlocking the screen when Focus Mode is locked.
 * Draws a circular progress fill around the lock icon and executes onUnlock when held for 1200ms.
 */
class HoldToUnlockButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onUnlockListener: (() -> Unit)? = null

    private val holdDurationMs = 1200L
    private var holdProgress = 0f
    private var animator: ValueAnimator? = null
    private var isHolding = false

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
    }

    private val arcBounds = RectF()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isHolding = true
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                startHoldAnimation()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isHolding) {
                    isHolding = false
                    cancelHoldAnimation()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun startHoldAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = holdDurationMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                holdProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (isHolding && holdProgress >= 0.99f) {
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        isHolding = false
                        holdProgress = 0f
                        invalidate()
                        onUnlockListener?.invoke()
                    }
                }
            })
            start()
        }
    }

    private fun cancelHoldAnimation() {
        animator?.cancel()
        holdProgress = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        val accentColor = ContextCompat.getColor(context, R.color.color_accent)
        val pillBgColor = if (isDark) Color.parseColor("#1C1D24") else Color.parseColor("#E5E7EB")
        val activeFillColor = if (isDark) Color.parseColor("#312E81") else Color.parseColor("#C7D2FE")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#0E0E10")

        val radius = h / 2f
        arcBounds.set(2f, 2f, w - 2f, h - 2f)

        // Draw background pill
        bgPaint.color = pillBgColor
        canvas.drawRoundRect(arcBounds, radius, radius, bgPaint)

        // Draw animated fill from left to right as user holds
        if (holdProgress > 0f) {
            val fillBounds = RectF(2f, 2f, (w - 2f) * holdProgress, h - 2f)
            canvas.save()
            val clipPath = Path().apply {
                addRoundRect(arcBounds, radius, radius, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            bgPaint.color = activeFillColor
            canvas.drawRect(fillBounds, bgPaint)
            canvas.restore()
        }

        // Draw outer accent border outline
        trackPaint.color = if (holdProgress > 0f) accentColor else if (isDark) Color.parseColor("#383944") else Color.parseColor("#D1D5DB")
        trackPaint.strokeWidth = if (holdProgress > 0f) 4f else 2f
        canvas.drawRoundRect(arcBounds, radius, radius, trackPaint)

        // Draw Lock icon inside a circle badge on left
        val iconCx = radius + 4f
        val iconCy = h / 2f
        val circleR = radius - 10f

        bgPaint.color = if (holdProgress > 0f) accentColor else if (isDark) Color.parseColor("#2A2B34") else Color.parseColor("#D1D5DB")
        canvas.drawCircle(iconCx, iconCy, circleR, bgPaint)

        // Lock icon shackle & body
        val lockIconColor = if (holdProgress > 0f) Color.WHITE else textColor
        val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lockIconColor
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val lockBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lockIconColor
            style = Paint.Style.FILL
        }

        val shackleBounds = RectF(iconCx - 6f, iconCy - 11f, iconCx + 6f, iconCy - 1f)
        canvas.drawArc(shackleBounds, 180f, 180f, false, lockPaint)
        val bodyBounds = RectF(iconCx - 8f, iconCy - 2f, iconCx + 8f, iconCy + 9f)
        canvas.drawRoundRect(bodyBounds, 3f, 3f, lockBodyPaint)

        // Draw label text
        textPaint.color = textColor
        textPaint.textSize = h * 0.28f
        val pct = (holdProgress * 100).toInt()
        val label = if (isHolding) "RELEASE TO CANCEL • $pct%" else "HOLD TO UNLOCK & EXIT"
        val textY = (h / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(label, (w + iconCx) / 2f - 4f, textY, textPaint)
    }
}
