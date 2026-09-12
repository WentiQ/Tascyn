package com.example.tascyn

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.tascyn.data.*
import com.example.tascyn.domain.GeminiAiService
import com.example.tascyn.domain.NotionFormulas
import com.example.tascyn.domain.TaskParseResult
import com.example.tascyn.receiver.TaskAlarmScheduler
import com.example.tascyn.ui.adapter.*
import com.example.tascyn.ui.components.InterlockingGeometryView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import java.text.SimpleDateFormat
import java.util.*

enum class AppNavTab {
    TODAY,
    TASKS,
    TIMELINE,
    SESSIONS,
    AI
}

enum class TasksTimeTab {
    PENDING,
    TODAY,
    TOMORROW,
    THIS_WEEK,
    COMPLETED,
    ALL
}

class MainActivity : AppCompatActivity() {

    private val repository = TaskManagerRepository.get()
    private var currentTab: AppNavTab = AppNavTab.TODAY
    private var selectedTasksTimeTab: TasksTimeTab = TasksTimeTab.PENDING
    private var selectedTaskTypeFilter: TaskType? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Notification permission granted!", Toast.LENGTH_SHORT).show()
            TaskAlarmScheduler.scheduleAllAlarms(this)
        } else {
            Toast.makeText(this, "Notification permission denied. Alarms may not show heads-up banners.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isNotificationPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun isExactAlarmPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.canScheduleExactAlarms() ?: true
        } else {
            true
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(packageName) ?: true
        } else {
            true
        }
    }

    private var speechPromptCallback: ((String) -> Unit)? = null
    private val speechRecognizerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                val spokenText = matches[0]
                speechPromptCallback?.invoke(spokenText)
            }
        }
    }

    private fun launchSpeechInput(onSpoken: (String) -> Unit) {
        speechPromptCallback = onSpoken
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe your task...")
        }
        try {
            speechRecognizerLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice recognition not available on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    // Top Header
    private lateinit var layoutTopHeader: LinearLayout

    // Header & Greeting Views
    private lateinit var txtGreetingHeadline: TextView
    private lateinit var txtGreetingDate: TextView

    // Attention Summary Card
    private lateinit var txtAttentionHeadline: TextView
    private lateinit var txtUrgentCountLabel: TextView
    private lateinit var txtAttentionCountLabel: TextView
    private lateinit var txtOnTrackCountLabel: TextView

    // Today Section Recyclers & Headers
    private lateinit var scrollTodayView: NestedScrollView
    private lateinit var headerSectionNow: View
    private lateinit var headerSectionNext: View
    private lateinit var headerSectionLater: View
    private lateinit var recyclerSectionNow: RecyclerView
    private lateinit var recyclerSectionNext: RecyclerView
    private lateinit var recyclerSectionLater: RecyclerView

    private lateinit var adapterNow: TodaySectionTaskAdapter
    private lateinit var adapterNext: TodaySectionTaskAdapter
    private lateinit var adapterLater: TodaySectionTaskAdapter

    // Tasks Page Views
    private lateinit var layoutTasksPageView: LinearLayout
    private lateinit var txtTasksPendingCount: TextView

    private lateinit var tabFilterPending: LinearLayout
    private lateinit var tabFilterToday: LinearLayout
    private lateinit var tabFilterTomorrow: LinearLayout
    private lateinit var tabFilterThisWeek: LinearLayout
    private lateinit var tabFilterCompleted: LinearLayout
    private lateinit var tabFilterAll: LinearLayout

    private lateinit var lblTabPending: TextView
    private lateinit var lblTabToday: TextView
    private lateinit var lblTabTomorrow: TextView
    private lateinit var lblTabThisWeek: TextView
    private lateinit var lblTabCompleted: TextView
    private lateinit var lblTabAll: TextView

    private lateinit var indicatorTabPending: View
    private lateinit var indicatorTabToday: View
    private lateinit var indicatorTabTomorrow: View
    private lateinit var indicatorTabThisWeek: View
    private lateinit var indicatorTabCompleted: View
    private lateinit var indicatorTabAll: View

    private lateinit var chipCatAll: TextView
    private lateinit var chipCatAcademic: TextView
    private lateinit var chipCatAssignment: TextView
    private lateinit var chipCatProject: TextView
    private lateinit var chipCatPersonal: TextView

    private lateinit var recyclerTasksGrouped: RecyclerView
    private lateinit var tasksGroupedAdapter: QuadrantGroupedTaskAdapter

    // Notion Timeline Page Views
    private lateinit var layoutTimelinePageView: LinearLayout
    private lateinit var viewNotionTimelineGantt: com.example.tascyn.ui.components.NotionTimelineGanttView
    private lateinit var scrollTimelineGanttHorizontal: HorizontalScrollView
    private lateinit var txtTimelineMonthHeader: TextView
    private lateinit var btnTimelinePrevMonth: ImageView
    private lateinit var btnTimelineNextMonth: ImageView
    private lateinit var btnTimelineGoToToday: TextView
    private lateinit var btnTimelineNewTask: LinearLayout
    private lateinit var btnTimelineViewSelector: LinearLayout
    private lateinit var txtTimelineViewName: TextView
    private lateinit var btnTimelineBack: ImageView
    private lateinit var btnTimelineShare: ImageView
    private lateinit var btnTimelineMore: TextView
    private lateinit var btnTimelineSearch: ImageView
    private lateinit var btnTimelineFilter: ImageView
    private lateinit var btnTimelineSort: ImageView
    private lateinit var btnTimelineZoomGranularity: LinearLayout
    private lateinit var txtTimelineGranularity: TextView

    private var currentTimelineView: NotionView = NotionView.TIMELINE

    // Sessions Page Views
    private lateinit var layoutSessionsPageView: NestedScrollView
    private lateinit var layoutInactiveSessionCard: LinearLayout
    private lateinit var layoutActiveSessionCard: LinearLayout
    private lateinit var txtSessionLiveTimer: TextView
    private lateinit var txtSessionActiveTaskTitle: TextView
    private lateinit var txtSessionActiveCountdown: TextView
    private lateinit var btnEndActiveSessionMain: Button
    private lateinit var txtTodaySessionsTotalDuration: TextView
    private lateinit var txtNoTodaySessions: TextView
    private lateinit var recyclerTodaySessionsList: RecyclerView
    private lateinit var recyclerStartWorkingOnTasks: RecyclerView

    private lateinit var todaySessionsAdapter: TimesheetAdapter
    private lateinit var startWorkingTaskAdapter: StartWorkingTaskAdapter

    private val sessionTickerHandler = Handler(Looper.getMainLooper())
    private val sessionTickerRunnable = object : Runnable {
        override fun run() {
            updateLiveSessionUi()
            sessionTickerHandler.postDelayed(this, 1000L)
        }
    }

    // Bottom Navigation Views
    private lateinit var tabNavToday: View
    private lateinit var tabNavTasks: View
    private lateinit var tabNavTimeline: View
    private lateinit var tabNavSessions: View
    private lateinit var tabNavAi: View

    private lateinit var pillNavToday: FrameLayout
    private lateinit var pillNavTasks: FrameLayout
    private lateinit var pillNavTimeline: FrameLayout
    private lateinit var pillNavSessions: FrameLayout
    private lateinit var pillNavAi: FrameLayout

    private lateinit var iconNavToday: ImageView
    private lateinit var iconNavTasks: ImageView
    private lateinit var iconNavTimeline: ImageView
    private lateinit var iconNavSessions: ImageView
    private lateinit var viewNavAiGeometry: com.example.tascyn.ui.components.InterlockingGeometryView

    private lateinit var labelNavToday: TextView
    private lateinit var labelNavTasks: TextView
    private lateinit var labelNavTimeline: TextView
    private lateinit var labelNavSessions: TextView
    private lateinit var labelNavAi: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository.attachContext(this)
        TaskAlarmScheduler.createNotificationChannels(this)
        TaskAlarmScheduler.scheduleAllAlarms(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isNotificationPermissionGranted()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        initViews()
        setupAdapters()
        setupTasksPageFilters()
        setupBottomNavigation()
        refreshData()
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionTickerHandler.removeCallbacks(sessionTickerRunnable)
    }

    private fun initViews() {
        layoutTopHeader = findViewById(R.id.layoutTopHeader)
        txtGreetingHeadline = findViewById(R.id.txtGreetingHeadline)
        txtGreetingDate = findViewById(R.id.txtGreetingDate)

        txtAttentionHeadline = findViewById(R.id.txtAttentionHeadline)
        txtUrgentCountLabel = findViewById(R.id.txtUrgentCountLabel)
        txtAttentionCountLabel = findViewById(R.id.txtAttentionCountLabel)
        txtOnTrackCountLabel = findViewById(R.id.txtOnTrackCountLabel)

        scrollTodayView = findViewById(R.id.scrollTodayView)
        headerSectionNow = findViewById(R.id.headerSectionNow)
        headerSectionNext = findViewById(R.id.headerSectionNext)
        headerSectionLater = findViewById(R.id.headerSectionLater)

        recyclerSectionNow = findViewById(R.id.recyclerSectionNow)
        recyclerSectionNext = findViewById(R.id.recyclerSectionNext)
        recyclerSectionLater = findViewById(R.id.recyclerSectionLater)

        layoutTasksPageView = findViewById(R.id.layoutTasksPageView)
        txtTasksPendingCount = findViewById(R.id.txtTasksPendingCount)

        tabFilterPending = findViewById(R.id.tabFilterPending)
        tabFilterToday = findViewById(R.id.tabFilterToday)
        tabFilterTomorrow = findViewById(R.id.tabFilterTomorrow)
        tabFilterThisWeek = findViewById(R.id.tabFilterThisWeek)
        tabFilterCompleted = findViewById(R.id.tabFilterCompleted)
        tabFilterAll = findViewById(R.id.tabFilterAll)

        lblTabPending = findViewById(R.id.lblTabPending)
        lblTabToday = findViewById(R.id.lblTabToday)
        lblTabTomorrow = findViewById(R.id.lblTabTomorrow)
        lblTabThisWeek = findViewById(R.id.lblTabThisWeek)
        lblTabCompleted = findViewById(R.id.lblTabCompleted)
        lblTabAll = findViewById(R.id.lblTabAll)

        indicatorTabPending = findViewById(R.id.indicatorTabPending)
        indicatorTabToday = findViewById(R.id.indicatorTabToday)
        indicatorTabTomorrow = findViewById(R.id.indicatorTabTomorrow)
        indicatorTabThisWeek = findViewById(R.id.indicatorTabThisWeek)
        indicatorTabCompleted = findViewById(R.id.indicatorTabCompleted)
        indicatorTabAll = findViewById(R.id.indicatorTabAll)

        chipCatAll = findViewById(R.id.chipCatAll)
        chipCatAcademic = findViewById(R.id.chipCatAcademic)
        chipCatAssignment = findViewById(R.id.chipCatAssignment)
        chipCatProject = findViewById(R.id.chipCatProject)
        chipCatPersonal = findViewById(R.id.chipCatPersonal)

        recyclerTasksGrouped = findViewById(R.id.recyclerTasksGrouped)

        // Notion Timeline Page Views Binding
        layoutTimelinePageView = findViewById(R.id.layoutTimelinePageView)
        viewNotionTimelineGantt = findViewById(R.id.viewNotionTimelineGantt)
        scrollTimelineGanttHorizontal = findViewById(R.id.scrollTimelineGanttHorizontal)
        txtTimelineMonthHeader = findViewById(R.id.txtTimelineMonthHeader)
        btnTimelinePrevMonth = findViewById(R.id.btnTimelinePrevMonth)
        btnTimelineNextMonth = findViewById(R.id.btnTimelineNextMonth)
        btnTimelineGoToToday = findViewById(R.id.btnTimelineGoToToday)
        btnTimelineNewTask = findViewById(R.id.btnTimelineNewTask)
        btnTimelineViewSelector = findViewById(R.id.btnTimelineViewSelector)
        txtTimelineViewName = findViewById(R.id.txtTimelineViewName)
        btnTimelineBack = findViewById(R.id.btnTimelineBack)
        btnTimelineShare = findViewById(R.id.btnTimelineShare)
        btnTimelineMore = findViewById(R.id.btnTimelineMore)
        btnTimelineSearch = findViewById(R.id.btnTimelineSearch)
        btnTimelineFilter = findViewById(R.id.btnTimelineFilter)
        btnTimelineSort = findViewById(R.id.btnTimelineSort)
        btnTimelineZoomGranularity = findViewById(R.id.btnTimelineZoomGranularity)
        txtTimelineGranularity = findViewById(R.id.txtTimelineGranularity)

        btnTimelinePrevMonth.setOnClickListener { viewNotionTimelineGantt.prevMonth() }
        btnTimelineNextMonth.setOnClickListener { viewNotionTimelineGantt.nextMonth() }
        btnTimelineGoToToday.setOnClickListener {
            viewNotionTimelineGantt.goToToday()
            scrollTimelineToToday()
        }
        btnTimelineNewTask.setOnClickListener { showTaskDetailBottomSheet(null) }
        btnTimelineBack.setOnClickListener { selectTab(AppNavTab.TODAY) }
        btnTimelineShare.setOnClickListener { Toast.makeText(this, "Schedule exported", Toast.LENGTH_SHORT).show() }
        btnTimelineMore.setOnClickListener { showTimelineOptionsMenu(it) }
        btnTimelineSearch.setOnClickListener { selectTab(AppNavTab.TASKS) }
        btnTimelineFilter.setOnClickListener { showTimelineFilterMenu(it) }
        btnTimelineSort.setOnClickListener { showTimelineSortMenu(it) }
        btnTimelineViewSelector.setOnClickListener { showTimelineViewSelectorMenu(it) }
        btnTimelineZoomGranularity.setOnClickListener { showTimelineZoomMenu(it) }

        // Sessions Page View Bindings
        layoutSessionsPageView = findViewById(R.id.layoutSessionsPageView)
        layoutInactiveSessionCard = findViewById(R.id.layoutInactiveSessionCard)
        layoutActiveSessionCard = findViewById(R.id.layoutActiveSessionCard)
        txtSessionLiveTimer = findViewById(R.id.txtSessionLiveTimer)
        txtSessionActiveTaskTitle = findViewById(R.id.txtSessionActiveTaskTitle)
        txtSessionActiveCountdown = findViewById(R.id.txtSessionActiveCountdown)
        btnEndActiveSessionMain = findViewById(R.id.btnEndActiveSessionMain)
        txtTodaySessionsTotalDuration = findViewById(R.id.txtTodaySessionsTotalDuration)
        txtNoTodaySessions = findViewById(R.id.txtNoTodaySessions)
        recyclerTodaySessionsList = findViewById(R.id.recyclerTodaySessionsList)
        recyclerStartWorkingOnTasks = findViewById(R.id.recyclerStartWorkingOnTasks)

        btnEndActiveSessionMain.setOnClickListener {
            repository.endCurrentActiveSession()
            Toast.makeText(this, "Session ended and logged.", Toast.LENGTH_SHORT).show()
            refreshSessionsPageView()
            refreshData()
        }

        // Header Action Buttons
        findViewById<View>(R.id.btnHeaderSettings).setOnClickListener {
            showSettingsBottomSheet()
        }
        findViewById<View>(R.id.btnHeaderNotifications).setOnClickListener {
            Toast.makeText(this, "2 urgent reminders pending for today", Toast.LENGTH_SHORT).show()
        }

        // Floating Action Button (+)
        findViewById<View>(R.id.btnFloatingAdd).setOnClickListener {
            showTaskDetailBottomSheet(null)
        }
    }

    private fun setupAdapters() {
        // Today Section Adapters
        adapterNow = TodaySectionTaskAdapter(
            category = TaskUrgencyCategory.NOW,
            onTaskClicked = { task -> showTaskDetailBottomSheet(task) },
            onTaskCheckToggled = { task -> toggleTaskStatus(task) },
            onStartSession = { task -> startWorkSessionForTask(task) }
        )

        adapterNext = TodaySectionTaskAdapter(
            category = TaskUrgencyCategory.NEXT,
            onTaskClicked = { task -> showTaskDetailBottomSheet(task) },
            onTaskCheckToggled = { task -> toggleTaskStatus(task) },
            onStartSession = { task -> startWorkSessionForTask(task) }
        )

        adapterLater = TodaySectionTaskAdapter(
            category = TaskUrgencyCategory.LATER,
            onTaskClicked = { task -> showTaskDetailBottomSheet(task) },
            onTaskCheckToggled = { task -> toggleTaskStatus(task) },
            onStartSession = { task -> startWorkSessionForTask(task) }
        )

        recyclerSectionNow.layoutManager = LinearLayoutManager(this)
        recyclerSectionNow.adapter = adapterNow

        recyclerSectionNext.layoutManager = LinearLayoutManager(this)
        recyclerSectionNext.adapter = adapterNext

        recyclerSectionLater.layoutManager = LinearLayoutManager(this)
        recyclerSectionLater.adapter = adapterLater

        // Tasks Page Grouped Adapter
        tasksGroupedAdapter = QuadrantGroupedTaskAdapter(
            onTaskClicked = { task -> showTaskDetailBottomSheet(task) },
            onTaskCheckToggled = { task -> toggleTaskStatus(task) },
            onStartSession = { task -> startWorkSessionForTask(task) }
        )
        recyclerTasksGrouped.layoutManager = LinearLayoutManager(this)
        recyclerTasksGrouped.adapter = tasksGroupedAdapter

        // Timeline Gantt Setup
        viewNotionTimelineGantt.onTaskClicked = { task -> showTaskDetailBottomSheet(task) }
        viewNotionTimelineGantt.onNewTaskClicked = { dateMillis ->
            val newTask = Task(
                id = "task_" + System.currentTimeMillis(),
                title = "",
                dueDate = dateMillis,
                remainderDate = dateMillis
            )
            showTaskDetailBottomSheet(newTask)
        }
        viewNotionTimelineGantt.onMonthChanged = { monthStr ->
            txtTimelineMonthHeader.text = monthStr
        }
        viewNotionTimelineGantt.onRequestScrollTo = { targetX ->
            scrollTimelineGanttHorizontal.smoothScrollTo(targetX, 0)
        }

        scrollTimelineGanttHorizontal.viewTreeObserver.addOnScrollChangedListener {
            viewNotionTimelineGantt.setViewport(
                scrollTimelineGanttHorizontal.scrollX,
                scrollTimelineGanttHorizontal.width
            )
        }

        // Sessions Page Adapters
        todaySessionsAdapter = TimesheetAdapter(
            onSessionClicked = { showTimesheetsBottomSheet() }
        )
        recyclerTodaySessionsList.layoutManager = LinearLayoutManager(this)
        recyclerTodaySessionsList.adapter = todaySessionsAdapter

        startWorkingTaskAdapter = StartWorkingTaskAdapter(
            onTaskClicked = { task -> showTaskDetailBottomSheet(task) },
            onStartWorking = { task ->
                startWorkSessionForTask(task)
                refreshSessionsPageView()
            }
        )
        recyclerStartWorkingOnTasks.layoutManager = LinearLayoutManager(this)
        recyclerStartWorkingOnTasks.adapter = startWorkingTaskAdapter
    }

    private fun setupTasksPageFilters() {
        tabFilterPending.setOnClickListener { selectTasksTimeTab(TasksTimeTab.PENDING) }
        tabFilterToday.setOnClickListener { selectTasksTimeTab(TasksTimeTab.TODAY) }
        tabFilterTomorrow.setOnClickListener { selectTasksTimeTab(TasksTimeTab.TOMORROW) }
        tabFilterThisWeek.setOnClickListener { selectTasksTimeTab(TasksTimeTab.THIS_WEEK) }
        tabFilterCompleted.setOnClickListener { selectTasksTimeTab(TasksTimeTab.COMPLETED) }
        tabFilterAll.setOnClickListener { selectTasksTimeTab(TasksTimeTab.ALL) }

        chipCatAll.setOnClickListener { selectCategoryFilter(null) }
        chipCatAcademic.setOnClickListener { selectCategoryFilter(TaskType.ACADEMIC) }
        chipCatAssignment.setOnClickListener { selectCategoryFilter(TaskType.ASSIGNMENT) }
        chipCatProject.setOnClickListener { selectCategoryFilter(TaskType.PROJECT) }
        chipCatPersonal.setOnClickListener { selectCategoryFilter(TaskType.PERSONAL) }
    }

    private fun selectTasksTimeTab(tab: TasksTimeTab) {
        selectedTasksTimeTab = tab
        val activeColor = Color.parseColor("#0E0E10")
        val inactiveColor = Color.parseColor("#6B7280")

        lblTabPending.setTextColor(if (tab == TasksTimeTab.PENDING) activeColor else inactiveColor)
        lblTabPending.setTypeface(null, if (tab == TasksTimeTab.PENDING) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        indicatorTabPending.setBackgroundColor(if (tab == TasksTimeTab.PENDING) activeColor else Color.TRANSPARENT)

        lblTabToday.setTextColor(if (tab == TasksTimeTab.TODAY) activeColor else inactiveColor)
        lblTabToday.setTypeface(null, if (tab == TasksTimeTab.TODAY) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        indicatorTabToday.setBackgroundColor(if (tab == TasksTimeTab.TODAY) activeColor else Color.TRANSPARENT)

        lblTabTomorrow.setTextColor(if (tab == TasksTimeTab.TOMORROW) activeColor else inactiveColor)
        lblTabTomorrow.setTypeface(null, if (tab == TasksTimeTab.TOMORROW) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        indicatorTabTomorrow.setBackgroundColor(if (tab == TasksTimeTab.TOMORROW) activeColor else Color.TRANSPARENT)

        lblTabThisWeek.setTextColor(if (tab == TasksTimeTab.THIS_WEEK) activeColor else inactiveColor)
        lblTabThisWeek.setTypeface(null, if (tab == TasksTimeTab.THIS_WEEK) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        indicatorTabThisWeek.setBackgroundColor(if (tab == TasksTimeTab.THIS_WEEK) activeColor else Color.TRANSPARENT)

        lblTabCompleted.setTextColor(if (tab == TasksTimeTab.COMPLETED) activeColor else inactiveColor)
        lblTabCompleted.setTypeface(null, if (tab == TasksTimeTab.COMPLETED) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        indicatorTabCompleted.setBackgroundColor(if (tab == TasksTimeTab.COMPLETED) activeColor else Color.TRANSPARENT)

        lblTabAll.setTextColor(if (tab == TasksTimeTab.ALL) activeColor else inactiveColor)
        lblTabAll.setTypeface(null, if (tab == TasksTimeTab.ALL) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        indicatorTabAll.setBackgroundColor(if (tab == TasksTimeTab.ALL) activeColor else Color.TRANSPARENT)

        refreshTasksPageView()
    }

    private fun selectCategoryFilter(type: TaskType?) {
        selectedTaskTypeFilter = type

        fun updateChip(chip: TextView, isSelected: Boolean) {
            if (isSelected) {
                chip.setBackgroundResource(R.drawable.bg_fab_dark)
                chip.setTextColor(Color.WHITE)
            } else {
                chip.setBackgroundResource(R.drawable.bg_ai_chip)
                chip.setTextColor(Color.parseColor("#4B5563"))
            }
        }

        updateChip(chipCatAll, type == null)
        updateChip(chipCatAcademic, type == TaskType.ACADEMIC)
        updateChip(chipCatAssignment, type == TaskType.ASSIGNMENT)
        updateChip(chipCatProject, type == TaskType.PROJECT)
        updateChip(chipCatPersonal, type == TaskType.PERSONAL)

        refreshTasksPageView()
    }

    private fun refreshTasksPageView() {
        val now = System.currentTimeMillis()
        val pendingCount = repository.getTasksForView(NotionView.PENDING, now).size
        txtTasksPendingCount.text = "$pendingCount pending"

        val baseTasks = when (selectedTasksTimeTab) {
            TasksTimeTab.PENDING -> repository.getTasksForView(NotionView.PENDING, now)
            TasksTimeTab.TODAY -> repository.getTasksForView(NotionView.TODAY, now)
            TasksTimeTab.TOMORROW -> repository.getTasksForView(NotionView.TOMORROW, now)
            TasksTimeTab.THIS_WEEK -> repository.getTasksForView(NotionView.THIS_WEEK, now)
            TasksTimeTab.COMPLETED -> repository.getTasksForView(NotionView.COMPLETED, now)
            TasksTimeTab.ALL -> repository.getTopLevelTasks()
        }

        val filteredTasks = if (selectedTaskTypeFilter == null) {
            baseTasks
        } else {
            baseTasks.filter { task ->
                task.taskTypes.contains(selectedTaskTypeFilter) ||
                (selectedTaskTypeFilter == TaskType.ACADEMIC && (task.taskTypes.contains(TaskType.LAB) || task.taskTypes.contains(TaskType.ASSIGNMENT) || task.taskTypes.contains(TaskType.EXAM)))
            }
        }

        // Group by Quadrant
        val grouped = filteredTasks.groupBy { task ->
            NotionFormulas.calculateQuadrant(task, now)
        }.toSortedMap(compareBy { if (it.isValid) it.qNumber else 999 })

        val items = mutableListOf<QuadrantListItem>()
        for ((qResult, taskList) in grouped) {
            val (headerColor, urgencyCat) = when {
                qResult.qNumber in 1..15 -> Pair("#EF4444", TaskUrgencyCategory.NOW)
                qResult.qNumber in 16..45 -> Pair("#F59E0B", TaskUrgencyCategory.NEXT)
                else -> Pair("#10B981", TaskUrgencyCategory.LATER)
            }

            val title = if (qResult.isValid) "• Q${qResult.qNumber} · ${qResult.action}" else "• Unscheduled / Other"
            items.add(QuadrantListItem.Header(title = title, colorHex = headerColor))

            for (task in taskList) {
                items.add(QuadrantListItem.TaskCard(task = task, category = urgencyCat))
            }
        }

        tasksGroupedAdapter.submitList(items)
    }

    private fun updateLiveSessionUi() {
        val activeState = repository.getActiveSessionState()
        if (activeState == null) {
            layoutInactiveSessionCard.visibility = View.VISIBLE
            layoutActiveSessionCard.visibility = View.GONE
        } else {
            layoutInactiveSessionCard.visibility = View.GONE
            layoutActiveSessionCard.visibility = View.VISIBLE

            val elapsedH = activeState.elapsedSeconds / 3600L
            val elapsedM = (activeState.elapsedSeconds % 3600L) / 60L
            val elapsedS = activeState.elapsedSeconds % 60L
            txtSessionLiveTimer.text = String.format("%02d:%02d:%02d", elapsedH, elapsedM, elapsedS)
            txtSessionActiveTaskTitle.text = activeState.task?.title ?: activeState.session.title

            if (activeState.remainingSeconds != null) {
                val remH = activeState.remainingSeconds / 3600L
                val remM = (activeState.remainingSeconds % 3600L) / 60L
                val remS = activeState.remainingSeconds % 60L
                val formatted = String.format("%02d:%02d:%02d", remH, remM, remS)
                txtSessionActiveCountdown.text = if (activeState.isOvertime) "Overtime: $formatted" else "Remaining: $formatted"
                txtSessionActiveCountdown.setTextColor(Color.parseColor(if (activeState.isOvertime) "#EF4444" else "#6B7280"))
            } else {
                txtSessionActiveCountdown.text = "No minimum time set"
                txtSessionActiveCountdown.setTextColor(Color.parseColor("#6B7280"))
            }
        }
    }

    private fun refreshSessionsPageView() {
        updateLiveSessionUi()

        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis

        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val todayEnd = cal.timeInMillis

        // Today's completed sessions
        val todaySessions = repository.getAllSessions().filter {
            it.status == TimesheetStatus.DONE &&
            it.startTime != null &&
            it.startTime!! in todayStart..todayEnd
        }

        val totalMinutes = todaySessions.sumOf { s ->
            val start = s.startTime
            val end = s.endTime
            if (start != null && end != null) {
                ((end - start) / (1000L * 60L)).coerceAtLeast(0L)
            } else 0L
        }

        val totalDurationStr = if (totalMinutes >= 60) {
            val h = totalMinutes / 60L
            val m = totalMinutes % 60L
            if (m > 0) "${h}h ${m}m" else "${h}h"
        } else {
            "${totalMinutes}m"
        }
        txtTodaySessionsTotalDuration.text = "Total today: $totalDurationStr"

        if (todaySessions.isEmpty()) {
            txtNoTodaySessions.visibility = View.VISIBLE
            recyclerTodaySessionsList.visibility = View.GONE
        } else {
            txtNoTodaySessions.visibility = View.GONE
            recyclerTodaySessionsList.visibility = View.VISIBLE
            todaySessionsAdapter.submitList(todaySessions)
        }

        // Start Working On Pending Tasks
        val pendingTasks = repository.getTasksForView(NotionView.PENDING, now)
        startWorkingTaskAdapter.submitList(pendingTasks)
    }

    private fun setupBottomNavigation() {
        tabNavToday = findViewById(R.id.tabNavToday)
        tabNavTasks = findViewById(R.id.tabNavTasks)
        tabNavTimeline = findViewById(R.id.tabNavTimeline)
        tabNavSessions = findViewById(R.id.tabNavSessions)
        tabNavAi = findViewById(R.id.tabNavAi)

        pillNavToday = findViewById(R.id.pillNavToday)
        pillNavTasks = findViewById(R.id.pillNavTasks)
        pillNavTimeline = findViewById(R.id.pillNavTimeline)
        pillNavSessions = findViewById(R.id.pillNavSessions)
        pillNavAi = findViewById(R.id.pillNavAi)

        iconNavToday = findViewById(R.id.iconNavToday)
        iconNavTasks = findViewById(R.id.iconNavTasks)
        iconNavTimeline = findViewById(R.id.iconNavTimeline)
        iconNavSessions = findViewById(R.id.iconNavSessions)
        viewNavAiGeometry = findViewById(R.id.viewNavAiGeometry)
        viewNavAiGeometry.isDarkBackground = true

        labelNavToday = findViewById(R.id.labelNavToday)
        labelNavTasks = findViewById(R.id.labelNavTasks)
        labelNavTimeline = findViewById(R.id.labelNavTimeline)
        labelNavSessions = findViewById(R.id.labelNavSessions)
        labelNavAi = findViewById(R.id.labelNavAi)

        tabNavToday.setOnClickListener { selectTab(AppNavTab.TODAY) }
        tabNavTasks.setOnClickListener { selectTab(AppNavTab.TASKS) }
        tabNavTimeline.setOnClickListener { selectTab(AppNavTab.TIMELINE) }
        tabNavSessions.setOnClickListener { selectTab(AppNavTab.SESSIONS) }
        tabNavAi.setOnClickListener { showAiNaturalLanguageBottomSheet() }
    }

    private fun selectTab(tab: AppNavTab) {
        currentTab = tab
        resetNavUi()

        when (tab) {
            AppNavTab.TODAY -> {
                pillNavToday.setBackgroundResource(R.drawable.bg_nav_selected_pill)
                iconNavToday.setColorFilter(Color.parseColor("#4F46E5"))
                labelNavToday.setTextColor(Color.parseColor("#0E0E10"))
                labelNavToday.setTypeface(null, android.graphics.Typeface.BOLD)

                layoutTopHeader.visibility = View.VISIBLE
                scrollTodayView.visibility = View.VISIBLE
                layoutTasksPageView.visibility = View.GONE
                layoutTimelinePageView.visibility = View.GONE
                layoutSessionsPageView.visibility = View.GONE
            }

            AppNavTab.TASKS -> {
                pillNavTasks.setBackgroundResource(R.drawable.bg_nav_selected_pill)
                iconNavTasks.setColorFilter(Color.parseColor("#4F46E5"))
                labelNavTasks.setTextColor(Color.parseColor("#0E0E10"))
                labelNavTasks.setTypeface(null, android.graphics.Typeface.BOLD)

                layoutTopHeader.visibility = View.GONE
                scrollTodayView.visibility = View.GONE
                layoutTasksPageView.visibility = View.VISIBLE
                layoutTimelinePageView.visibility = View.GONE
                layoutSessionsPageView.visibility = View.GONE

                refreshTasksPageView()
            }

            AppNavTab.TIMELINE -> {
                pillNavTimeline.setBackgroundResource(R.drawable.bg_nav_selected_pill)
                iconNavTimeline.setColorFilter(Color.parseColor("#4F46E5"))
                labelNavTimeline.setTextColor(Color.parseColor("#0E0E10"))
                labelNavTimeline.setTypeface(null, android.graphics.Typeface.BOLD)

                layoutTopHeader.visibility = View.GONE
                scrollTodayView.visibility = View.GONE
                layoutTasksPageView.visibility = View.GONE
                layoutTimelinePageView.visibility = View.VISIBLE
                layoutSessionsPageView.visibility = View.GONE

                refreshTimelinePageView()
                scrollTimelineToToday()
            }

            AppNavTab.SESSIONS -> {
                pillNavSessions.setBackgroundResource(R.drawable.bg_nav_selected_pill)
                iconNavSessions.setColorFilter(Color.parseColor("#4F46E5"))
                labelNavSessions.setTextColor(Color.parseColor("#0E0E10"))
                labelNavSessions.setTypeface(null, android.graphics.Typeface.BOLD)

                layoutTopHeader.visibility = View.GONE
                scrollTodayView.visibility = View.GONE
                layoutTasksPageView.visibility = View.GONE
                layoutTimelinePageView.visibility = View.GONE
                layoutSessionsPageView.visibility = View.VISIBLE

                refreshSessionsPageView()
                sessionTickerHandler.removeCallbacks(sessionTickerRunnable)
                sessionTickerHandler.post(sessionTickerRunnable)
            }

            AppNavTab.AI -> {
                pillNavAi.setBackgroundResource(R.drawable.bg_nav_ai_black_circle)
                labelNavAi.setTextColor(Color.parseColor("#0E0E10"))
                labelNavAi.setTypeface(null, android.graphics.Typeface.BOLD)
                showAiNaturalLanguageBottomSheet()
            }
        }
    }

    private fun resetNavUi() {
        pillNavToday.background = null
        pillNavTasks.background = null
        pillNavTimeline.background = null
        pillNavSessions.background = null
        pillNavAi.setBackgroundResource(R.drawable.bg_nav_ai_black_circle)

        val grey = Color.parseColor("#6B7280")
        iconNavToday.setColorFilter(grey)
        iconNavTasks.setColorFilter(grey)
        iconNavTimeline.setColorFilter(grey)
        iconNavSessions.setColorFilter(grey)

        labelNavToday.setTextColor(grey)
        labelNavTasks.setTextColor(grey)
        labelNavTimeline.setTextColor(grey)
        labelNavSessions.setTextColor(grey)
        labelNavAi.setTextColor(grey)

        labelNavToday.setTypeface(null, android.graphics.Typeface.NORMAL)
        labelNavTasks.setTypeface(null, android.graphics.Typeface.NORMAL)
        labelNavTimeline.setTypeface(null, android.graphics.Typeface.NORMAL)
        labelNavSessions.setTypeface(null, android.graphics.Typeface.NORMAL)
        labelNavAi.setTypeface(null, android.graphics.Typeface.NORMAL)
    }

    private fun refreshTimelinePageView() {
        val tasks = repository.getTasksForView(currentTimelineView)
        viewNotionTimelineGantt.setTasks(tasks)
        scrollTimelineGanttHorizontal.post {
            viewNotionTimelineGantt.setViewport(
                scrollTimelineGanttHorizontal.scrollX,
                scrollTimelineGanttHorizontal.width
            )
        }
    }

    private fun scrollTimelineToToday() {
        scrollTimelineGanttHorizontal.post {
            val scrollTo = viewNotionTimelineGantt.getTodayColumnScrollX()
            scrollTimelineGanttHorizontal.smoothScrollTo(scrollTo, 0)
            viewNotionTimelineGantt.setViewport(
                scrollTo,
                scrollTimelineGanttHorizontal.width
            )
        }
    }

    private fun showTimelineViewSelectorMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "📋 Pending Tasks timeline")
        popup.menu.add(0, 2, 1, "📅 All Tasks timeline")
        popup.menu.add(0, 3, 2, "🎓 Academic Tasks timeline")
        popup.menu.add(0, 4, 3, "✅ Completed Tasks timeline")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    currentTimelineView = NotionView.PENDING
                    txtTimelineViewName.text = "Pending Tasks timeline"
                }
                2 -> {
                    currentTimelineView = NotionView.TIMELINE
                    txtTimelineViewName.text = "All Tasks timeline"
                }
                3 -> {
                    currentTimelineView = NotionView.ACADEMIC
                    txtTimelineViewName.text = "Academic Tasks timeline"
                }
                4 -> {
                    currentTimelineView = NotionView.COMPLETED
                    txtTimelineViewName.text = "Completed Tasks timeline"
                }
            }
            refreshTimelinePageView()
            true
        }
        popup.show()
    }

    private fun showTimelineZoomMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        popup.menu.add("Day")
        popup.menu.add("Week")
        popup.menu.add("Month")
        popup.menu.add("Quarter")
        popup.menu.add("Year")

        popup.setOnMenuItemClickListener { item ->
            val level = item.title.toString()
            txtTimelineGranularity.text = level
            viewNotionTimelineGantt.setGranularity(level)
            scrollTimelineToToday()
            true
        }
        popup.show()
    }

    private fun showTimelineFilterMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        popup.menu.add("Filter by: All Categories")
        popup.menu.add("Filter by: Academic")
        popup.menu.add("Filter by: Assignment")
        popup.menu.add("Filter by: Project")
        popup.menu.add("Filter by: Personal")
        popup.menu.add("Filter by: Urgent only")

        popup.setOnMenuItemClickListener { item ->
            val filterText = item.title.toString()
            when {
                filterText.contains("Academic") -> {
                    val tasks = repository.getAllTasks().filter { it.taskTypes.contains(TaskType.ACADEMIC) }
                    viewNotionTimelineGantt.setTasks(tasks)
                }
                filterText.contains("Assignment") -> {
                    val tasks = repository.getAllTasks().filter { it.taskTypes.contains(TaskType.ASSIGNMENT) }
                    viewNotionTimelineGantt.setTasks(tasks)
                }
                filterText.contains("Project") -> {
                    val tasks = repository.getAllTasks().filter { it.taskTypes.contains(TaskType.PROJECT) }
                    viewNotionTimelineGantt.setTasks(tasks)
                }
                filterText.contains("Personal") -> {
                    val tasks = repository.getAllTasks().filter { it.taskTypes.contains(TaskType.PERSONAL) }
                    viewNotionTimelineGantt.setTasks(tasks)
                }
                filterText.contains("Urgent") -> {
                    val tasks = repository.getAllTasks().filter { NotionFormulas.calculateTimeLeft(it).urgencyLevel == UrgencyLevel.URGENT }
                    viewNotionTimelineGantt.setTasks(tasks)
                }
                else -> refreshTimelinePageView()
            }
            Toast.makeText(this, filterText, Toast.LENGTH_SHORT).show()
            true
        }
        popup.show()
    }

    private fun showTimelineSortMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        popup.menu.add("Sort by: Due Date (Ascending)")
        popup.menu.add("Sort by: Due Date (Descending)")
        popup.menu.add("Sort by: Priority (High to Low)")
        popup.menu.add("Sort by: Title A-Z")

        popup.setOnMenuItemClickListener { item ->
            val currentTasks = repository.getTasksForView(currentTimelineView).toMutableList()
            when (item.title.toString()) {
                "Sort by: Due Date (Ascending)" -> currentTasks.sortBy { it.dueDate ?: Long.MAX_VALUE }
                "Sort by: Due Date (Descending)" -> currentTasks.sortByDescending { it.dueDate ?: 0L }
                "Sort by: Priority (High to Low)" -> currentTasks.sortBy { it.priority.formulaIndex }
                "Sort by: Title A-Z" -> currentTasks.sortBy { it.title.lowercase() }
            }
            viewNotionTimelineGantt.setTasks(currentTasks)
            Toast.makeText(this, item.title, Toast.LENGTH_SHORT).show()
            true
        }
        popup.show()
    }

    private fun showTimelineOptionsMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(this, anchor)
        popup.menu.add("Layout options")
        popup.menu.add("Timeline settings")
        popup.menu.add("Export view")
        popup.menu.add("Duplicate view")

        popup.setOnMenuItemClickListener { item ->
            Toast.makeText(this, item.title, Toast.LENGTH_SHORT).show()
            true
        }
        popup.show()
    }

    private fun refreshData() {
        updateGreetingAndDate()

        val allTasks = repository.getTopLevelTasks()
        val pendingTasks = allTasks.filter { !it.isCompleted }

        // Categorize Tasks into NOW, NEXT, LATER
        val nowList = mutableListOf<Task>()
        val nextList = mutableListOf<Task>()
        val laterList = mutableListOf<Task>()

        var urgentCount = 0
        var attentionCount = 0
        var onTrackCount = 0

        for (task in pendingTasks) {
            val tl = NotionFormulas.calculateTimeLeft(task)
            when (tl.urgencyLevel) {
                UrgencyLevel.OVERDUE, UrgencyLevel.URGENT -> {
                    nowList.add(task)
                    urgentCount++
                }
                UrgencyLevel.ATTENTION_NEEDED -> {
                    nextList.add(task)
                    attentionCount++
                }
                UrgencyLevel.ON_TRACK -> {
                    laterList.add(task)
                    onTrackCount++
                }
                UrgencyLevel.NORMAL_TIME_LEFT, UrgencyLevel.NO_DUE_DATE -> {
                    laterList.add(task)
                    onTrackCount++
                }
            }
        }

        // Update Attention Summary Card
        val totalAttention = urgentCount + attentionCount + onTrackCount
        txtAttentionHeadline.text = "$totalAttention tasks need your attention"
        txtUrgentCountLabel.text = "$urgentCount Urgent"
        txtAttentionCountLabel.text = "$attentionCount Attention"
        txtOnTrackCountLabel.text = "$onTrackCount On Track"

        // Submit to Today Section Recyclers
        adapterNow.submitList(nowList)
        headerSectionNow.visibility = if (nowList.isNotEmpty()) View.VISIBLE else View.GONE
        recyclerSectionNow.visibility = if (nowList.isNotEmpty()) View.VISIBLE else View.GONE

        adapterNext.submitList(nextList)
        headerSectionNext.visibility = if (nextList.isNotEmpty()) View.VISIBLE else View.GONE
        recyclerSectionNext.visibility = if (nextList.isNotEmpty()) View.VISIBLE else View.GONE

        adapterLater.submitList(laterList)
        headerSectionLater.visibility = if (laterList.isNotEmpty()) View.VISIBLE else View.GONE
        recyclerSectionLater.visibility = if (laterList.isNotEmpty()) View.VISIBLE else View.GONE

        // Update other tabs if active
        if (currentTab == AppNavTab.TASKS) {
            refreshTasksPageView()
        } else if (currentTab == AppNavTab.TIMELINE) {
            refreshTimelinePageView()
        } else if (currentTab == AppNavTab.SESSIONS) {
            refreshSessionsPageView()
        }
    }

    private fun updateGreetingAndDate() {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)

        val greeting = when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Good night"
        }
        txtGreetingHeadline.text = greeting

        val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
        txtGreetingDate.text = dateFormat.format(cal.time)
    }

    private fun toggleTaskStatus(task: Task) {
        val willBeCompleted = !task.isCompleted
        task.status = if (willBeCompleted) TaskStatus.DONE else TaskStatus.NOT_STARTED
        task.completedAt = if (willBeCompleted) System.currentTimeMillis() else null
        repository.updateTask(task)
        if (willBeCompleted) {
            TaskAlarmScheduler.cancelTaskAlarms(this, task.id)
        } else {
            TaskAlarmScheduler.scheduleTaskAlarms(this, task)
        }
        refreshData()
    }

    private fun startWorkSessionForTask(task: Task) {
        repository.startSession(task.id, "Work: ${task.title}")
        Toast.makeText(this, "Started session for '${task.title}'", Toast.LENGTH_SHORT).show()
        if (currentTab == AppNavTab.SESSIONS) {
            refreshSessionsPageView()
        } else {
            selectTab(AppNavTab.SESSIONS)
        }
    }

    // =========================================================================
    // TASK DETAIL / CREATE MODAL BOTTOM SHEET
    // =========================================================================
    private fun showTaskDetailBottomSheet(existingTask: Task?) {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_task_detail, null)
        dialog.setContentView(view)

        val txtSheetHeader = view.findViewById<TextView>(R.id.txtSheetHeader)
        val btnDelete = view.findViewById<ImageView>(R.id.btnDeleteTaskInSheet)
        val btnClose = view.findViewById<ImageView>(R.id.btnCloseSheet)
        val edtTitle = view.findViewById<EditText>(R.id.edtTaskTitle)
        val layoutMatrixBanner = view.findViewById<LinearLayout>(R.id.layoutSheetMatrixBanner)
        val txtQuadrant = view.findViewById<TextView>(R.id.txtSheetQuadrant)
        val txtTimeLeft = view.findViewById<TextView>(R.id.txtSheetTimeLeft)
        val txtDurationTracked = view.findViewById<TextView>(R.id.txtSheetDurationTracked)

        val btnPriorityHigh = view.findViewById<TextView>(R.id.btnPriorityHigh)
        val btnPriorityMed = view.findViewById<TextView>(R.id.btnPriorityMedium)
        val btnPriorityLow = view.findViewById<TextView>(R.id.btnPriorityLow)

        val statusButtons = mapOf(
            TaskStatus.NOT_STARTED to view.findViewById<TextView>(R.id.btnStatusNotStarted),
            TaskStatus.IN_PROGRESS to view.findViewById<TextView>(R.id.btnStatusInProgress),
            TaskStatus.PROCRASTINATED to view.findViewById<TextView>(R.id.btnStatusProcrastinated),
            TaskStatus.LEFT to view.findViewById<TextView>(R.id.btnStatusLeft),
            TaskStatus.DONE to view.findViewById<TextView>(R.id.btnStatusDone)
        )

        val btnSelectDueDate = view.findViewById<LinearLayout>(R.id.btnSelectDueDate)
        val txtSelectedDueDate = view.findViewById<TextView>(R.id.txtSelectedDueDate)
        val edtMinTime = view.findViewById<EditText>(R.id.edtMinTimeRequired)
        val chipGroupTypes = view.findViewById<ChipGroup>(R.id.chipGroupTaskTypes)
        val edtComment = view.findViewById<EditText>(R.id.edtTaskComment)

        val layoutSubtasksSection = view.findViewById<LinearLayout>(R.id.layoutSubtasksSection)
        val btnAddSubtaskTrigger = view.findViewById<TextView>(R.id.btnAddSubtaskTrigger)
        val layoutInlineAddSubtask = view.findViewById<LinearLayout>(R.id.layoutInlineAddSubtask)
        val edtSubtaskInput = view.findViewById<EditText>(R.id.edtSubtaskTitleInput)
        val btnConfirmAddSubtask = view.findViewById<ImageView>(R.id.btnConfirmAddSubtask)
        val recyclerSubtasks = view.findViewById<RecyclerView>(R.id.recyclerSubtasks)

        val btnStartSession = view.findViewById<Button>(R.id.btnStartSessionFromDetail)
        val btnSave = view.findViewById<Button>(R.id.btnSaveTask)

        var selectedPriority = existingTask?.priority ?: TaskPriority.MEDIUM
        var selectedStatus = existingTask?.status ?: TaskStatus.NOT_STARTED
        var selectedDueDate: Long? = existingTask?.dueDate
        val selectedTypes = existingTask?.taskTypes?.toMutableSet() ?: mutableSetOf()

        fun updatePriorityUi() {
            btnPriorityHigh.setBackgroundResource(if (selectedPriority == TaskPriority.HIGH) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            btnPriorityHigh.setTextColor(ContextCompat.getColor(this, if (selectedPriority == TaskPriority.HIGH) R.color.color_white else R.color.color_carbon))

            btnPriorityMed.setBackgroundResource(if (selectedPriority == TaskPriority.MEDIUM) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            btnPriorityMed.setTextColor(ContextCompat.getColor(this, if (selectedPriority == TaskPriority.MEDIUM) R.color.color_white else R.color.color_carbon))

            btnPriorityLow.setBackgroundResource(if (selectedPriority == TaskPriority.LOW) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
            btnPriorityLow.setTextColor(ContextCompat.getColor(this, if (selectedPriority == TaskPriority.LOW) R.color.color_white else R.color.color_carbon))
        }

        fun updateStatusUi() {
            for ((status, btn) in statusButtons) {
                val isSelected = status == selectedStatus
                btn.setBackgroundResource(if (isSelected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                btn.setTextColor(ContextCompat.getColor(this, if (isSelected) R.color.color_white else R.color.color_carbon))
            }
        }

        val dateFormat = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
        fun updateDueDateUi() {
            txtSelectedDueDate.text = if (selectedDueDate != null) dateFormat.format(Date(selectedDueDate!!)) else "Set Due Date"
        }

        btnPriorityHigh.setOnClickListener { selectedPriority = TaskPriority.HIGH; updatePriorityUi() }
        btnPriorityMed.setOnClickListener { selectedPriority = TaskPriority.MEDIUM; updatePriorityUi() }
        btnPriorityLow.setOnClickListener { selectedPriority = TaskPriority.LOW; updatePriorityUi() }

        for ((status, btn) in statusButtons) {
            btn.setOnClickListener { selectedStatus = status; updateStatusUi() }
        }

        btnSelectDueDate.setOnClickListener {
            val cal = Calendar.getInstance()
            if (selectedDueDate != null) cal.timeInMillis = selectedDueDate!!

            DatePickerDialog(this, { _, year, month, dayOfMonth ->
                cal.set(Calendar.YEAR, year)
                cal.set(Calendar.MONTH, month)
                cal.set(Calendar.DAY_OF_MONTH, dayOfMonth)

                TimePickerDialog(this, { _, hourOfDay, minute ->
                    cal.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    cal.set(Calendar.MINUTE, minute)
                    selectedDueDate = cal.timeInMillis
                    updateDueDateUi()
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()

            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        // Populate task types chips
        chipGroupTypes.removeAllViews()
        for (type in TaskType.values()) {
            val chip = Chip(this)
            chip.text = type.displayName
            chip.isCheckable = true
            chip.isChecked = selectedTypes.contains(type)
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedTypes.add(type) else selectedTypes.remove(type)
            }
            chipGroupTypes.addView(chip)
        }

        // Subtasks setup
        val subtasksAdapter = SubtaskAdapter { subtask ->
            TaskAlarmScheduler.cancelTaskAlarms(this, subtask.id)
            repository.deleteTask(subtask.id)
            if (existingTask != null) {
                (recyclerSubtasks.adapter as? SubtaskAdapter)?.submitList(repository.getSubTasks(existingTask.id))
            }
        }
        recyclerSubtasks.layoutManager = LinearLayoutManager(this)
        recyclerSubtasks.adapter = subtasksAdapter

        if (existingTask != null) {
            txtSheetHeader.text = "Edit Task"
            btnDelete.visibility = View.VISIBLE
            edtTitle.setText(existingTask.title)
            edtMinTime.setText(existingTask.minimumTimeRequired)
            edtComment.setText(existingTask.comment)

            val qResult = NotionFormulas.calculateQuadrant(existingTask)
            if (qResult.isValid) {
                layoutMatrixBanner.visibility = View.VISIBLE
                txtQuadrant.text = "Q${qResult.qNumber} · ${qResult.action}"
                val tl = NotionFormulas.calculateTimeLeft(existingTask)
                txtTimeLeft.text = tl.formattedTime
                val dur = NotionFormulas.calculateTotalDuration(existingTask, repository.getAllTasks(), repository.getAllSessions())
                txtDurationTracked.text = "Tracked: $dur"
            }

            subtasksAdapter.submitList(repository.getSubTasks(existingTask.id))
        } else {
            txtSheetHeader.text = "New Task"
            btnDelete.visibility = View.GONE
            layoutMatrixBanner.visibility = View.GONE
            layoutSubtasksSection.visibility = View.GONE
        }

        updatePriorityUi()
        updateStatusUi()
        updateDueDateUi()

        btnAddSubtaskTrigger.setOnClickListener {
            layoutInlineAddSubtask.visibility = if (layoutInlineAddSubtask.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        btnConfirmAddSubtask.setOnClickListener {
            val subTitle = edtSubtaskInput.text.toString().trim()
            if (subTitle.isNotBlank() && existingTask != null) {
                val newSub = Task(
                    id = "sub_" + System.currentTimeMillis(),
                    title = subTitle,
                    status = TaskStatus.NOT_STARTED,
                    priority = existingTask.priority,
                    taskTypes = existingTask.taskTypes,
                    minimumTimeRequired = "0d 0h 30m",
                    parentTaskId = existingTask.id
                )
                repository.addTask(newSub)
                TaskAlarmScheduler.scheduleTaskAlarms(this, newSub)
                edtSubtaskInput.setText("")
                layoutInlineAddSubtask.visibility = View.GONE
                subtasksAdapter.submitList(repository.getSubTasks(existingTask.id))
            }
        }

        btnStartSession.setOnClickListener {
            val taskId = existingTask?.id
            val title = edtTitle.text.toString().ifBlank { "Working Session" }
            repository.startSession(taskId, "Work: $title")
            dialog.dismiss()
            showTimesheetsBottomSheet()
        }

        btnDelete.setOnClickListener {
            if (existingTask != null) {
                TaskAlarmScheduler.cancelTaskAlarms(this, existingTask.id)
                repository.deleteTask(existingTask.id)
                dialog.dismiss()
                refreshData()
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val title = edtTitle.text.toString().trim()
            if (title.isBlank()) {
                Toast.makeText(this, "Please enter a task title.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (existingTask != null) {
                existingTask.title = title
                existingTask.priority = selectedPriority
                if (selectedStatus == TaskStatus.DONE && existingTask.status != TaskStatus.DONE) {
                    existingTask.completedAt = System.currentTimeMillis()
                } else if (selectedStatus != TaskStatus.DONE) {
                    existingTask.completedAt = null
                }
                existingTask.status = selectedStatus
                existingTask.dueDate = selectedDueDate
                existingTask.minimumTimeRequired = edtMinTime.text.toString().ifBlank { "0d 1h 0m" }
                existingTask.taskTypes = selectedTypes
                existingTask.comment = edtComment.text.toString()
                repository.updateTask(existingTask)

                if (existingTask.isCompleted) {
                    TaskAlarmScheduler.cancelTaskAlarms(this, existingTask.id)
                } else {
                    TaskAlarmScheduler.scheduleTaskAlarms(this, existingTask)
                }
            } else {
                val newTask = Task(
                    id = "task_" + System.currentTimeMillis(),
                    title = title,
                    status = selectedStatus,
                    priority = selectedPriority,
                    taskTypes = selectedTypes,
                    comment = edtComment.text.toString(),
                    minimumTimeRequired = edtMinTime.text.toString().ifBlank { "0d 1h 0m" },
                    dueDate = selectedDueDate,
                    remainderDate = selectedDueDate,
                    completedAt = if (selectedStatus == TaskStatus.DONE) System.currentTimeMillis() else null
                )
                repository.addTask(newTask)
                if (!newTask.isCompleted) {
                    TaskAlarmScheduler.scheduleTaskAlarms(this, newTask)
                }
            }

            dialog.dismiss()
            refreshData()
        }

        dialog.show()
    }

    // =========================================================================
    // AI NATURAL LANGUAGE PARSER MODAL BOTTOM SHEET
    // =========================================================================
    private fun showAiNaturalLanguageBottomSheet(initialPrompt: String = "") {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_ai_task, null)
        dialog.setContentView(view)

        val settings = AppSettingsManager.getInstance(this)

        val viewAiInterlocking = view.findViewById<InterlockingGeometryView>(R.id.viewAiInterlocking)
        val btnClose = view.findViewById<ImageView>(R.id.btnCloseAiSheet)
        val txtEngineStatus = view.findViewById<TextView>(R.id.txtAiEngineStatus)
        val bannerApiKeyWarning = view.findViewById<LinearLayout>(R.id.bannerApiKeyWarning)
        val btnOpenSettings = view.findViewById<TextView>(R.id.btnOpenSettingsFromAi)

        val edtInput = view.findViewById<EditText>(R.id.edtAiInput)
        val btnVoiceInput = view.findViewById<ImageView>(R.id.btnAiVoiceInput)
        val btnParse = view.findViewById<ImageView>(R.id.btnAiParseAction)

        val chipSampleExam = view.findViewById<TextView>(R.id.chipSampleExam)
        val chipSampleAssignment = view.findViewById<TextView>(R.id.chipSampleAssignment)
        val chipSampleReading = view.findViewById<TextView>(R.id.chipSampleReading)

        val layoutLoading = view.findViewById<LinearLayout>(R.id.layoutAiLoading)
        val layoutResult = view.findViewById<LinearLayout>(R.id.layoutAiParsedResult)
        val txtTitle = view.findViewById<TextView>(R.id.txtAiParsedTitle)
        val txtQuadrant = view.findViewById<TextView>(R.id.txtAiParsedQuadrant)
        val txtPriority = view.findViewById<TextView>(R.id.txtAiParsedPriority)
        val txtMinTime = view.findViewById<TextView>(R.id.txtAiParsedMinTime)
        val txtDetails = view.findViewById<TextView>(R.id.txtAiParsedDetails)
        val txtSubtasksCount = view.findViewById<TextView>(R.id.txtAiSubtasksCount)

        val layoutActions = view.findViewById<LinearLayout>(R.id.layoutAiActions)
        val btnDone = view.findViewById<Button>(R.id.btnDoneAiTask)
        val btnEdit = view.findViewById<Button>(R.id.btnEditAiTask)

        var lastCreatedTask: Task? = null

        fun updateAiEngineHeader() {
            if (settings.hasValidGeminiKey()) {
                val modelName = settings.selectedAiModel.ifBlank { "gemini-2.5-flash" }
                txtEngineStatus.text = "⚡ Powered by $modelName · Auto-Create"
                bannerApiKeyWarning.visibility = View.GONE
            } else {
                txtEngineStatus.text = "⚠️ Gemini API key not configured"
                bannerApiKeyWarning.visibility = View.VISIBLE
            }
        }
        updateAiEngineHeader()

        btnOpenSettings.setOnClickListener {
            dialog.dismiss()
            showSettingsBottomSheet()
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        fun parseAndCreateAutomatically(prompt: String) {
            val cleanPrompt = prompt.trim()
            if (cleanPrompt.isBlank()) {
                Toast.makeText(this, "Please describe a task first.", Toast.LENGTH_SHORT).show()
                return
            }

            if (!settings.hasValidGeminiKey()) {
                Toast.makeText(this, "Please enter your Gemini API Key in Settings first.", Toast.LENGTH_LONG).show()
                showSettingsBottomSheet()
                return
            }

            viewAiInterlocking.setMode(InterlockingGeometryView.Mode.AI_PROCESSING)
            btnParse.isEnabled = false
            btnVoiceInput.isEnabled = false
            layoutLoading.visibility = View.VISIBLE
            layoutResult.visibility = View.GONE
            layoutActions.visibility = View.GONE

            GeminiAiService.parseTask(cleanPrompt, this@MainActivity) { parseResult: TaskParseResult ->
                viewAiInterlocking.setMode(InterlockingGeometryView.Mode.STATIC_METALLIC)
                btnParse.isEnabled = true
                btnVoiceInput.isEnabled = true
                layoutLoading.visibility = View.GONE

                if (!parseResult.success || parseResult.task == null) {
                    // STRICT REQUIREMENT: DO NOT CREATE ANY TASK WHEN LLM FAILS
                    val errorText = parseResult.error ?: "Gemini LLM extraction failed. No task created."
                    Toast.makeText(this@MainActivity, "❌ $errorText", Toast.LENGTH_LONG).show()
                    layoutResult.visibility = View.GONE
                    layoutActions.visibility = View.GONE
                    return@parseTask
                }

                val task = parseResult.task
                lastCreatedTask = task

                // AUTOMATICALLY CREATE TASK AND ALL SUBTASKS IN REPOSITORY ONLY ON SUCCESSFUL LLM EXTRACTION
                repository.addTask(task)
                TaskAlarmScheduler.scheduleTaskAlarms(this@MainActivity, task)
                for (subtask in parseResult.subtasks) {
                    repository.addTask(subtask)
                    TaskAlarmScheduler.scheduleTaskAlarms(this@MainActivity, subtask)
                }

                // Instantly refresh all views (Today view, Tasks view, Timeline Gantt, Sessions)
                refreshData()

                // Display created task details in preview card
                val qRes = NotionFormulas.calculateQuadrant(task)
                txtTitle.text = task.title
                txtQuadrant.text = "Q${qRes.qNumber} · ${qRes.action}"
                txtPriority.text = task.priority.name
                txtMinTime.text = task.minimumTimeRequired

                val dueDate = task.dueDate ?: System.currentTimeMillis()
                val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                txtDetails.text = "Due: ${sdf.format(Date(dueDate))} | Types: ${task.taskTypes.joinToString { it.displayName }}"

                if (parseResult.subtasks.isNotEmpty()) {
                    txtSubtasksCount.visibility = View.VISIBLE
                    txtSubtasksCount.text = "✓ ${parseResult.subtasks.size} subtasks automatically created"
                } else {
                    txtSubtasksCount.visibility = View.GONE
                }

                layoutResult.visibility = View.VISIBLE
                layoutActions.visibility = View.VISIBLE

                Toast.makeText(this@MainActivity, "✨ Task '${task.title}' created via Gemini AI!", Toast.LENGTH_SHORT).show()
            }
        }

        btnParse.setOnClickListener {
            val text = edtInput.text.toString().trim()
            parseAndCreateAutomatically(text)
        }

        btnVoiceInput.setOnClickListener {
            launchSpeechInput { spokenText ->
                edtInput.setText(spokenText)
                parseAndCreateAutomatically(spokenText)
            }
        }

        chipSampleExam.setOnClickListener {
            val prompt = "Physics Exam prep on Friday 6pm, 3h min time, high priority, academic exam"
            edtInput.setText(prompt)
            parseAndCreateAutomatically(prompt)
        }

        chipSampleAssignment.setOnClickListener {
            val prompt = "OS Project draft tomorrow by 5pm, 4h min time, project assignment"
            edtInput.setText(prompt)
            parseAndCreateAutomatically(prompt)
        }

        chipSampleReading.setOnClickListener {
            val prompt = "Read Machine Learning chapter 3 tonight for 45m, book reading"
            edtInput.setText(prompt)
            parseAndCreateAutomatically(prompt)
        }

        btnDone.setOnClickListener {
            dialog.dismiss()
        }

        btnEdit.setOnClickListener {
            dialog.dismiss()
            lastCreatedTask?.let { task ->
                showTaskDetailBottomSheet(task)
            }
        }

        if (initialPrompt.isNotBlank()) {
            edtInput.setText(initialPrompt)
            parseAndCreateAutomatically(initialPrompt)
        }

        dialog.show()
    }

    // =========================================================================
    // SETTINGS MODAL BOTTOM SHEET (Google Account, Gemini API, Preferences)
    // =========================================================================
    private fun showSettingsBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_settings, null)
        dialog.setContentView(view)

        val settings = AppSettingsManager.getInstance(this)

        val btnClose = view.findViewById<ImageView>(R.id.btnCloseSettingsSheet)
        val txtGoogleEmail = view.findViewById<TextView>(R.id.txtGoogleAccountEmail)
        val switchGoogleSync = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchGoogleSync)
        val txtGeminiBadge = view.findViewById<TextView>(R.id.txtGeminiStatusBadge)
        val edtGeminiKey = view.findViewById<EditText>(R.id.edtGeminiApiKey)
        val btnToggleKeyVis = view.findViewById<ImageView>(R.id.btnToggleApiKeyVisibility)
        val spinnerAiModel = view.findViewById<Spinner>(R.id.spinnerAiModel)
        val btnTestKey = view.findViewById<Button>(R.id.btnTestGeminiKey)
        val btnSaveKey = view.findViewById<Button>(R.id.btnSaveGeminiKey)
        val switchNotifications = view.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchNotifications)
        val btnExportData = view.findViewById<View>(R.id.btnExportData)

        btnClose.setOnClickListener { dialog.dismiss() }

        // Google Account
        txtGoogleEmail.text = settings.googleAccountEmail
        switchGoogleSync.isChecked = settings.isGoogleSynced
        switchGoogleSync.setOnCheckedChangeListener { _, isChecked ->
            settings.isGoogleSynced = isChecked
            Toast.makeText(this, if (isChecked) "Google Cloud Sync enabled" else "Google Cloud Sync disabled", Toast.LENGTH_SHORT).show()
        }

        // AI Model Spinner Setup with latest Gemini models
        val models = listOf("gemini-3.7-flash", "gemini-3.7-pro", "gemini-2.5-flash", "gemini-2.0-flash")
        val modelDisplayNames = listOf("Gemini 3.7 Flash (Latest & Recommended)", "Gemini 3.7 Pro (Deep Reasoning)", "Gemini 2.5 Flash", "Gemini 2.0 Flash")
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelDisplayNames)
        spinnerAiModel.adapter = spinnerAdapter
        val currentModelIndex = models.indexOf(settings.selectedAiModel).coerceAtLeast(0)
        spinnerAiModel.setSelection(currentModelIndex)

        // Gemini AI API Key
        edtGeminiKey.setText(settings.geminiApiKey)

        fun updateGeminiBadge() {
            if (settings.hasValidGeminiKey()) {
                txtGeminiBadge.text = "Active (${settings.selectedAiModel})"
                txtGeminiBadge.setBackgroundResource(R.drawable.bg_badge_connected)
                txtGeminiBadge.setTextColor(Color.parseColor("#059669"))
            } else {
                txtGeminiBadge.text = "Not Configured"
                txtGeminiBadge.setBackgroundResource(R.drawable.bg_badge_disconnected)
                txtGeminiBadge.setTextColor(Color.parseColor("#DC2626"))
            }
        }
        updateGeminiBadge()

        var isPasswordVisible = false
        btnToggleKeyVis.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                edtGeminiKey.inputType = android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                edtGeminiKey.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            edtGeminiKey.setSelection(edtGeminiKey.text.length)
        }

        btnTestKey.setOnClickListener {
            val key = edtGeminiKey.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(this@MainActivity, "Please enter a Gemini API key first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnTestKey.isEnabled = false
            btnTestKey.text = "Testing..."
            GeminiAiService.testApiKey(key) { success: Boolean, message: String ->
                btnTestKey.isEnabled = true
                btnTestKey.text = "Test Key"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                if (success) {
                    settings.geminiApiKey = key
                    val selectedPos = spinnerAiModel.selectedItemPosition
                    if (selectedPos in models.indices) {
                        settings.selectedAiModel = models[selectedPos]
                    }
                    updateGeminiBadge()
                }
            }
        }

        btnSaveKey.setOnClickListener {
            val key = edtGeminiKey.text.toString().trim()
            val selectedPos = spinnerAiModel.selectedItemPosition
            if (selectedPos in models.indices) {
                settings.selectedAiModel = models[selectedPos]
            }
            settings.geminiApiKey = key
            updateGeminiBadge()
            Toast.makeText(this@MainActivity, if (key.isNotBlank()) "Gemini settings saved!" else "API key cleared. Local parser will be used.", Toast.LENGTH_SHORT).show()
        }

        // Preferences
        switchNotifications.isChecked = settings.notificationsEnabled
        switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            settings.notificationsEnabled = isChecked
            Toast.makeText(this@MainActivity, if (isChecked) "Notifications enabled" else "Notifications muted", Toast.LENGTH_SHORT).show()
        }

        // Background Activity & Alarm Permissions Controls
        val txtNotifPermBadge = view.findViewById<TextView>(R.id.txtNotifPermBadge)
        val btnGrantNotifPerm = view.findViewById<Button>(R.id.btnGrantNotifPerm)
        val txtExactAlarmBadge = view.findViewById<TextView>(R.id.txtExactAlarmBadge)
        val btnGrantExactAlarm = view.findViewById<Button>(R.id.btnGrantExactAlarm)
        val txtBatteryOptBadge = view.findViewById<TextView>(R.id.txtBatteryOptBadge)
        val btnFixBatteryOptimization = view.findViewById<Button>(R.id.btnFixBatteryOptimization)
        val btnOpenAppSettings = view.findViewById<Button>(R.id.btnOpenAppSettings)

        fun updatePermissionsUi() {
            // 1. Notification Permission
            val notifGranted = isNotificationPermissionGranted()
            txtNotifPermBadge.text = if (notifGranted) "Granted" else "Action Required"
            txtNotifPermBadge.setBackgroundResource(if (notifGranted) R.drawable.bg_badge_connected else R.drawable.bg_badge_disconnected)
            txtNotifPermBadge.setTextColor(Color.parseColor(if (notifGranted) "#059669" else "#DC2626"))
            btnGrantNotifPerm.text = if (notifGranted) "Configure in Settings" else "Grant Notification Permission"

            // 2. Exact Alarm Permission
            val exactGranted = isExactAlarmPermissionGranted()
            txtExactAlarmBadge.text = if (exactGranted) "Granted" else "Action Required"
            txtExactAlarmBadge.setBackgroundResource(if (exactGranted) R.drawable.bg_badge_connected else R.drawable.bg_badge_disconnected)
            txtExactAlarmBadge.setTextColor(Color.parseColor(if (exactGranted) "#059669" else "#DC2626"))
            btnGrantExactAlarm.text = if (exactGranted) "Exact Alarms Enabled" else "Allow Exact Alarms Setting"

            // 3. Battery Optimization
            val batIgnored = isIgnoringBatteryOptimizations()
            txtBatteryOptBadge.text = if (batIgnored) "Unrestricted" else "Optimized (May Delay)"
            txtBatteryOptBadge.setBackgroundResource(if (batIgnored) R.drawable.bg_badge_connected else R.drawable.bg_badge_disconnected)
            txtBatteryOptBadge.setTextColor(Color.parseColor(if (batIgnored) "#059669" else "#DC2626"))
            btnFixBatteryOptimization.text = if (batIgnored) "Battery Optimization Disabled" else "Disable Battery Optimization"
        }

        updatePermissionsUi()

        btnGrantNotifPerm.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isNotificationPermissionGranted()) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                try {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            }
        }

        btnGrantExactAlarm.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } else {
                Toast.makeText(this@MainActivity, "Exact alarms are enabled on this Android version.", Toast.LENGTH_SHORT).show()
            }
        }

        btnFixBatteryOptimization.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    } catch (ex: Exception) {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                }
            } else {
                Toast.makeText(this@MainActivity, "Battery optimization exemption not required on this Android version.", Toast.LENGTH_SHORT).show()
            }
        }

        btnOpenAppSettings.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Could not open app settings.", Toast.LENGTH_SHORT).show()
            }
        }

        btnExportData.setOnClickListener {
            val allTasks = repository.getAllTasks()
            val allSessions = repository.getAllSessions()
            Toast.makeText(this@MainActivity, "Exported ${allTasks.size} tasks & ${allSessions.size} sessions to backup", Toast.LENGTH_LONG).show()
        }

        dialog.show()
    }

    // =========================================================================
    // TIMESHEETS & LIVE TRACKING MODAL BOTTOM SHEET
    // =========================================================================
    private fun showTimesheetsBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_timesheets, null)
        dialog.setContentView(view)

        val btnClose = view.findViewById<ImageView>(R.id.btnCloseTimesheetsSheet)
        val cardActive = view.findViewById<LinearLayout>(R.id.cardActiveSessionInSheet)
        val txtElapsedLive = view.findViewById<TextView>(R.id.txtActiveSessionElapsedLive)
        val txtTaskName = view.findViewById<TextView>(R.id.txtActiveSessionTaskName)
        val txtCountdown = view.findViewById<TextView>(R.id.txtActiveSessionCountdown)
        val btnEndSession = view.findViewById<Button>(R.id.btnEndActiveSessionSheet)

        val edtNewTitle = view.findViewById<EditText>(R.id.edtNewSessionTitle)
        val spinnerTask = view.findViewById<Spinner>(R.id.spinnerLinkTask)
        val btnStartNew = view.findViewById<Button>(R.id.btnStartNewSession)
        val recyclerHistory = view.findViewById<RecyclerView>(R.id.recyclerTimesheetsLog)

        btnClose.setOnClickListener { dialog.dismiss() }

        // Populate task spinner
        val allTasks = repository.getAllTasks()
        val taskTitles = mutableListOf("No linked task (Independent)")
        taskTitles.addAll(allTasks.map { it.title })
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, taskTitles)
        spinnerTask.adapter = spinnerAdapter

        // History adapter
        val historyAdapter = TimesheetAdapter {}
        recyclerHistory.layoutManager = LinearLayoutManager(this)
        recyclerHistory.adapter = historyAdapter
        historyAdapter.submitList(repository.getAllSessions())

        fun updateSheetLiveState() {
            val activeState = repository.getActiveSessionState()
            if (activeState == null) {
                cardActive.visibility = View.GONE
            } else {
                cardActive.visibility = View.VISIBLE
                txtTaskName.text = activeState.task?.title ?: activeState.session.title

                val elapsedH = activeState.elapsedSeconds / 3600L
                val elapsedM = (activeState.elapsedSeconds % 3600L) / 60L
                val elapsedS = activeState.elapsedSeconds % 60L
                txtElapsedLive.text = String.format("%02d:%02d:%02d", elapsedH, elapsedM, elapsedS)

                if (activeState.remainingSeconds != null) {
                    val remH = activeState.remainingSeconds / 3600L
                    val remM = (activeState.remainingSeconds % 3600L) / 60L
                    val remS = activeState.remainingSeconds % 60L
                    val formatted = String.format("%02d:%02d:%02d", remH, remM, remS)
                    txtCountdown.text = if (activeState.isOvertime) "Overtime: $formatted" else "Remaining: $formatted"
                } else {
                    txtCountdown.text = "No minimum time set"
                }
            }
        }

        updateSheetLiveState()

        btnEndSession.setOnClickListener {
            repository.endCurrentActiveSession()
            updateSheetLiveState()
            historyAdapter.submitList(repository.getAllSessions())
            refreshData()
            Toast.makeText(this, "Session logged.", Toast.LENGTH_SHORT).show()
        }

        btnStartNew.setOnClickListener {
            val title = edtNewTitle.text.toString().ifBlank { "Working Session" }
            val selIdx = spinnerTask.selectedItemPosition
            val linkedTaskId = if (selIdx > 0 && selIdx - 1 < allTasks.size) allTasks[selIdx - 1].id else null

            repository.startSession(linkedTaskId, title)
            edtNewTitle.setText("")
            updateSheetLiveState()
            historyAdapter.submitList(repository.getAllSessions())
            refreshData()
            Toast.makeText(this, "Tracking started!", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    // Inner Subtask Adapter
    class SubtaskAdapter(private val onDeleteClicked: (Task) -> Unit) :
        ListAdapter<Task, SubtaskAdapter.SubtaskViewHolder>(TaskAdapter.TaskDiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubtaskViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_subtask_precision, parent, false)
            return SubtaskViewHolder(view)
        }

        override fun onBindViewHolder(holder: SubtaskViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class SubtaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val txtTitle: TextView = itemView.findViewById(R.id.txtSubtaskTitle)
            private val txtDur: TextView = itemView.findViewById(R.id.txtSubtaskDuration)
            private val btnDel: ImageView = itemView.findViewById(R.id.btnDeleteSubtask)

            fun bind(task: Task) {
                txtTitle.text = task.title
                txtDur.text = task.minimumTimeRequired
                btnDel.setOnClickListener { onDeleteClicked(task) }
            }
        }
    }
}
