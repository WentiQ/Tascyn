package com.example.tascyn.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.example.tascyn.R

data class HourlyProductivityPoint(
    val hour: Int,
    val label: String,
    val minutes: Float,
    val isPeak: Boolean = false,
    val isGoodProductivity: Boolean = false
)

data class ProductivityTimeRange(
    val startHour: Int,
    val endHour: Int,
    val label: String,
    val totalMinutes: Float,
    val percentageOfTotal: Float
)

// Maintained for backward compatibility if needed
data class DiurnalFocusSlot(
    val title: String,
    val timeRange: String,
    val minutes: Long,
    val percentage: Float
)

/**
 * FocusHourlyDistributionView
 * Continuous 24-Hour Productivity Line/Spline Chart.
 * Visualizes user productivity across each hour of the day (00:00 to 23:00)
 * for the selected analytics period (7D, 30D, All Time).
 * Features:
 *  - Continuous Cubic Spline curve with silky gradient fill
 *  - High-Productivity time range highlighting with subtle shaded zones
 *  - Peak hour glowing marker
 *  - Interactive touch scrubber with dynamic floating tooltip and haptic feedback
 *  - Automatic Day/Night theme matching
 */
class FocusHourlyDistributionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val hourlyPoints = mutableListOf<HourlyProductivityPoint>()
    private val goodRanges = mutableListOf<ProductivityTimeRange>()
    private var peakHour: Int = -1
    private var maxScaleMinutes: Float = 60f
    private var animFraction: Float = 0f

    private var selectedHour: Int = -1
    private var isScrubbing: Boolean = false

    // Paints
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val highlightZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val highlightZoneBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val boldLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
    }

    private val scrubberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val tooltipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val zoneBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val zoneBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
        letterSpacing = 0.04f
    }

    private val emptyStatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    // Geometry helpers
    private val curvePath = Path()
    private val fillPath = Path()
    private val zoneRect = RectF()
    private val tooltipRect = RectF()
    private val badgeRect = RectF()
    private val tempPoint = PointF()

    init {
        // Initialize default 24 hours
        if (hourlyPoints.isEmpty()) {
            for (h in 0..23) {
                hourlyPoints.add(HourlyProductivityPoint(h, String.format("%02d:00", h), 0f))
            }
        }
    }

    /**
     * Primary data setter for 24-hour continuous distribution
     */
    fun setHourlyDistribution(
        hourlyMinutes: FloatArray,
        peakHour: Int = -1,
        goodProductivityRanges: List<ProductivityTimeRange> = emptyList()
    ) {
        hourlyPoints.clear()
        goodRanges.clear()
        goodRanges.addAll(goodProductivityRanges)

        val rawMax = hourlyMinutes.maxOrNull() ?: 0f
        this.peakHour = if (rawMax > 0f) peakHour else -1
        this.maxScaleMinutes = maxOf(30f, Math.ceil(rawMax / 15.0).toFloat() * 15f)

        val goodThreshold = if (rawMax > 0f) maxOf(8f, rawMax * 0.40f) else Float.MAX_VALUE

        for (h in 0..23) {
            val mins = if (h in hourlyMinutes.indices) hourlyMinutes[h] else 0f
            val isPeak = (h == this.peakHour && rawMax > 0f)
            val isGood = (mins >= goodThreshold && rawMax > 0f)
            hourlyPoints.add(
                HourlyProductivityPoint(
                    hour = h,
                    label = String.format("%02d:00", h),
                    minutes = mins,
                    isPeak = isPeak,
                    isGoodProductivity = isGood
                )
            )
        }

        // Set default selected hour to peak or middle of prime zone
        selectedHour = if (this.peakHour in 0..23) {
            this.peakHour
        } else {
            goodRanges.firstOrNull()?.let { (it.startHour + it.endHour) / 2 } ?: 12
        }

        startEntryAnimation()
    }

    /**
     * Legacy adapter method to avoid breaking older callers
     */
    fun setData(items: List<DiurnalFocusSlot>) {
        val array = FloatArray(24)
        for (slot in items) {
            val rangeHours = when (slot.title.lowercase()) {
                "morning" -> 6..11
                "afternoon" -> 12..16
                "evening" -> 17..20
                else -> listOf(21, 22, 23, 0, 1, 2, 3, 4, 5)
            }
            val count = if (rangeHours is IntRange) (rangeHours.last - rangeHours.first + 1) else 9
            val perHour = slot.minutes.toFloat() / count.toFloat()
            if (rangeHours is IntRange) {
                for (h in rangeHours) array[h] = perHour
            } else {
                for (h in listOf(21, 22, 23, 0, 1, 2, 3, 4, 5)) array[h] = perHour
            }
        }
        val peak = array.indices.maxByOrNull { array[it] } ?: 10
        setHourlyDistribution(array, peak)
    }

    private fun startEntryAnimation() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animFraction = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (hourlyPoints.isEmpty()) return super.onTouchEvent(event)

        val padL = 36f
        val padR = 36f
        val chartW = width.toFloat() - padL - padR
        val stepX = chartW / 23f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isScrubbing = true
                val touchHour = ((event.x - padL + stepX * 0.5f) / stepX).toInt().coerceIn(0, 23)
                if (selectedHour != touchHour) {
                    selectedHour = touchHour
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val touchHour = ((event.x - padL + stepX * 0.5f) / stepX).toInt().coerceIn(0, 23)
                if (selectedHour != touchHour) {
                    selectedHour = touchHour
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isScrubbing = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hourlyPoints.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        val accentColor = ContextCompat.getColor(context, R.color.color_accent)
        val textPrimary = ContextCompat.getColor(context, R.color.color_text_primary)
        val textSecondary = ContextCompat.getColor(context, R.color.color_text_secondary)
        val textTertiary = ContextCompat.getColor(context, R.color.color_text_tertiary)
        val dividerColor = ContextCompat.getColor(context, R.color.color_divider)
        val cardBgColor = if (isDark) Color.parseColor("#14141A") else Color.WHITE

        val padL = 36f
        val padR = 36f
        val padTop = 38f
        val padBottom = 54f

        val chartW = w - padL - padR
        val chartH = h - padTop - padBottom
        val stepX = chartW / 23f
        val baselineY = padTop + chartH

        val hasAnyData = hourlyPoints.any { it.minutes > 0f }

        // 1. Draw High-Productivity Range Highlight Zones
        if (hasAnyData && goodRanges.isNotEmpty()) {
            val zoneAlpha = if (isDark) 35 else 24
            val zoneColor = (zoneAlpha shl 24) or (accentColor and 0x00FFFFFF)
            highlightZonePaint.color = zoneColor
            highlightZoneBorderPaint.color = (60 shl 24) or (accentColor and 0x00FFFFFF)

            for (range in goodRanges) {
                val zStart = (padL + (range.startHour * stepX) - (stepX * 0.48f)).coerceAtLeast(padL)
                val zEnd = (padL + (range.endHour * stepX) + (stepX * 0.48f)).coerceAtMost(w - padR)

                zoneRect.set(zStart, padTop + 4f, zEnd, baselineY)
                canvas.drawRoundRect(zoneRect, 10f, 10f, highlightZonePaint)
                canvas.drawRoundRect(zoneRect, 10f, 10f, highlightZoneBorderPaint)

                // Draw Prime Focus Zone micro-badge at top of the zone
                if (zEnd - zStart >= 55f) {
                    val badgeW = 74f
                    val badgeH = 22f
                    val badgeX = ((zStart + zEnd) / 2f) - (badgeW / 2f)
                    val badgeY = padTop + 8f
                    badgeRect.set(badgeX, badgeY, badgeX + badgeW, badgeY + badgeH)

                    zoneBadgeBgPaint.color = accentColor
                    canvas.drawRoundRect(badgeRect, 6f, 6f, zoneBadgeBgPaint)

                    zoneBadgeTextPaint.textSize = 14f
                    zoneBadgeTextPaint.color = Color.WHITE
                    canvas.drawText("PRIME ZONE", badgeRect.centerX(), badgeRect.centerY() + 5f, zoneBadgeTextPaint)
                }
            }
        }

        // 2. Draw Reference Gridlines & Y-Axis Scale
        gridLinePaint.color = dividerColor
        val midY = padTop + (chartH * 0.5f)
        canvas.drawLine(padL, midY, w - padR, midY, gridLinePaint)
        canvas.drawLine(padL, padTop, w - padR, padTop, gridLinePaint)

        // Mid and Max minute indicators
        labelPaint.textAlign = Paint.Align.RIGHT
        labelPaint.textSize = 18f
        labelPaint.color = textTertiary
        val maxLabel = "${maxScaleMinutes.toInt()}m"
        val midLabel = "${(maxScaleMinutes / 2f).toInt()}m"
        canvas.drawText(maxLabel, w - padR + 2f, padTop - 6f, labelPaint)
        canvas.drawText(midLabel, w - padR + 2f, midY - 6f, labelPaint)

        // 3. Draw X-Axis Baseline
        axisPaint.color = dividerColor
        canvas.drawLine(padL, baselineY, w - padR, baselineY, axisPaint)

        // 4. Draw X-Axis Time Hour Markers: 00:00, 04:00, 08:00, 12:00, 16:00, 20:00, 23:00
        val landmarkHours = intArrayOf(0, 4, 8, 12, 16, 20, 23)
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize = 21f

        for (lh in landmarkHours) {
            val lx = padL + (lh * stepX)
            val isHourSelected = (selectedHour == lh)
            labelPaint.color = if (isHourSelected) textPrimary else textTertiary
            labelPaint.typeface = if (isHourSelected) Typeface.create("sans-serif-bold", Typeface.BOLD) else Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val hourLabel = String.format("%02d:00", lh)
            canvas.drawText(hourLabel, lx, baselineY + 28f, labelPaint)
        }

        // 5. Build Coordinates for Continuous Cubic Spline
        val pointCoords = Array(24) { PointF() }
        for (i in 0..23) {
            val pt = hourlyPoints[i]
            val px = padL + (i * stepX)
            val norm = (pt.minutes / maxScaleMinutes).coerceIn(0f, 1f) * animFraction
            val py = baselineY - (chartH * norm)
            pointCoords[i].set(px, py)
        }

        // 6. Draw Continuous Spline Curve & Area Gradient
        curvePath.reset()
        curvePath.moveTo(pointCoords[0].x, pointCoords[0].y)

        for (i in 0 until 23) {
            val p0 = if (i > 0) pointCoords[i - 1] else pointCoords[i]
            val p1 = pointCoords[i]
            val p2 = pointCoords[i + 1]
            val p3 = if (i + 2 < 24) pointCoords[i + 2] else p2

            val cp1x = p1.x + (p2.x - p0.x) * 0.18f
            val cp1y = p1.y + (p2.y - p0.y) * 0.18f
            val cp2x = p2.x - (p3.x - p1.x) * 0.18f
            val cp2y = p2.y - (p3.y - p1.y) * 0.18f

            curvePath.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
        }

        // Gradient Area Fill Under the Spline Curve
        fillPath.reset()
        fillPath.addPath(curvePath)
        fillPath.lineTo(padL + (23f * stepX), baselineY)
        fillPath.lineTo(padL, baselineY)
        fillPath.close()

        val gradTopAlpha = if (hasAnyData) (if (isDark) 95 else 75) else 0
        val gradMidAlpha = if (hasAnyData) (if (isDark) 35 else 20) else 0
        fillPaint.shader = LinearGradient(
            0f, padTop,
            0f, baselineY,
            intArrayOf(
                (gradTopAlpha shl 24) or (accentColor and 0x00FFFFFF),
                (gradMidAlpha shl 24) or (accentColor and 0x00FFFFFF),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        // Continuous Stroke Curve
        curvePaint.color = if (hasAnyData) accentColor else dividerColor
        curvePaint.strokeWidth = if (hasAnyData) 4.5f else 2.5f
        canvas.drawPath(curvePath, curvePaint)

        // 7. Draw Dots along the Curve for Notable / Good Productivity Hours
        if (hasAnyData) {
            for (i in 0..23) {
                val pt = hourlyPoints[i]
                if (pt.minutes <= 0f && !pt.isGoodProductivity && i != selectedHour) continue

                val cx = pointCoords[i].x
                val cy = pointCoords[i].y

                if (pt.isPeak) {
                    // Prominent Peak Dot with glowing aura
                    dotPaint.color = (45 shl 24) or (accentColor and 0x00FFFFFF)
                    canvas.drawCircle(cx, cy, 11f, dotPaint)

                    dotPaint.color = accentColor
                    canvas.drawCircle(cx, cy, 6f, dotPaint)

                    dotBorderPaint.color = cardBgColor
                    canvas.drawCircle(cx, cy, 3f, dotBorderPaint)
                } else if (pt.isGoodProductivity) {
                    // High Productivity Point Dot
                    dotPaint.color = (35 shl 24) or (accentColor and 0x00FFFFFF)
                    canvas.drawCircle(cx, cy, 8f, dotPaint)

                    dotPaint.color = accentColor
                    canvas.drawCircle(cx, cy, 4.5f, dotPaint)
                }
            }
        } else {
            // Empty State Notice in center of chart
            emptyStatePaint.textSize = 24f
            emptyStatePaint.color = textTertiary
            canvas.drawText("No focus sessions logged for this period", w / 2f, padTop + (chartH / 2f) + 8f, emptyStatePaint)
        }

        // 8. Interactive Scrubber Guide Line & Dynamic Floating Tooltip
        if (selectedHour in 0..23) {
            val selPt = hourlyPoints[selectedHour]
            val selX = pointCoords[selectedHour].x
            val selY = pointCoords[selectedHour].y

            // Draw vertical dashed scrubber line
            scrubberPaint.color = if (isDark) Color.parseColor("#4B5563") else Color.parseColor("#9CA3AF")
            canvas.drawLine(selX, padTop, selX, baselineY, scrubberPaint)

            // Scrubber point highlight
            dotPaint.color = accentColor
            canvas.drawCircle(selX, selY, 6f, dotPaint)
            dotBorderPaint.color = cardBgColor
            canvas.drawCircle(selX, selY, 3f, dotBorderPaint)

            // Tooltip text formatting
            val nextH = (selectedHour + 1) % 24
            val timeRangeStr = String.format("%02d:00–%02d:00", selectedHour, nextH)
            val mins = selPt.minutes.toInt()
            val durStr = if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
            val statusTag = when {
                selPt.isPeak -> " ★ Peak"
                selPt.isGoodProductivity -> " 🔥 High"
                mins > 0 -> " • Active"
                else -> " • Idle"
            }
            val tooltipText = "$timeRangeStr: $durStr$statusTag"

            boldLabelPaint.textSize = 23f
            val textWidth = boldLabelPaint.measureText(tooltipText)
            val tipW = textWidth + 30f
            val tipH = 40f

            // Position tooltip dynamically above point, clamped within bounds
            val idealTipX = selX - (tipW / 2f)
            val tipLeft = idealTipX.coerceIn(padL, w - padR - tipW)
            val tipTop = (selY - tipH - 14f).coerceAtLeast(6f)

            tooltipRect.set(tipLeft, tipTop, tipLeft + tipW, tipTop + tipH)
            tooltipBgPaint.color = if (isDark) Color.parseColor("#1F2028") else Color.WHITE
            tooltipBorderPaint.color = if (selPt.isGoodProductivity || selPt.isPeak) accentColor else dividerColor

            canvas.drawRoundRect(tooltipRect, 10f, 10f, tooltipBgPaint)
            canvas.drawRoundRect(tooltipRect, 10f, 10f, tooltipBorderPaint)

            boldLabelPaint.color = if (selPt.isGoodProductivity || selPt.isPeak) accentColor else textPrimary
            canvas.drawText(tooltipText, tooltipRect.centerX(), tooltipRect.centerY() + 7f, boldLabelPaint)
        }
    }
}
