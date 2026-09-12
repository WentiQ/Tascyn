package com.example.tascyn.domain

import com.example.tascyn.data.*
import kotlin.math.floor

/**
 * Exact implementation of Notion "Task Manager" formulas
 */
object NotionFormulas {

    /**
     * Helper to parse "1d 0h 0m" format into total minutes
     */
    fun parseMinimumTimeToMinutes(minText: String?): Long {
        if (minText.isNullOrBlank()) return 0L
        val parts = minText.trim().split(Regex("\\s+"))
        var days = 0L
        var hours = 0L
        var minutes = 0L

        for (part in parts) {
            val clean = part.trim().lowercase()
            when {
                clean.endsWith("d") -> days = clean.removeSuffix("d").toLongOrNull() ?: 0L
                clean.endsWith("h") -> hours = clean.removeSuffix("h").toLongOrNull() ?: 0L
                clean.endsWith("m") -> minutes = clean.removeSuffix("m").toLongOrNull() ?: 0L
            }
        }
        return (days * 1440L) + (hours * 60L) + minutes
    }

    /**
     * Helper to format minutes into "Xd Yh Zm"
     */
    fun formatMinutesToDuration(totalMinutes: Long): String {
        val days = totalMinutes / 1440L
        val remainingAfterDays = totalMinutes - (days * 1440L)
        val hours = remainingAfterDays / 60L
        val minutes = remainingAfterDays - (hours * 60L)
        return "${days}d ${hours}h ${minutes}m"
    }

    /**
     * Formula 2.1: Total Duration
     * Sum total work duration across:
     * - all linked sessions on this task
     * - plus sessions linked to any sub-tasks
     */
    fun calculateTotalDuration(
        task: Task,
        allTasks: List<Task>,
        allSessions: List<TimesheetSession>
    ): String {
        val subTasks = allTasks.filter { it.parentTaskId == task.id }
        val targetTaskIds = setOf(task.id) + subTasks.map { it.id }.toSet()

        val validSessions = allSessions.filter { session ->
            session.taskId != null &&
            session.taskId in targetTaskIds &&
            session.startTime != null &&
            session.endTime != null
        }

        if (validSessions.isEmpty()) {
            return "No sessions"
        }

        val totalMinutes = validSessions.sumOf { session ->
            val diff = (session.endTime!! - session.startTime!!) / (1000L * 60L)
            if (diff > 0) diff else 0L
        }

        return formatMinutesToDuration(totalMinutes)
    }

    /**
     * Returns total minutes recorded for task and its subtasks
     */
    fun calculateTotalDurationMinutes(
        task: Task,
        allTasks: List<Task>,
        allSessions: List<TimesheetSession>
    ): Long {
        val subTasks = allTasks.filter { it.parentTaskId == task.id }
        val targetTaskIds = setOf(task.id) + subTasks.map { it.id }.toSet()

        val validSessions = allSessions.filter { session ->
            session.taskId != null &&
            session.taskId in targetTaskIds &&
            session.startTime != null &&
            session.endTime != null
        }

        return validSessions.sumOf { session ->
            val diff = (session.endTime!! - session.startTime!!) / (1000L * 60L)
            if (diff > 0) diff else 0L
        }
    }

