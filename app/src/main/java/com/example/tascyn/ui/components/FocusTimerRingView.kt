package com.example.tascyn.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.example.tascyn.R

/**
 * FocusTimerRingView
 * Custom high-precision circular progress timer for Tascyn Full-Screen Focus Mode.
 * Renders an anti-aliased circular track, animated progress arc, digital time readout,
 * and semantic status (Normal / Overtime / Pulsing).
 */
class FocusTimerRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var elapsedSeconds: Long = 0L
    private var targetSeconds: Long? = null
    private var isOvertime: Boolean = false

    private var animatedProgress: Float = 0f
    private var progressAnimator: ValueAnimator? = null

    // Breathing pulse for live tracking
    private var pulseAlpha: Float = 1.0f
    private val pulseAnimator = ValueAnimator.ofFloat(0.35f, 1.0f).apply {
        duration = 1400L
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            pulseAlpha = it.animatedValue as Float
            invalidate()
        }
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val overtimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val pulseDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val timerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
    }

    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.08f
    }

    private val arcBounds = RectF()

    init {
        pulseAnimator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator.cancel()
        progressAnimator?.cancel()
    }

    fun updateSessionTime(elapsed: Long, target: Long?, overtime: Boolean) {
        this.elapsedSeconds = elapsed
        this.targetSeconds = target
        this.isOvertime = overtime

        val newFraction: Float = if (target != null && target > 0L) {
            if (overtime) 1.0f else (elapsed.toFloat() / target.toFloat()).coerceIn(0f, 1f)
        } else {
            // Infinite loop sweep
            ((elapsed % 60L).toFloat() / 60f)
        }

        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofFloat(animatedProgress, newFraction).apply {
            duration = 450L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animatedProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val size = Math.min(w, h)
        val strokeW = size * 0.055f
        val radius = (size - strokeW * 2f) / 2f
        val cx = w / 2f
        val cy = h / 2f

        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        val trackColor = if (isDark) Color.parseColor("#1C1C22") else Color.parseColor("#E5E7EB")
        val accentColor = ContextCompat.getColor(context, R.color.color_accent)
        val overtimeColor = ContextCompat.getColor(context, R.color.color_urgent_red)
        val textPrimaryColor = ContextCompat.getColor(context, R.color.color_text_primary)
        val textSecondaryColor = ContextCompat.getColor(context, R.color.color_text_secondary)

        // 1. Base Circular Track
        trackPaint.strokeWidth = strokeW
        trackPaint.color = trackColor
        canvas.drawCircle(cx, cy, radius, trackPaint)

        // 2. Active Progress Arc
        if (isOvertime) {
            overtimePaint.strokeWidth = strokeW * 1.12f
            overtimePaint.color = overtimeColor
            canvas.drawArc(arcBounds, -90f, 360f, false, overtimePaint)
        } else {
            progressPaint.strokeWidth = strokeW
            progressPaint.color = accentColor
            val sweep = animatedProgress * 360f
            if (sweep > 0f) {
                canvas.drawArc(arcBounds, -90f, sweep, false, progressPaint)
            }
        }

        // 3. Status indicator dot (breathing pulse)
        pulseDotPaint.color = if (isOvertime) overtimeColor else Color.parseColor("#10B981")
        pulseDotPaint.alpha = (pulseAlpha * 255).toInt()
        val dotRadius = strokeW * 0.45f
        val dotY = cy - radius * 0.58f
        canvas.drawCircle(cx - (dotRadius * 3.5f), dotY - (dotRadius * 0.2f), dotRadius, pulseDotPaint)

        // 4. Status Subtext (e.g., "LIVE FOCUS" or "OVERTIME")
        subTextPaint.textSize = size * 0.046f
        subTextPaint.color = if (isOvertime) overtimeColor else textSecondaryColor
        val subLabel = if (isOvertime) "OVERTIME" else "LIVE FOCUS"
        canvas.drawText(subLabel, cx + (dotRadius * 1.2f), dotY + (dotRadius * 0.45f), subTextPaint)

        // 5. Massive Digital Clock Timer
        timerTextPaint.textSize = size * 0.165f
        timerTextPaint.color = textPrimaryColor

        val hours = elapsedSeconds / 3600L
        val minutes = (elapsedSeconds % 3600L) / 60L
        val seconds = elapsedSeconds % 60L
        val timeStr = String.format("%02d:%02d:%02d", hours, minutes, seconds)

        // Vertical centering
        val textBounds = Rect()
        timerTextPaint.getTextBounds(timeStr, 0, timeStr.length, textBounds)
        val timerY = cy + (textBounds.height() / 2f) + (size * 0.015f)
        canvas.drawText(timeStr, cx, timerY, timerTextPaint)

        // 6. Remaining Target / Overtime Subtext
        subTextPaint.color = if (isOvertime) overtimeColor else textSecondaryColor
        subTextPaint.textSize = size * 0.044f

        val bottomLabel = if (targetSeconds != null) {
            val rem = targetSeconds!! - elapsedSeconds
            if (rem > 0) {
                val rH = rem / 3600L
                val rM = (rem % 3600L) / 60L
                val rS = rem % 60L
                if (rH > 0) String.format("Remaining: %dh %02dm", rH, rM) else String.format("Remaining: %02d:%02d", rM, rS)
            } else {
                val oS = -rem
                val oH = oS / 3600L
                val oM = (oS % 3600L) / 60L
                val oSec = oS % 60L
                if (oH > 0) String.format("+%dh %02dm overtime", oH, oM) else String.format("+%02d:%02d overtime", oM, oSec)
            }
        } else {
            "Open Session"
        }
        val bottomLabelY = cy + radius * 0.56f
        canvas.drawText(bottomLabel, cx, bottomLabelY, subTextPaint)
    }
}
