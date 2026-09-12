package com.example.tascyn.data

/**
 * Task Status options matching Notion Task Manager
 * Groups:
 * - To do: Left, Not started
 * - In progress: In progress, Procrastinated
 * - Complete: Done
 */
enum class TaskStatus(val displayName: String, val group: StatusGroup, val formulaIndex: Int) {
    NOT_STARTED("Not started", StatusGroup.TO_DO, 1),
    IN_PROGRESS("In progress", StatusGroup.IN_PROGRESS, 2),
    PROCRASTINATED("Procrastinated", StatusGroup.IN_PROGRESS, 3),
    LEFT("Left", StatusGroup.TO_DO, 4),
    DONE("Done", StatusGroup.COMPLETE, 5);

    enum class StatusGroup(val displayName: String) {
        TO_DO("To do"),
        IN_PROGRESS("In progress"),
        COMPLETE("Complete")
    }

    companion object {
        fun fromString(value: String): TaskStatus {
            return values().find { it.displayName.equals(value, ignoreCase = true) } ?: NOT_STARTED
        }
    }
}

/**
 * Task Priority matching Notion Task Manager
 */
enum class TaskPriority(val displayName: String, val formulaIndex: Int) {
    HIGH("High", 1),
    MEDIUM("Medium", 2),
    LOW("Low", 3);

    companion object {
        fun fromString(value: String): TaskPriority {
            return values().find { it.displayName.equals(value, ignoreCase = true) } ?: MEDIUM
        }
    }
}

/**
 * Multi-select Task Types matching Notion schema
 */
enum class TaskType(val displayName: String, val badgeColorHex: String) {
    WORK("Work", "#292A2D"),
    PERSONAL("Personal", "#4A5568"),
    ACADEMIC("Academic", "#2B6CB0"),
    PROJECT("Project", "#2C7A7B"),
    SKILL("Skill", "#6B46C1"),
    BOOK_READING("Book Reading", "#805AD5"),
    SPIRITUAL("Spiritual", "#D69E2E"),
    HEALTH("Health", "#319795"),
    TRAVEL("Travel", "#DD6B20"),
    LAB("Lab", "#3182CE"),
    ASSIGNMENT("Assignment", "#C53030"),
    EXAM("Exam", "#9B2C2C");

    companion object {
        fun fromString(value: String): TaskType? {
            return values().find { it.displayName.equals(value, ignoreCase = true) }
        }
    }
}

/**
 * Working Session status for Timesheets Database
 */
enum class TimesheetStatus(val displayName: String) {
    NOT_STARTED("Not started"),
    IN_PROGRESS("In progress"),
    DONE("Done")
}

/**
 * Timesheet Session model (Database 2)
 */
data class TimesheetSession(
    val id: String,
    var title: String,
    var status: TimesheetStatus = TimesheetStatus.DONE,
    var startTime: Long? = null, // epoch millis
    var endTime: Long? = null,   // epoch millis
    var taskId: String? = null   // relation -> Task Manager
)

/**
 * Task model matching Database 1: Task Manager
 */
data class Task(
    val id: String,
    var title: String,
    var status: TaskStatus = TaskStatus.NOT_STARTED,
    var priority: TaskPriority = TaskPriority.MEDIUM,
    var taskTypes: MutableSet<TaskType> = mutableSetOf(),
    var comment: String = "",
    var minimumTimeRequired: String = "0d 1h 0m", // "1d 0h 0m" format
    var dueDate: Long? = null,       // epoch millis
    var remainderDate: Long? = null, // epoch millis for reminder & timeline
    var parentTaskId: String? = null, // Sub-task relation to parent
    var completedAt: Long? = null,    // epoch millis when completed
    val createdAt: Long = System.currentTimeMillis()
) {
    val isCompleted: Boolean
        get() = status == TaskStatus.DONE
}

/**
 * Result of Notion Formula 2.2: Time Left
 */
enum class UrgencyLevel(val formulaIndex: Int) {
    OVERDUE(1),
    URGENT(2),
    ATTENTION_NEEDED(3),
    ON_TRACK(4),
    NORMAL_TIME_LEFT(5),
    NO_DUE_DATE(0)
}

data class TimeLeftResult(
    val rawText: String,
    val urgencyLevel: UrgencyLevel,
    val minutesLeft: Long?,
    val formattedTime: String
)

/**
 * Result of Notion Formula 2.3: Quadrant
 */
data class QuadrantResult(
    val qNumber: Int,
    val action: String,
    val fullLabel: String,
    val urgencyIndex: Int,
    val priorityIndex: Int,
    val statusIndex: Int,
    val isValid: Boolean
)

/**
 * Active session tracking state with countdown
 */
data class ActiveSessionState(
    val session: TimesheetSession,
    val task: Task?,
    val elapsedSeconds: Long,
    val remainingSeconds: Long?, // countdown against task minimum time
    val isOvertime: Boolean
)