    /**
     * Formula 2.2: Time Left
     * Compare Due Date vs now(), check whether time remaining is below Minimum Time Required.
     */
    fun calculateTimeLeft(
        task: Task,
        now: Long = System.currentTimeMillis()
    ): TimeLeftResult {
        val dueDate = task.dueDate
        if (dueDate == null) {
            return TimeLeftResult(
                rawText = "No due date set",
                urgencyLevel = UrgencyLevel.NO_DUE_DATE,
                minutesLeft = null,
                formattedTime = ""
            )
        }

        val minTotalMinutes = parseMinimumTimeToMinutes(task.minimumTimeRequired)
        val minutesLeft = (dueDate - now) / (1000L * 60L)

        val daysLeft = floor(minutesLeft / 1440.0).toLong()
        val hoursLeft = floor((minutesLeft - daysLeft * 1440L) / 60.0).toLong()
        val minsLeft = minutesLeft - (daysLeft * 1440L) - (hoursLeft * 60L)

        val timeLeftFormatted = "${daysLeft}d ${hoursLeft}h ${minsLeft}m"

        return when {
            minutesLeft < 0 -> {
                TimeLeftResult(
                    rawText = "Overdue",
                    urgencyLevel = UrgencyLevel.OVERDUE,
                    minutesLeft = minutesLeft,
                    formattedTime = timeLeftFormatted
                )
            }
            minutesLeft < minTotalMinutes -> {
                TimeLeftResult(
                    rawText = "Urgent · $timeLeftFormatted",
                    urgencyLevel = UrgencyLevel.URGENT,
                    minutesLeft = minutesLeft,
                    formattedTime = timeLeftFormatted
                )
            }
            minutesLeft < 2 * minTotalMinutes -> {
                TimeLeftResult(
                    rawText = "Attention Needed · $timeLeftFormatted",
                    urgencyLevel = UrgencyLevel.ATTENTION_NEEDED,
                    minutesLeft = minutesLeft,
                    formattedTime = timeLeftFormatted
                )
            }
            minutesLeft < 4 * minTotalMinutes -> {
                TimeLeftResult(
                    rawText = "On Track · $timeLeftFormatted",
                    urgencyLevel = UrgencyLevel.ON_TRACK,
                    minutesLeft = minutesLeft,
                    formattedTime = timeLeftFormatted
                )
            }
            else -> {
                TimeLeftResult(
                    rawText = timeLeftFormatted,
                    urgencyLevel = UrgencyLevel.NORMAL_TIME_LEFT,
                    minutesLeft = minutesLeft,
                    formattedTime = timeLeftFormatted
                )
            }
        }
    }

    /**
     * Formula 2.3: Quadrant Matrix Engine
     * Deterministic Q number + Action engine
     * qNumber = (uIndex - 1) * 15 + (pIndex - 1) * 5 + sIndex
     */
    fun calculateQuadrant(
        task: Task,
        now: Long = System.currentTimeMillis()
    ): QuadrantResult {
        val timeLeftResult = calculateTimeLeft(task, now)
        val tTime = timeLeftResult.rawText

        // Urgency index (1..5)
        val uIndex = when {
            tTime.contains("Overdue") -> 1
            tTime.contains("Urgent") -> 2
            tTime.contains("Attention Needed") -> 3
            tTime.contains("On Track") -> 4
            tTime.contains("d") || tTime.contains("h") || tTime.contains("m") -> 5
            else -> 0
        }

        // Priority index (1..3)
        val pIndex = when (task.priority) {
            TaskPriority.HIGH -> 1
            TaskPriority.MEDIUM -> 2
            TaskPriority.LOW -> 3
        }

        // Status index (1..5)
        val sIndex = when (task.status) {
            TaskStatus.NOT_STARTED -> 1
            TaskStatus.IN_PROGRESS -> 2
            TaskStatus.PROCRASTINATED -> 3
            TaskStatus.LEFT -> 4
            TaskStatus.DONE -> 5
        }

        if (uIndex == 0 || pIndex == 0 || sIndex == 0) {
            return QuadrantResult(
                qNumber = 0,
                action = "Invalid combination",
                fullLabel = "⚠ Invalid combination",
                urgencyIndex = uIndex,
                priorityIndex = pIndex,
                statusIndex = sIndex,
                isValid = false
            )
        }

        val qNumber = (uIndex - 1) * 15 + (pIndex - 1) * 5 + sIndex

        val action = when {
            sIndex == 5 -> "Done / Archive"
            uIndex == 1 -> "Recover Immediately"
            uIndex == 2 && pIndex == 1 -> "Do Now"
            uIndex == 2 -> "Start & Push"
            uIndex == 3 && pIndex == 1 -> "Schedule & Focus"
            sIndex == 3 -> "Break Procrastination"
            uIndex == 5 -> "Monitor (Time Buffer)"
            uIndex >= 4 && pIndex == 3 -> "Low Impact, Park"
            else -> "Review & Decide"
        }

        val fullLabel = "Q$qNumber – $action"

        return QuadrantResult(
            qNumber = qNumber,
            action = action,
            fullLabel = fullLabel,
            urgencyIndex = uIndex,
            priorityIndex = pIndex,
            statusIndex = sIndex,
            isValid = true
        )
    }

