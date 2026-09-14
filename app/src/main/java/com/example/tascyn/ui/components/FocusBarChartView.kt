package com.example.tascyn.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.example.tascyn.R

data class DailyBarData(
    val dayLabel: String,
    val dateSubtitle: String,
    val minutes: Long,
    val isToday: Boolean = false
)

/**
 * FocusBarChartView
 * Custom Canvas bar chart displaying daily focus time distribution.
 * Implements smooth entry animation, target benchmark line, and interactive touch tooltips.
 */
class FocusBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bars = mutableListOf<DailyBarData>()
    private var targetMinutes: Long = 120L // 2h default target goal
    private var maxScaleMinutes: Long = 180L

    private var animationFraction: Float = 0f
    private var selectedIndex: Int = -1

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val todayBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val boldTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
    }

    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val tooltipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val barRect = RectF()
    private val tooltipRect = RectF()

    fun setData(items: List<DailyBarData>, targetDailyMinutes: Long = 120L) {
        bars.clear()
        bars.addAll(items)
        this.targetMinutes = targetDailyMinutes

        val maxVal = items.maxOfOrNull { it.minutes } ?: 0L
        maxScaleMinutes = Math.max(targetDailyMinutes + 30L, ((maxVal + 29L) / 30L) * 30L).coerceAtLeast(60L)
        selectedIndex = items.indexOfFirst { it.isToday }.coerceAtLeast(0)

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 750L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animationFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bars.isEmpty()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val paddingLeft = 32f
                val paddingRight = 32f
                val chartW = width.toFloat() - paddingLeft - paddingRight
                val count = bars.size
                val colW = chartW / count
                val touchX = event.x - paddingLeft
                val idx = (touchX / colW).toInt().coerceIn(0, count - 1)
                if (selectedIndex != idx) {
                    selectedIndex = idx
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        val accentColor = ContextCompat.getColor(context, R.color.color_accent)
        val textPrimary = ContextCompat.getColor(context, R.color.color_text_primary)
        val textSecondary = ContextCompat.getColor(context, R.color.color_text_secondary)
        val dividerColor = ContextCompat.getColor(context, R.color.color_divider)
        val normalBarColor = if (isDark) Color.parseColor("#26272F") else Color.parseColor("#E2E4E9")

        val paddingL = 36f
        val paddingR = 36f
        val paddingTop = 50f
        val paddingBottom = 75f

        val chartH = h - paddingTop - paddingBottom
        val chartW = w - paddingL - paddingR
        val count = bars.size
        val colW = chartW / count
        val barW = (colW * 0.52f).coerceAtMost(46f)

        // 1. Draw Target Benchmark Guide Line
        val targetY = paddingTop + chartH * (1f - (targetMinutes.toFloat() / maxScaleMinutes.toFloat()))
        gridLinePaint.color = dividerColor
        canvas.drawLine(paddingL, targetY, w - paddingR, targetY, gridLinePaint)

        textPaint.textSize = 24f
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = textSecondary
        val targetLabel = if (targetMinutes >= 60) "${targetMinutes / 60}h Goal" else "${targetMinutes}m Goal"
        canvas.drawText(targetLabel, w - paddingR, targetY - 8f, textPaint)

        // 2. Draw Baseline
        gridLinePaint.pathEffect = null
        canvas.drawLine(paddingL, paddingTop + chartH, w - paddingR, paddingTop + chartH, gridLinePaint)

        // 3. Draw Columns
        for (i in bars.indices) {
            val bar = bars[i]
            val cx = paddingL + (i * colW) + (colW / 2f)
            val barHeight = chartH * (bar.minutes.toFloat() / maxScaleMinutes.toFloat()) * animationFraction
            val topY = (paddingTop + chartH) - Math.max(barHeight, 6f)
            val bottomY = paddingTop + chartH

            barRect.set(cx - barW / 2f, topY, cx + barW / 2f, bottomY)

            val isSelected = (i == selectedIndex)
            if (bar.isToday || isSelected) {
                todayBarPaint.color = accentColor
                canvas.drawRoundRect(barRect, 10f, 10f, todayBarPaint)
            } else {
                barPaint.color = normalBarColor
                canvas.drawRoundRect(barRect, 10f, 10f, barPaint)
            }

            // Day Label
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 26f
            textPaint.color = if (bar.isToday || isSelected) textPrimary else textSecondary
            canvas.drawText(bar.dayLabel, cx, paddingTop + chartH + 34f, textPaint)

            // Date Subtitle (e.g., "14")
            textPaint.textSize = 21f
            textPaint.color = textSecondary
            canvas.drawText(bar.dateSubtitle, cx, paddingTop + chartH + 60f, textPaint)

            // Value label on top of bar if selected or > 0
            if (bar.minutes > 0) {
                val valueStr = if (bar.minutes >= 60) {
                    val hPart = bar.minutes / 60
                    val mPart = bar.minutes % 60
                    if (mPart > 0) "${hPart}h ${mPart}m" else "${hPart}h"
                } else {
                    "${bar.minutes}m"
                }

                boldTextPaint.textSize = 22f
                boldTextPaint.color = if (isSelected || bar.isToday) accentColor else textSecondary
                canvas.drawText(valueStr, cx, topY - 10f, boldTextPaint)
            }
        }

        // 4. Interactive Callout Tooltip if an index is selected
        if (selectedIndex in bars.indices) {
            val sel = bars[selectedIndex]
            val cx = paddingL + (selectedIndex * colW) + (colW / 2f)
            val tooltipText = "${sel.dayLabel}: ${sel.minutes}m focus logged"
            boldTextPaint.textSize = 26f
            val textW = boldTextPaint.measureText(tooltipText)
            val tipW = textW + 36f
            val tipH = 46f
            val tipLeft = (cx - tipW / 2f).coerceIn(paddingL, w - paddingR - tipW)
            val tipTop = 8f

            tooltipRect.set(tipLeft, tipTop, tipLeft + tipW, tipTop + tipH)
            tooltipBgPaint.color = if (isDark) Color.parseColor("#1C1C24") else Color.WHITE
            tooltipBorderPaint.color = dividerColor

            canvas.drawRoundRect(tooltipRect, 12f, 12f, tooltipBgPaint)
            canvas.drawRoundRect(tooltipRect, 12f, 12f, tooltipBorderPaint)

            boldTextPaint.color = textPrimary
            canvas.drawText(tooltipText, tooltipRect.centerX(), tooltipRect.centerY() + 9f, boldTextPaint)
        }
    }
}
