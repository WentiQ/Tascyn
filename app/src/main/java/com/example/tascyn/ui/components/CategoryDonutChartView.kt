package com.example.tascyn.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.example.tascyn.R

data class CategoryShareData(
    val name: String,
    val minutes: Long,
    val percentage: Float,
    val colorHex: String
)

/**
 * CategoryDonutChartView
 * Custom multi-segment ring/donut chart showing category distribution.
 * Implements smooth entrance sweep, center focus label, and precision arc rendering.
 */
class CategoryDonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val shares = mutableListOf<CategoryShareData>()
    private var totalMinutes: Long = 0L
    private var sweepFraction: Float = 0f

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
    }

    private val centerSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val ovalBounds = RectF()

    fun setData(items: List<CategoryShareData>) {
        shares.clear()
        shares.addAll(items)
        totalMinutes = items.sumOf { it.minutes }

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 850L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                sweepFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val size = Math.min(w, h)
        val strokeW = size * 0.16f
        val radius = (size - strokeW) / 2f
        val cx = w / 2f
        val cy = h / 2f

        ovalBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        arcPaint.strokeWidth = strokeW

        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val textPrimary = ContextCompat.getColor(context, R.color.color_text_primary)
        val textSecondary = ContextCompat.getColor(context, R.color.color_text_secondary)
        val emptyTrackColor = if (isDark) Color.parseColor("#1C1C22") else Color.parseColor("#E5E7EB")

        if (shares.isEmpty() || totalMinutes <= 0) {
            arcPaint.color = emptyTrackColor
            canvas.drawArc(ovalBounds, 0f, 360f, false, arcPaint)

            centerTextPaint.textSize = size * 0.08f
            centerTextPaint.color = textSecondary
            canvas.drawText("No Data", cx, cy + (size * 0.025f), centerTextPaint)
            return
        }

        var startAngle = -90f
        val gapAngle = if (shares.size > 1) 2.5f else 0f

        for (item in shares) {
            val sweep = (item.percentage / 100f) * 360f * sweepFraction
            val adjustedSweep = (sweep - gapAngle).coerceAtLeast(0.5f)

            try {
                arcPaint.color = Color.parseColor(item.colorHex)
            } catch (e: Exception) {
                arcPaint.color = ContextCompat.getColor(context, R.color.color_accent)
            }

            canvas.drawArc(ovalBounds, startAngle + (gapAngle / 2f), adjustedSweep, false, arcPaint)
            startAngle += sweep
        }

        // Center Content
        val topCategory = shares.maxByOrNull { it.minutes }
        centerTextPaint.textSize = size * 0.11f
        centerTextPaint.color = textPrimary

        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        val totalFormatted = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"

        canvas.drawText(totalFormatted, cx, cy + (size * 0.015f), centerTextPaint)

        centerSubPaint.textSize = size * 0.048f
        centerSubPaint.color = textSecondary
        val subLabel = if (topCategory != null) "Top: ${topCategory.name}" else "Total Focus"
        canvas.drawText(subLabel, cx, cy + (size * 0.11f), centerSubPaint)
    }
}