    /**
     * Formula 4.1: Timesheets Duration
     */
    fun calculateTimesheetDuration(
        session: TimesheetSession,
        now: Long = System.currentTimeMillis()
    ): String {
        val startTime = session.startTime ?: return "Duration: No Time Tracked"

        if (session.endTime == null) {
            val currDiffSeconds = (now - startTime).coerceAtLeast(0) / 1000L
            val currDiffMinutes = currDiffSeconds / 60L
            val h = currDiffMinutes / 60L
            val m = currDiffMinutes % 60L
            val s = currDiffSeconds % 60L
            return "Duration: ${h}h ${m}m ${s}s (In Progress)"
        } else {
            val timeDiffSeconds = ((session.endTime!! - startTime) / 1000L).coerceAtLeast(0)
            if (timeDiffSeconds < 60) {
                return "Duration: Hit Start Work"
            }
            val timeDiffMinutes = timeDiffSeconds / 60L
            val hours = timeDiffMinutes / 60L
            val minutes = timeDiffMinutes % 60L
            val seconds = timeDiffSeconds % 60L
            return "Duration: ${hours}h ${minutes}m ${seconds}s"
        }
    }

    /**
     * Data class holding calculated next reminder milestone info for timeline view
     */
    data class NextReminderInfo(
        val triggerTime: Long,
        val displayLabel: String,
        val isPastAllMilestones: Boolean,
        val milestoneType: MilestoneType
    )

    enum class MilestoneType {
        ATTENTION,
        URGENT,
        DUE,
        CUSTOM_REMINDER,
        OVERDUE,
        COMPLETED
    }

    /**
     * Determines the next upcoming reminder date/time for timeline placement:
     * 1. Attention Needed milestone: dueDate - 2 * minRequiredTime
     * 2. Urgent milestone: dueDate - 1 * minRequiredTime
     * 3. Due Date milestone: dueDate
     * 4. Custom Reminder / Snooze / Rescheduled: task.remainderDate
     * If all reminders have passed or task is overdue: stays on due date until marked done.
     */
    fun calculateNextReminderInfo(
        task: Task,
        now: Long = System.currentTimeMillis()
    ): NextReminderInfo {
        if (task.isCompleted) {
            val completedTime = task.completedAt ?: task.dueDate ?: task.remainderDate ?: task.createdAt
            return NextReminderInfo(
                triggerTime = completedTime,
                displayLabel = "Done",
                isPastAllMilestones = true,
                milestoneType = MilestoneType.COMPLETED
            )
        }

        data class Candidate(val time: Long, val label: String, val type: MilestoneType)
        val candidates = mutableListOf<Candidate>()

        // 1. Explicit Remainder Date (Custom Snooze / Rescheduled Reminder)
        val remDate = task.remainderDate
        if (remDate != null && remDate > now) {
            candidates.add(Candidate(remDate, "Reminder", MilestoneType.CUSTOM_REMINDER))
        }

        // 2. Formula Milestones based on Due Date and Minimum Time Required
        val dueDate = task.dueDate
        if (dueDate != null) {
            val minMinutes = parseMinimumTimeToMinutes(task.minimumTimeRequired).coerceAtLeast(1L)
            val minMillis = minMinutes * 60 * 1000L

            val attentionTime = dueDate - (2 * minMillis)
            val urgentTime = dueDate - (1 * minMillis)

            if (attentionTime > now) {
                candidates.add(Candidate(attentionTime, "Attention", MilestoneType.ATTENTION))
            }
            if (urgentTime > now) {
                candidates.add(Candidate(urgentTime, "Urgent", MilestoneType.URGENT))
            }
            if (dueDate > now) {
                candidates.add(Candidate(dueDate, "Due", MilestoneType.DUE))
            }
        }

        val upcoming = candidates.filter { it.time > now }.sortedBy { it.time }

        if (upcoming.isNotEmpty()) {
            val next = upcoming.first()
            return NextReminderInfo(
                triggerTime = next.time,
                displayLabel = next.label,
                isPastAllMilestones = false,
                milestoneType = next.type
            )
        }

        // If all scheduled reminders have passed or task is overdue:
        // Always show on due date itself (or createdAt if no due date)
        val fallbackTime = task.dueDate ?: (task.remainderDate ?: task.createdAt)
        val isOverdue = task.dueDate != null && now >= task.dueDate!!

        return NextReminderInfo(
            triggerTime = fallbackTime,
            displayLabel = if (isOverdue) "Overdue" else "Due",
            isPastAllMilestones = true,
            milestoneType = if (isOverdue) MilestoneType.OVERDUE else MilestoneType.DUE
        )
    }
}
