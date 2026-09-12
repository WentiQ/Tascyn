package com.example.tascyn.ui.components

import android.content.Context
import android.graphics.*
import android.text.TextUtils
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.example.tascyn.R
import com.example.tascyn.data.Task
import com.example.tascyn.data.UrgencyLevel
import com.example.tascyn.domain.NotionFormulas
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

class NotionTimelineGanttView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density
    private fun sp(v: Float): Float = v * density

    // Column & Row metrics with generous width so tasks strictly fit within their date column
    private var colWidth = dp(240f)
    private val headerHeight = dp(52f)
    private val rowHeight = dp(62f)
    private val barHeight = dp(46f)
    private val barCorner = dp(8f)
    private val leftGutterWidth = dp(90f)

    // Calendar state
    private var baseCalendar: Calendar = Calendar.getInstance()
    private var daysCount = 35 // 5 weeks view
    private var startCalendar: Calendar = Calendar.getInstance()

    // Data
    private var tasks: List<Task> = emptyList()
    private val taskRects = mutableListOf<Pair<Task, RectF>>()
    private val offscreenIndicators = mutableListOf<Pair<RectF, Int>>() // Indicator Rect -> Target Scroll X
    private val newButtonRect = RectF()

    // Viewport tracking for off-screen highlights
    private var viewportScrollX: Int = 0
    private var viewportWidth: Int = 0

    // Callbacks
    var onTaskClicked: ((Task) -> Unit)? = null
    var onNewTaskClicked: ((Long) -> Unit)? = null
    var onMonthChanged: ((String) -> Unit)? = null
    var onRequestScrollTo: ((Int) -> Unit)? = null

    // Date formats
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dayOfWeekFormat = SimpleDateFormat("EEE", Locale.getDefault())

    // Header & Grid Paints
    private val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
    }
    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F1F3F5")
        strokeWidth = dp(1f)
    }
    private val weekendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FAFAFB")
        style = Paint.Style.FILL
    }
    private val todayColumnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F5F7FF")
        style = Paint.Style.FILL
    }
    private val todayPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4F46E5")
        style = Paint.Style.FILL
    }
    private val textHeaderDayNamePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9CA3AF")
        textSize = dp(10.5f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val textHeaderDayNumberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#374151")
        textSize = dp(13f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val textHeaderTodayDayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        textSize = dp(13f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val textHeaderTodayLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4F46E5")
        textSize = dp(10f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    // Card Paints
    private val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
    }
    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E5E7EB")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val accentStripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val taskTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(12.5f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.parseColor("#111827")
    }
    private val reminderTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10.5f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        color = Color.parseColor("#4F46E5")
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(9.5f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // Left Gutter Paints
    private val gutterBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
    }
    private val newBtnBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F3F4F6")
        style = Paint.Style.FILL
    }
    private val newBtnBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E5E7EB")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val newBtnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#374151")
        textSize = dp(12f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    init {
        recomputeDates()
    }

    private fun recomputeDates() {
        val cal = Calendar.getInstance()
        cal.timeInMillis = baseCalendar.timeInMillis
        cal.set(Calendar.DAY_OF_MONTH, 1)
        // Backtrack to starting Sunday
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val offset = (dayOfWeek - Calendar.SUNDAY)
        cal.add(Calendar.DAY_OF_MONTH, -offset)
        startCalendar = cal

        val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        onMonthChanged?.invoke(monthFormat.format(baseCalendar.time))
        invalidate()
    }

    private fun computeDayOffset(targetMillis: Long): Int {
        val start0 = Calendar.getInstance().apply {
            timeInMillis = startCalendar.timeInMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val target0 = Calendar.getInstance().apply {
            timeInMillis = targetMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return ((target0.timeInMillis - start0.timeInMillis) / (24 * 3600 * 1000L)).toInt()
    }

    fun setTasks(taskList: List<Task>) {
        val now = System.currentTimeMillis()
        // Sort tasks chronologically by next upcoming reminder/alarm milestone
        this.tasks = taskList.sortedWith(
            compareBy<Task> { it.isCompleted }
                .thenBy { NotionFormulas.calculateNextReminderInfo(it, now).triggerTime }
        )
        requestLayout()
        invalidate()
    }

    fun setGranularity(granularity: String) {
        when (granularity.lowercase(Locale.ROOT)) {
            "day" -> {
                colWidth = dp(320f)
                daysCount = 14
            }
            "week" -> {
                colWidth = dp(240f)
                daysCount = 35
            }
            "month" -> {
                colWidth = dp(180f)
                daysCount = 60
            }
            "quarter" -> {
                colWidth = dp(130f)
                daysCount = 90
            }
            "year" -> {
                colWidth = dp(90f)
                daysCount = 365
            }
            else -> {
                colWidth = dp(240f)
                daysCount = 35
            }
        }
        recomputeDates()
        requestLayout()
        invalidate()
    }

    fun setDate(year: Int, month: Int) {
        baseCalendar.set(Calendar.YEAR, year)
        baseCalendar.set(Calendar.MONTH, month)
        baseCalendar.set(Calendar.DAY_OF_MONTH, 1)
        recomputeDates()
    }

    fun nextMonth() {
        baseCalendar.add(Calendar.MONTH, 1)
        recomputeDates()
    }

    fun prevMonth() {
        baseCalendar.add(Calendar.MONTH, -1)
        recomputeDates()
    }

    fun goToToday() {
        baseCalendar = Calendar.getInstance()
        recomputeDates()
    }

    fun setViewport(scrollX: Int, width: Int) {
        if (viewportScrollX != scrollX || viewportWidth != width) {
            viewportScrollX = scrollX
            viewportWidth = width
            invalidate()
        }
    }

    fun getTodayColumnScrollX(): Int {
        val todayCal = Calendar.getInstance()
        val diffDays = computeDayOffset(todayCal.timeInMillis).coerceAtLeast(0)
        val scrollTarget = (leftGutterWidth + (diffDays * colWidth) - dp(40f)).toInt()
        return max(0, scrollTarget)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = (leftGutterWidth + (daysCount * colWidth)).toInt()
        val rowCount = max(tasks.size + 1, 10)
        val totalHeight = (headerHeight + (rowCount * rowHeight) + dp(60f)).toInt()
        setMeasuredDimension(totalWidth, totalHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        taskRects.clear()
        offscreenIndicators.clear()

        val now = System.currentTimeMillis()
        val totalWidth = leftGutterWidth + (daysCount * colWidth)
        val todayCal = Calendar.getInstance()
        val todayDayOfYear = todayCal.get(Calendar.DAY_OF_YEAR)
        val todayYear = todayCal.get(Calendar.YEAR)
        val visibleLeft = if (viewportWidth > 0) max(leftGutterWidth, viewportScrollX.toFloat()) else leftGutterWidth
        val visibleRight = if (viewportWidth > 0) (viewportScrollX + viewportWidth).toFloat() else totalWidth

        // 1. Draw Columns & Weekend / Today Stripes
        val tempCal = Calendar.getInstance()
        tempCal.timeInMillis = startCalendar.timeInMillis

        for (i in 0 until daysCount) {
            val colLeft = leftGutterWidth + (i * colWidth)
            val colRight = colLeft + colWidth
            val dayOfWeek = tempCal.get(Calendar.DAY_OF_WEEK)
            val isWeekend = (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY)
            val isToday = (tempCal.get(Calendar.YEAR) == todayYear && tempCal.get(Calendar.DAY_OF_YEAR) == todayDayOfYear)

            if (isToday) {
                canvas.drawRect(colLeft, headerHeight, colRight, height.toFloat(), todayColumnPaint)
            } else if (isWeekend) {
                canvas.drawRect(colLeft, headerHeight, colRight, height.toFloat(), weekendPaint)
            }

            // Grid vertical line
            canvas.drawLine(colLeft, 0f, colLeft, height.toFloat(), gridLinePaint)
            tempCal.add(Calendar.DAY_OF_MONTH, 1)
        }

        // 2. Draw Horizontal Header Row (Days with Day-of-Week + Date Number)
        canvas.drawRect(0f, 0f, totalWidth, headerHeight, headerBgPaint)
        canvas.drawLine(0f, headerHeight, totalWidth, headerHeight, gridLinePaint)

        tempCal.timeInMillis = startCalendar.timeInMillis
        for (i in 0 until daysCount) {
            val colCenterX = leftGutterWidth + (i * colWidth) + (colWidth / 2f)
            val dayNumber = tempCal.get(Calendar.DAY_OF_MONTH).toString()
            val dayName = dayOfWeekFormat.format(tempCal.time).uppercase(Locale.getDefault())
            val isToday = (tempCal.get(Calendar.YEAR) == todayYear && tempCal.get(Calendar.DAY_OF_YEAR) == todayDayOfYear)

            if (isToday) {
                // Today Label
                canvas.drawText("TODAY", colCenterX, dp(14f), textHeaderTodayLabelPaint)
                // Pill behind day number
                val pillWidth = dp(26f)
                val pillHeight = dp(24f)
                val pillRect = RectF(
                    colCenterX - pillWidth / 2f,
                    dp(18f),
                    colCenterX + pillWidth / 2f,
                    dp(18f) + pillHeight
                )
                canvas.drawRoundRect(pillRect, dp(12f), dp(12f), todayPillPaint)
                canvas.drawText(dayNumber, colCenterX, dp(35f), textHeaderTodayDayPaint)
            } else {
                // Day name
                canvas.drawText(dayName, colCenterX, dp(17f), textHeaderDayNamePaint)
                // Day number
                canvas.drawText(dayNumber, colCenterX, dp(36f), textHeaderDayNumberPaint)
            }
            tempCal.add(Calendar.DAY_OF_MONTH, 1)
        }

        // 3. Draw Left Gutter (+ New row)
        canvas.drawRect(0f, 0f, leftGutterWidth, height.toFloat(), gutterBgPaint)
        canvas.drawLine(leftGutterWidth, 0f, leftGutterWidth, height.toFloat(), gridLinePaint)

        // + New button slot
        newButtonRect.set(
            dp(8f),
            headerHeight + dp(10f),
            leftGutterWidth - dp(8f),
            headerHeight + rowHeight - dp(10f)
        )
        canvas.drawRoundRect(newButtonRect, dp(6f), dp(6f), newBtnBgPaint)
        canvas.drawRoundRect(newButtonRect, dp(6f), dp(6f), newBtnBorderPaint)
        canvas.drawText("+ New", newButtonRect.centerX(), newButtonRect.centerY() + dp(4.5f), newBtnTextPaint)

        // Horizontal Row Separators
        val rowCount = max(tasks.size + 1, 10)
        for (r in 0..rowCount) {
            val y = headerHeight + (r * rowHeight)
            canvas.drawLine(0f, y, totalWidth, y, gridLinePaint)
        }

        // 4. Draw Task Cards & Off-Screen Highlights per row (Left & Right)
        tasks.forEachIndexed { index, task ->
            val rowIndex = index + 1 // below + New row
            val topY = headerHeight + (rowIndex * rowHeight) + ((rowHeight - barHeight) / 2f)
            val bottomY = topY + barHeight

            // Map task strictly to its next upcoming reminder/alarm milestone date column
            val reminderInfo = NotionFormulas.calculateNextReminderInfo(task, now)
            val targetTaskTimestamp = reminderInfo.triggerTime
            val dayOffset = computeDayOffset(targetTaskTimestamp)
            val targetTaskColLeft = leftGutterWidth + (dayOffset * colWidth)
            val targetTaskColRight = targetTaskColLeft + colWidth

            // Check if the task is located off-screen to the left or to the right
            val isOffscreenLeft = (dayOffset < 0) || (viewportWidth > 0 && targetTaskColRight <= visibleLeft + dp(12f))
            val isOffscreenRight = (dayOffset >= daysCount) || (viewportWidth > 0 && targetTaskColLeft >= visibleRight - dp(12f))

            if (isOffscreenLeft) {
                // Draw floating highlight indicator on the LEFT edge of this row
                val indWidth = dp(34f)
                val indHeight = dp(28f)
                val indLeft = visibleLeft + dp(6f)
                val indRight = indLeft + indWidth
                val indTop = topY + ((barHeight - indHeight) / 2f)
                val indBottom = indTop + indHeight

                val indRect = RectF(indLeft, indTop, indRight, indBottom)
                val targetScrollX = (targetTaskColLeft - dp(40f)).toInt().coerceAtLeast(0)
                offscreenIndicators.add(Pair(indRect, targetScrollX))

                // Urgency theme for the off-screen highlight
                val qResult = NotionFormulas.calculateQuadrant(task)
                val tlResult = NotionFormulas.calculateTimeLeft(task)
                val (accentColor, badgeBgColor, badgeTextColor) = when {
                    task.isCompleted ->
                        Triple("#9CA3AF", "#F3F4F6", "#6B7280")
                    reminderInfo.milestoneType == NotionFormulas.MilestoneType.OVERDUE || tlResult.urgencyLevel == UrgencyLevel.OVERDUE || reminderInfo.milestoneType == NotionFormulas.MilestoneType.URGENT || tlResult.urgencyLevel == UrgencyLevel.URGENT || qResult.qNumber in 1..15 ->
                        Triple("#EF4444", "#FEE2E2", "#DC2626")
                    reminderInfo.milestoneType == NotionFormulas.MilestoneType.ATTENTION || qResult.qNumber in 16..45 ->
                        Triple("#F59E0B", "#FEF3C7", "#D97706")
                    else ->
                        Triple("#4F46E5", "#EEF2FF", "#4F46E5")
                }

                val indBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor(badgeBgColor)
                    style = Paint.Style.FILL
                }
                val indBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor(accentColor)
                    style = Paint.Style.STROKE
                    strokeWidth = dp(1.2f)
                }
                val indTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor(badgeTextColor)
                    textSize = dp(11f)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }

                canvas.drawRoundRect(indRect, dp(14f), dp(14f), indBgPaint)
                canvas.drawRoundRect(indRect, dp(14f), dp(14f), indBorderPaint)
                canvas.drawText("◀", indRect.centerX(), indRect.centerY() + dp(4f), indTextPaint)

            } else if (isOffscreenRight) {
                // Draw floating highlight indicator on the RIGHT edge of this row
                val indWidth = dp(34f)
                val indHeight = dp(28f)
                val indRight = visibleRight - dp(6f)
                val indLeft = indRight - indWidth
                val indTop = topY + ((barHeight - indHeight) / 2f)
                val indBottom = indTop + indHeight

                val indRect = RectF(indLeft, indTop, indRight, indBottom)
                val targetScrollX = (targetTaskColLeft - dp(80f)).toInt().coerceAtLeast(0)
                offscreenIndicators.add(Pair(indRect, targetScrollX))

                // Urgency theme for the off-screen highlight
                val qResult = NotionFormulas.calculateQuadrant(task)
                val tlResult = NotionFormulas.calculateTimeLeft(task)
                val (accentColor, badgeBgColor, badgeTextColor) = when {
                    task.isCompleted ->
                        Triple("#9CA3AF", "#F3F4F6", "#6B7280")
                    reminderInfo.milestoneType == NotionFormulas.MilestoneType.OVERDUE || tlResult.urgencyLevel == UrgencyLevel.OVERDUE || reminderInfo.milestoneType == NotionFormulas.MilestoneType.URGENT || tlResult.urgencyLevel == UrgencyLevel.URGENT || qResult.qNumber in 1..15 ->
                        Triple("#EF4444", "#FEE2E2", "#DC2626")
                    reminderInfo.milestoneType == NotionFormulas.MilestoneType.ATTENTION || qResult.qNumber in 16..45 ->
                        Triple("#F59E0B", "#FEF3C7", "#D97706")
                    else ->
                        Triple("#4F46E5", "#EEF2FF", "#4F46E5")
                }

                val indBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor(badgeBgColor)
                    style = Paint.Style.FILL
                }
                val indBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor(accentColor)
                    style = Paint.Style.STROKE
                    strokeWidth = dp(1.2f)
                }
                val indTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor(badgeTextColor)
                    textSize = dp(11f)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                }

                canvas.drawRoundRect(indRect, dp(14f), dp(14f), indBgPaint)
                canvas.drawRoundRect(indRect, dp(14f), dp(14f), indBorderPaint)
                canvas.drawText("▶", indRect.centerX(), indRect.centerY() + dp(4f), indTextPaint)
            } else if (dayOffset in 0 until daysCount) {
                val colLeft = leftGutterWidth + (dayOffset * colWidth)
                val colRight = colLeft + colWidth
                val cardMargin = dp(6f)

                // Task card stays STRICTLY within its day column bounds
                val barLeft = colLeft + cardMargin
                val barRight = colRight - cardMargin

                val rect = RectF(barLeft, topY, barRight, bottomY)
                taskRects.add(Pair(task, rect))

                // Quadrant and Urgency Metrics
                val qResult = NotionFormulas.calculateQuadrant(task)
                val tlResult = NotionFormulas.calculateTimeLeft(task)

                val (accentColor, badgeBgColor, badgeTextColor, badgeLabel) = when {
                    task.isCompleted ->
                        QuadTheme("#9CA3AF", "#F3F4F6", "#6B7280", "Done")
                    reminderInfo.milestoneType == NotionFormulas.MilestoneType.OVERDUE || tlResult.urgencyLevel == UrgencyLevel.OVERDUE ->
                        QuadTheme("#EF4444", "#FEE2E2", "#DC2626", "Overdue")
                    reminderInfo.milestoneType == NotionFormulas.MilestoneType.URGENT || tlResult.urgencyLevel == UrgencyLevel.URGENT || qResult.qNumber in 1..15 ->
                        QuadTheme("#EF4444", "#FEE2E2", "#DC2626", if (qResult.isValid) "Q${qResult.qNumber} · Urgent" else "Urgent")
                    reminderInfo.milestoneType == NotionFormulas.MilestoneType.ATTENTION || tlResult.urgencyLevel == UrgencyLevel.ATTENTION_NEEDED || qResult.qNumber in 16..45 ->
                        QuadTheme("#F59E0B", "#FEF3C7", "#D97706", if (qResult.isValid) "Q${qResult.qNumber} · Attention" else "Attention")
                    reminderInfo.milestoneType == NotionFormulas.MilestoneType.CUSTOM_REMINDER ->
                        QuadTheme("#6366F1", "#EEF2FF", "#4F46E5", "Reminder")
                    else ->
                        QuadTheme("#10B981", "#D1FAE5", "#059669", if (qResult.isValid) "Q${qResult.qNumber} · On Track" else "On Track")
                }

                // Draw Card Body
                canvas.drawRoundRect(rect, barCorner, barCorner, cardBgPaint)
                cardBorderPaint.color = Color.parseColor(if (task.isCompleted) "#E5E7EB" else "#E2E8F0")
                canvas.drawRoundRect(rect, barCorner, barCorner, cardBorderPaint)

                // Draw Left Color Accent Strip (5dp width)
                val accentRect = RectF(barLeft, topY, barLeft + dp(5f), bottomY)
                accentStripPaint.color = Color.parseColor(accentColor)
                canvas.save()
                canvas.clipRect(rect)
                canvas.drawRoundRect(accentRect, barCorner, barCorner, accentStripPaint)
                canvas.restore()

                // Clip inner card content
                canvas.save()
                canvas.clipRect(RectF(barLeft + dp(5f), topY, barRight - dp(4f), bottomY))

                // Format Next Remainder Milestone Time Text
                val timeStr = timeFormat.format(Date(reminderInfo.triggerTime))
                val remainderStr = when {
                    task.isCompleted -> "✓ Completed"
                    reminderInfo.milestoneType == NotionFormulas.MilestoneType.OVERDUE -> "🚨 Overdue · Due $timeStr"
                    reminderInfo.milestoneType == NotionFormulas.MilestoneType.ATTENTION -> "⏰ Attention at $timeStr"
                    reminderInfo.milestoneType == NotionFormulas.MilestoneType.URGENT -> "⏰ Urgent alarm at $timeStr"
                    reminderInfo.milestoneType == NotionFormulas.MilestoneType.CUSTOM_REMINDER -> "⏰ Reminder at $timeStr"
                    reminderInfo.milestoneType == NotionFormulas.MilestoneType.DUE -> "⏰ Due at $timeStr"
                    else -> "⏰ $timeStr"
                }

                // Draw Remainder Time
                reminderTimePaint.color = if (task.isCompleted) Color.parseColor("#9CA3AF") else Color.parseColor(accentColor)
                val remainderX = barLeft + dp(10f)
                val remainderY = topY + dp(16f)
                canvas.drawText(remainderStr, remainderX, remainderY, reminderTimePaint)

                // Draw Urgency / Quadrant Badge on the right
                val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = dp(9.5f)
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                }
                val badgeTextWidth = badgePaint.measureText(badgeLabel)
                val badgeW = badgeTextWidth + dp(12f)
                val badgeH = dp(16f)
                val badgeRight = barRight - dp(8f)
                val badgeLeft = badgeRight - badgeW
                val badgeTop = topY + dp(5f)
                val badgeBottom = badgeTop + badgeH

                badgeBgPaint.color = Color.parseColor(badgeBgColor)
                badgeTextPaint.color = Color.parseColor(badgeTextColor)

                val badgeRect = RectF(badgeLeft, badgeTop, badgeRight, badgeBottom)
                canvas.drawRoundRect(badgeRect, dp(4f), dp(4f), badgeBgPaint)
                canvas.drawText(badgeLabel, badgeLeft + dp(6f), badgeTop + dp(11.5f), badgeTextPaint)

                // Draw Task Title
                taskTitlePaint.color = if (task.isCompleted) Color.parseColor("#9CA3AF") else Color.parseColor("#111827")
                if (task.isCompleted) {
                    taskTitlePaint.flags = taskTitlePaint.flags or Paint.STRIKE_THRU_TEXT_FLAG
                } else {
                    taskTitlePaint.flags = taskTitlePaint.flags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                }

                val maxTitleWidth = (barRight - dp(10f)) - (barLeft + dp(10f))
                val elidedTitle = TextUtils.ellipsize(task.title, android.text.TextPaint(taskTitlePaint), maxTitleWidth, TextUtils.TruncateAt.END).toString()
                val titleX = barLeft + dp(10f)
                val titleY = topY + dp(34f)
                canvas.drawText(elidedTitle, titleX, titleY, taskTitlePaint)

                canvas.restore()
            }
        }
    }

    private data class QuadTheme(
        val accentColor: String,
        val badgeBgColor: String,
        val badgeTextColor: String,
        val badgeLabel: String
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y

            // Check Off-screen Right Indicator Clicks
            for ((rect, targetScrollX) in offscreenIndicators) {
                if (rect.contains(x, y)) {
                    onRequestScrollTo?.invoke(targetScrollX)
                    return true
                }
            }

            // Check + New Click
            if (newButtonRect.contains(x, y)) {
                onNewTaskClicked?.invoke(System.currentTimeMillis())
                return true
            }

            // Check Task Card Clicks
            for ((task, rect) in taskRects) {
                if (rect.contains(x, y)) {
                    onTaskClicked?.invoke(task)
                    return true
                }
            }

            // Check Empty Grid Cell Click
            if (y > headerHeight && x > leftGutterWidth) {
                val colIdx = ((x - leftGutterWidth) / colWidth).toInt()
                val clickedCal = Calendar.getInstance()
                clickedCal.timeInMillis = startCalendar.timeInMillis
                clickedCal.add(Calendar.DAY_OF_MONTH, colIdx)
                onNewTaskClicked?.invoke(clickedCal.timeInMillis)
                return true
            }
        }
        return true
    }
}
