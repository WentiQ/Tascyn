package com.example.tascyn.data

import android.content.Context
import android.content.SharedPreferences
import com.example.tascyn.domain.NotionFormulas
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

enum class NotionView(val displayName: String, val badgeText: String) {
    PENDING("Pending", "Sorted by Q#"),
    TODAY("Today", "Due / Remainder Today"),
    TOMORROW("Tomorrow", "Remainder Tomorrow"),
    THIS_WEEK("This Week", "Next 7 Days"),
    ACADEMIC("Academic", "Coursework & Labs"),
    COMPLETED("Completed", "Done & Left"),
    TIMELINE("Timeline", "Schedule View"),
    TIMESHEETS("Timesheets", "Live & History")
}

class TaskManagerRepository private constructor(context: Context?) {

    private var appContext: Context? = context?.applicationContext
    private var prefs: SharedPreferences? = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val tasks = mutableListOf<Task>()
    private val sessions = mutableListOf<TimesheetSession>()

    init {
        loadFromStorage()
    }

    companion object {
        private const val PREFS_NAME = "tascyn_tasks_storage"
        private const val KEY_TASKS_JSON = "saved_tasks_json"
        private const val KEY_SESSIONS_JSON = "saved_sessions_json"
        private const val KEY_INITIAL_SEEDED = "has_initial_seeded"
        private const val MAX_COMPLETED_TASKS = 30

        @Volatile
        private var instance: TaskManagerRepository? = null

        fun initialize(context: Context): TaskManagerRepository {
            return instance ?: synchronized(this) {
                instance ?: TaskManagerRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun get(): TaskManagerRepository {
            return instance ?: synchronized(this) {
                instance ?: TaskManagerRepository(null).also { instance = it }
            }
        }
    }

    fun attachContext(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadFromStorage()
        }
    }

    @Synchronized
    private fun loadFromStorage() {
        val sp = prefs
        if (sp == null) {
            if (tasks.isEmpty()) {
                seedInitialData()
            }
            return
        }

        val hasSeeded = sp.getBoolean(KEY_INITIAL_SEEDED, false)
        val tasksJsonStr = sp.getString(KEY_TASKS_JSON, null)
        val sessionsJsonStr = sp.getString(KEY_SESSIONS_JSON, null)

        if (!hasSeeded && tasksJsonStr.isNullOrBlank()) {
            tasks.clear()
            sessions.clear()
            seedInitialData()
            sp.edit().putBoolean(KEY_INITIAL_SEEDED, true).apply()
            saveToStorage()
            return
        }

        tasks.clear()
        if (!tasksJsonStr.isNullOrBlank()) {
            try {
                val arr = JSONArray(tasksJsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    tasks.add(taskFromJson(obj))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        sessions.clear()
        if (!sessionsJsonStr.isNullOrBlank()) {
            try {
                val arr = JSONArray(sessionsJsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    sessions.add(sessionFromJson(obj))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        enforceCompletedTaskLimit(shouldSave = false)
    }

    @Synchronized
    private fun saveToStorage() {
        val sp = prefs ?: return
        try {
            val tasksArr = JSONArray()
            for (t in tasks) {
                tasksArr.put(taskToJson(t))
            }

            val sessionsArr = JSONArray()
            for (s in sessions) {
                sessionsArr.put(sessionToJson(s))
            }

            sp.edit()
                .putString(KEY_TASKS_JSON, tasksArr.toString())
                .putString(KEY_SESSIONS_JSON, sessionsArr.toString())
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    private fun enforceCompletedTaskLimit(shouldSave: Boolean = true) {
        val completed = tasks.filter { it.status == TaskStatus.DONE }
        if (completed.size > MAX_COMPLETED_TASKS) {
            // Sort descending by completion time (most recent first)
            val sortedCompleted = completed.sortedByDescending {
                it.completedAt ?: it.remainderDate ?: it.createdAt
            }
            // Keep top 30 most recent completed tasks
            val toKeep = sortedCompleted.take(MAX_COMPLETED_TASKS).map { it.id }.toSet()
            // Identify oldest completed tasks exceeding the 30 limit to permanently delete
            val toDelete = completed.filter { !toKeep.contains(it.id) }.map { it.id }.toSet()

            tasks.removeAll { toDelete.contains(it.id) || (it.parentTaskId != null && toDelete.contains(it.parentTaskId)) }
            sessions.removeAll { it.taskId != null && toDelete.contains(it.taskId) }

            if (shouldSave) {
                saveToStorage()
            }
        }
    }

    private fun taskToJson(task: Task): JSONObject {
        val obj = JSONObject()
        obj.put("id", task.id)
        obj.put("title", task.title)
        obj.put("status", task.status.name)
        obj.put("priority", task.priority.name)
        val typesArr = JSONArray()
        task.taskTypes.forEach { typesArr.put(it.name) }
        obj.put("taskTypes", typesArr)
        obj.put("comment", task.comment)
        obj.put("minimumTimeRequired", task.minimumTimeRequired)
        if (task.dueDate != null) obj.put("dueDate", task.dueDate)
        if (task.remainderDate != null) obj.put("remainderDate", task.remainderDate)
        if (task.parentTaskId != null) obj.put("parentTaskId", task.parentTaskId)
        if (task.completedAt != null) obj.put("completedAt", task.completedAt)
        obj.put("createdAt", task.createdAt)
        return obj
    }

    private fun taskFromJson(obj: JSONObject): Task {
        val types = mutableSetOf<TaskType>()
        val typesArr = obj.optJSONArray("taskTypes")
        if (typesArr != null) {
            for (i in 0 until typesArr.length()) {
                val tName = typesArr.getString(i)
                try { types.add(TaskType.valueOf(tName)) } catch (e: Exception) {}
            }
        }
        return Task(
            id = obj.getString("id"),
            title = obj.optString("title", ""),
            status = try { TaskStatus.valueOf(obj.optString("status", TaskStatus.NOT_STARTED.name)) } catch (e: Exception) { TaskStatus.NOT_STARTED },
            priority = try { TaskPriority.valueOf(obj.optString("priority", TaskPriority.MEDIUM.name)) } catch (e: Exception) { TaskPriority.MEDIUM },
            taskTypes = types,
            comment = obj.optString("comment", ""),
            minimumTimeRequired = obj.optString("minimumTimeRequired", "0d 1h 0m"),
            dueDate = if (obj.has("dueDate")) obj.optLong("dueDate") else null,
            remainderDate = if (obj.has("remainderDate")) obj.optLong("remainderDate") else null,
            parentTaskId = if (obj.has("parentTaskId")) obj.optString("parentTaskId") else null,
            completedAt = if (obj.has("completedAt")) obj.optLong("completedAt") else null,
            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
        )
    }

    private fun sessionToJson(session: TimesheetSession): JSONObject {
        val obj = JSONObject()
        obj.put("id", session.id)
        obj.put("title", session.title)
        obj.put("status", session.status.name)
        if (session.startTime != null) obj.put("startTime", session.startTime)
        if (session.endTime != null) obj.put("endTime", session.endTime)
        if (session.taskId != null) obj.put("taskId", session.taskId)
        return obj
    }

    private fun sessionFromJson(obj: JSONObject): TimesheetSession {
        return TimesheetSession(
            id = obj.getString("id"),
            title = obj.optString("title", "Working Session"),
            status = try { TimesheetStatus.valueOf(obj.optString("status", TimesheetStatus.DONE.name)) } catch (e: Exception) { TimesheetStatus.DONE },
            startTime = if (obj.has("startTime")) obj.optLong("startTime") else null,
            endTime = if (obj.has("endTime")) obj.optLong("endTime") else null,
            taskId = if (obj.has("taskId")) obj.optString("taskId") else null
        )
    }

    private fun seedInitialData() {
        val now = System.currentTimeMillis()
        val oneHour = 3600 * 1000L
        val oneDay = 24 * 3600 * 1000L

        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 18)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        val todayEvening = cal.timeInMillis

        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 14)
        val tomorrowAfternoon = cal.timeInMillis

        cal.add(Calendar.DAY_OF_YEAR, 3)
        val inFourDays = cal.timeInMillis

        val t1 = Task(
            id = "task_traj",
            title = "Write trajectory solver implementation",
            status = TaskStatus.NOT_STARTED,
            priority = TaskPriority.MEDIUM,
            taskTypes = mutableSetOf(TaskType.ACADEMIC, TaskType.PROJECT),
            comment = "Implement Runge-Kutta 4th order trajectory numerical integration.",
            minimumTimeRequired = "0d 1h 0m",
            dueDate = inFourDays,
            remainderDate = inFourDays
        )

        val t2 = Task(
            id = "task_sim",
            title = "Test simulation algorithm with noise",
            status = TaskStatus.NOT_STARTED,
            priority = TaskPriority.MEDIUM,
            taskTypes = mutableSetOf(TaskType.LAB, TaskType.PROJECT),
            comment = "Add Gaussian white noise to sensor measurements.",
            minimumTimeRequired = "0d 0h 45m",
            dueDate = inFourDays + oneDay,
            remainderDate = inFourDays + oneDay
        )

        val t3 = Task(
            id = "task_subm",
            title = "Final submission to course portal",
            status = TaskStatus.NOT_STARTED,
            priority = TaskPriority.HIGH,
            taskTypes = mutableSetOf(TaskType.ASSIGNMENT),
            comment = "Export PDF report and zip archive.",
            minimumTimeRequired = "0d 0h 15m",
            dueDate = inFourDays + (2 * oneDay),
            remainderDate = inFourDays + (2 * oneDay)
        )

        val t4 = Task(
            id = "task_reading",
            title = "Read Chapter 4: Neural Control",
            status = TaskStatus.NOT_STARTED,
            priority = TaskPriority.MEDIUM,
            taskTypes = mutableSetOf(TaskType.BOOK_READING, TaskType.ACADEMIC),
            comment = "Review PID vs Adaptive Neural controllers.",
            minimumTimeRequired = "0d 1h 30m",
            dueDate = tomorrowAfternoon + (4 * oneHour),
            remainderDate = tomorrowAfternoon
        )

        val t5 = Task(
            id = "task_lab_report",
            title = "Complete lab report: Frequency Response",
            status = TaskStatus.NOT_STARTED,
            priority = TaskPriority.LOW,
            taskTypes = mutableSetOf(TaskType.LAB, TaskType.ACADEMIC),
            comment = "Bode plots and gain margins.",
            minimumTimeRequired = "0d 2h 0m",
            dueDate = inFourDays + (3 * oneDay),
            remainderDate = inFourDays + (2 * oneDay)
        )

        val t6 = Task(
            id = "task_ethics",
            title = "Submit Ethics Board Project Declaration",
            status = TaskStatus.NOT_STARTED,
            priority = TaskPriority.HIGH,
            taskTypes = mutableSetOf(TaskType.PROJECT, TaskType.WORK),
            comment = "Delayed declaration. Must submit immediately.",
            minimumTimeRequired = "0d 0h 45m",
            dueDate = now - (3 * oneHour),
            remainderDate = todayEvening - oneDay
        )

        val t7 = Task(
            id = "task_robotics_urgent",
            title = "Robotics Kinematics Control Simulation",
            status = TaskStatus.IN_PROGRESS,
            priority = TaskPriority.HIGH,
            taskTypes = mutableSetOf(TaskType.ACADEMIC, TaskType.LAB, TaskType.ASSIGNMENT),
            comment = "Need to verify inverse kinematics matrix before submission.",
            minimumTimeRequired = "0d 3h 0m",
            dueDate = now + (2 * oneHour),
            remainderDate = todayEvening
        )

        tasks.addAll(listOf(t1, t2, t3, t4, t5, t6, t7))
    }

    // Task operations
    @Synchronized
    fun getAllTasks(): List<Task> = tasks.toList()

    @Synchronized
    fun getTopLevelTasks(): List<Task> = tasks.filter { it.parentTaskId == null }

    @Synchronized
    fun getSubTasks(parentId: String): List<Task> = tasks.filter { it.parentTaskId == parentId }

    @Synchronized
    fun getTaskById(id: String): Task? = tasks.find { it.id == id }

    @Synchronized
    fun addTask(task: Task) {
        if (task.status == TaskStatus.DONE && task.completedAt == null) {
            task.completedAt = System.currentTimeMillis()
        }
        tasks.add(0, task)
        enforceCompletedTaskLimit()
        saveToStorage()
    }

    @Synchronized
    fun updateTask(task: Task) {
        if (task.status == TaskStatus.DONE && task.completedAt == null) {
            task.completedAt = System.currentTimeMillis()
        } else if (task.status != TaskStatus.DONE) {
            task.completedAt = null
        }
        val idx = tasks.indexOfFirst { it.id == task.id }
        if (idx >= 0) {
            tasks[idx] = task
        } else {
            tasks.add(0, task)
        }
        enforceCompletedTaskLimit()
        saveToStorage()
    }

    @Synchronized
    fun deleteTask(id: String) {
        tasks.removeAll { it.id == id || it.parentTaskId == id }
        sessions.removeAll { it.taskId == id }
        saveToStorage()
    }

    // Timesheet operations
    @Synchronized
    fun getAllSessions(): List<TimesheetSession> = sessions.sortedByDescending { it.startTime ?: 0L }

    @Synchronized
    fun getSessionsForTask(taskId: String): List<TimesheetSession> =
        sessions.filter { it.taskId == taskId }.sortedByDescending { it.startTime ?: 0L }

    @Synchronized
    fun getActiveSession(): TimesheetSession? =
        sessions.find { it.status == TimesheetStatus.IN_PROGRESS && it.startTime != null && it.endTime == null }

    @Synchronized
    fun startSession(taskId: String?, title: String = "Working Session"): TimesheetSession {
        endCurrentActiveSession()

        val newSession = TimesheetSession(
            id = "sess_" + System.currentTimeMillis(),
            title = title,
            status = TimesheetStatus.IN_PROGRESS,
            startTime = System.currentTimeMillis(),
            endTime = null,
            taskId = taskId
        )
        sessions.add(0, newSession)
        saveToStorage()
        return newSession
    }

    @Synchronized
    fun endCurrentActiveSession(): TimesheetSession? {
        val active = getActiveSession() ?: return null
        active.status = TimesheetStatus.DONE
        active.endTime = System.currentTimeMillis()
        saveToStorage()
        return active
    }

    @Synchronized
    fun endSession(sessionId: String): TimesheetSession? {
        val session = sessions.find { it.id == sessionId } ?: return null
        session.status = TimesheetStatus.DONE
        session.endTime = System.currentTimeMillis()
        saveToStorage()
        return session
    }

    // Active state with countdown calculation
    fun getActiveSessionState(): ActiveSessionState? {
        val active = getActiveSession() ?: return null
        val now = System.currentTimeMillis()
        val start = active.startTime ?: return null
        val elapsedSeconds = (now - start) / 1000L

        val task = active.taskId?.let { getTaskById(it) }
        val remainingSeconds: Long?
        val isOvertime: Boolean

        if (task != null && task.minimumTimeRequired.isNotBlank()) {
            val minMinutes = NotionFormulas.parseMinimumTimeToMinutes(task.minimumTimeRequired)
            val minSeconds = minMinutes * 60L
            val rem = minSeconds - elapsedSeconds
            if (rem <= 0) {
                remainingSeconds = -rem
                isOvertime = true
            } else {
                remainingSeconds = rem
                isOvertime = false
            }
        } else {
            remainingSeconds = null
            isOvertime = false
        }

        return ActiveSessionState(
            session = active,
            task = task,
            elapsedSeconds = elapsedSeconds,
            remainingSeconds = remainingSeconds,
            isOvertime = isOvertime
        )
    }

    // Filter by Notion Views
    @Synchronized
    fun getTasksForView(view: NotionView, now: Long = System.currentTimeMillis()): List<Task> {
        val all = tasks.toList()

        return when (view) {
            NotionView.PENDING -> {
                all.filter {
                    it.status == TaskStatus.NOT_STARTED ||
                    it.status == TaskStatus.IN_PROGRESS ||
                    it.status == TaskStatus.PROCRASTINATED
                }.sortedBy { task ->
                    val qRes = NotionFormulas.calculateQuadrant(task, now)
                    if (qRes.isValid) qRes.qNumber else 999
                }
            }

            NotionView.TODAY -> {
                val todayStart = getStartOfDay(now)
                val todayEnd = getEndOfDay(now)
                all.filter { task ->
                    val inStatus = task.status == TaskStatus.NOT_STARTED ||
                                   task.status == TaskStatus.IN_PROGRESS ||
                                   task.status == TaskStatus.PROCRASTINATED
                    val inToday = task.remainderDate != null &&
                                  task.remainderDate!! in todayStart..todayEnd
                    inStatus && inToday
                }.sortedBy { it.remainderDate ?: Long.MAX_VALUE }
            }

            NotionView.TOMORROW -> {
                val tomorrowStart = getStartOfDay(now + 24 * 3600 * 1000L)
                val tomorrowEnd = getEndOfDay(now + 24 * 3600 * 1000L)
                all.filter { task ->
                    task.remainderDate != null &&
                    task.remainderDate!! in tomorrowStart..tomorrowEnd
                }.sortedBy { it.remainderDate ?: Long.MAX_VALUE }
            }

            NotionView.THIS_WEEK -> {
                val weekStart = getStartOfDay(now)
                val weekEnd = getEndOfDay(now + 7 * 24 * 3600 * 1000L)
                all.filter { task ->
                    val inStatus = task.status == TaskStatus.NOT_STARTED ||
                                   task.status == TaskStatus.IN_PROGRESS ||
                                   task.status == TaskStatus.PROCRASTINATED
                    val inWeek = task.remainderDate != null &&
                                 task.remainderDate!! in weekStart..weekEnd
                    inStatus && inWeek
                }.sortedBy { it.remainderDate ?: Long.MAX_VALUE }
            }

            NotionView.ACADEMIC -> {
                all.filter { task ->
                    val inStatus = task.status == TaskStatus.NOT_STARTED ||
                                   task.status == TaskStatus.IN_PROGRESS ||
                                   task.status == TaskStatus.PROCRASTINATED
                    val isAcademic = task.taskTypes.contains(TaskType.ACADEMIC) ||
                                     task.taskTypes.contains(TaskType.LAB) ||
                                     task.taskTypes.contains(TaskType.ASSIGNMENT) ||
                                     task.taskTypes.contains(TaskType.EXAM)
                    inStatus && isAcademic
                }.sortedBy { task ->
                    val qRes = NotionFormulas.calculateQuadrant(task, now)
                    if (qRes.isValid) qRes.qNumber else 999
                }
            }

            NotionView.COMPLETED -> {
                // Return up to 30 most recently completed tasks
                all.filter {
                    it.status == TaskStatus.DONE || it.status == TaskStatus.LEFT
                }.sortedByDescending {
                    it.completedAt ?: it.remainderDate ?: it.createdAt
                }.take(MAX_COMPLETED_TASKS)
            }

            NotionView.TIMELINE -> {
                all.filter {
                    it.status != TaskStatus.DONE
                }.sortedBy { it.remainderDate ?: it.dueDate ?: Long.MAX_VALUE }
            }

            NotionView.TIMESHEETS -> {
                emptyList()
            }
        }
    }

    private fun getStartOfDay(timeMillis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMillis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun getEndOfDay(timeMillis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMillis
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.timeInMillis
    }
}
