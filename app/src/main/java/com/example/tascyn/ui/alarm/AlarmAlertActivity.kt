package com.example.tascyn.ui.alarm

import android.app.DatePickerDialog
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.tascyn.R
import com.example.tascyn.data.Task
import com.example.tascyn.data.TaskManagerRepository
import com.example.tascyn.data.TaskStatus
import com.example.tascyn.domain.NotionFormulas
import com.example.tascyn.receiver.SessionNotificationManager
import com.example.tascyn.receiver.TaskAlarmScheduler
import java.text.SimpleDateFormat
import java.util.*

class AlarmAlertActivity : AppCompatActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    private var currentTask: Task? = null
    private var taskId: String? = null
    private var alarmType: String? = null

    // Auto-timeout handlers
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null
    private var isUrgentOrOverdueAlert = false
    private var alertTimeoutSeconds = 30
    private var activityWakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAndUnlockScreen()
        acquireActivityWakeLock()
        setContentView(R.layout.activity_alarm_alert)

        extractIntentData(intent)
        initUi()
        startAlarmAudioAndVibration()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        wakeAndUnlockScreen()
        acquireActivityWakeLock()
        extractIntentData(intent)
        initUi()
        startAlarmAudioAndVibration()
    }

    private fun extractIntentData(srcIntent: Intent?) {
        taskId = srcIntent?.getStringExtra(TaskAlarmScheduler.EXTRA_TASK_ID)
        alarmType = srcIntent?.getStringExtra(TaskAlarmScheduler.EXTRA_ALARM_TYPE)

        val repository = TaskManagerRepository.get()
        repository.attachContext(this)
        currentTask = if (taskId != null) repository.getTaskById(taskId!!) else null
    }

    private fun wakeAndUnlockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
        )
    }

    private fun acquireActivityWakeLock() {
        try {
            if (activityWakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                @Suppress("DEPRECATION")
                activityWakeLock = powerManager?.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "Tascyn:AlarmAlertActivityWakeLock"
                )
                // Hold wake lock for alert duration plus buffer
                activityWakeLock?.acquire((alertTimeoutSeconds + 5) * 1000L)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseActivityWakeLock() {
        try {
            if (activityWakeLock?.isHeld == true) {
                activityWakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            activityWakeLock = null
        }
    }

    private fun initUi() {
        val txtBadgeLabel = findViewById<TextView>(R.id.txtAlarmBadgeLabel)
        val layoutBadge = findViewById<View>(R.id.layoutAlarmBadge)
        val dotIndicator = findViewById<View>(R.id.dotAlarmIndicator)
        val txtTimeSubtitle = findViewById<TextView>(R.id.txtAlarmTimeSubtitle)
        val txtTaskTitle = findViewById<TextView>(R.id.txtAlarmTaskTitle)
        val txtQuadrant = findViewById<TextView>(R.id.txtAlarmQuadrant)
        val txtPriority = findViewById<TextView>(R.id.txtAlarmPriority)
        val txtDueTime = findViewById<TextView>(R.id.txtAlarmDueTime)
        val txtMinTime = findViewById<TextView>(R.id.txtAlarmMinTime)
        val txtComment = findViewById<TextView>(R.id.txtAlarmComment)

        val btnSnooze10m = findViewById<Button>(R.id.btnAlarmSnooze10m)
        val btnRescheduleDueDate = findViewById<Button>(R.id.btnAlarmRescheduleDueDate)
        val btnAddCustomReminder = findViewById<Button>(R.id.btnAlarmAddCustomReminder)
        val btnMarkDone = findViewById<Button>(R.id.btnAlarmMarkDone)
        val btnDismiss = findViewById<Button>(R.id.btnAlarmDismiss)

        val task = currentTask
        val title = task?.title ?: intent?.getStringExtra(TaskAlarmScheduler.EXTRA_TASK_TITLE) ?: "Task Alert"
        txtTaskTitle.text = title

        val now = System.currentTimeMillis()
        val minMinutes = if (task != null) NotionFormulas.parseMinimumTimeToMinutes(task.minimumTimeRequired) else 0L
        val minMillis = minMinutes.coerceAtLeast(1L) * 60 * 1000L

        // Urgency calculation:
        // - Time is lesser than time of urgent: urgent window (now >= dueDate - minMillis) or overdue
        // - Time is more than time of urgent: standard reminders / custom alerts
        val isOverdue = alarmType == TaskAlarmScheduler.ACTION_TRIGGER_OVERDUE_ALARM || (task?.dueDate != null && now >= task.dueDate!!)
        isUrgentOrOverdueAlert = isOverdue ||
                (task?.dueDate != null && now >= task.dueDate!! - minMillis) ||
                alarmType == TaskAlarmScheduler.ACTION_TRIGGER_URGENT_ALARM

        // Show for 30 seconds if time is more than urgent; show for 1 minute (60s) if time is lesser than urgent
        alertTimeoutSeconds = if (isUrgentOrOverdueAlert) 60 else 30

        val baseSubtitle: String
        if (isOverdue) {
            txtBadgeLabel.text = "TASK OVERDUE"
            txtBadgeLabel.setTextColor(Color.parseColor("#DC2626"))
            layoutBadge.setBackgroundResource(R.drawable.bg_badge_disconnected)
            dotIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#EF4444"))
            baseSubtitle = "Due date has passed. Immediate action required."
        } else if (alarmType == TaskAlarmScheduler.ACTION_TRIGGER_URGENT_ALARM || isUrgentOrOverdueAlert) {
            txtBadgeLabel.text = "URGENT ALARM"
            txtBadgeLabel.setTextColor(Color.parseColor("#D97706"))
            layoutBadge.setBackgroundResource(R.drawable.bg_badge_connected)
            layoutBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FEF3C7"))
            dotIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F59E0B"))
            baseSubtitle = "Approaching minimum time buffer. Action required."
        } else {
            txtBadgeLabel.text = "TASK REMINDER"
            txtBadgeLabel.setTextColor(Color.parseColor("#4F46E5"))
            layoutBadge.setBackgroundResource(R.drawable.bg_badge_connected)
            layoutBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#EEF2FF"))
            dotIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#6366F1"))
            baseSubtitle = "Scheduled reminder alert."
        }
        txtTimeSubtitle.text = baseSubtitle

        // Start countdown timer for auto-timeout
        startTimeoutCountdown(txtTimeSubtitle, baseSubtitle)

        if (task != null) {
            val qResult = NotionFormulas.calculateQuadrant(task)
            txtQuadrant.text = if (qResult.isValid) "Q${qResult.qNumber} · ${qResult.action}" else "Unscheduled"
            val priorityFormatted = task.priority.name.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            txtPriority.text = "Priority: $priorityFormatted"
            txtMinTime.text = "Minimum Required Time: ${task.minimumTimeRequired}"

            val dateFormat = SimpleDateFormat("EEEE, MMM d · h:mm a", Locale.getDefault())
            if (task.dueDate != null) {
                txtDueTime.text = "Due: ${dateFormat.format(Date(task.dueDate!!))}"
            } else {
                txtDueTime.text = "No due date set"
            }

            if (!task.comment.isNullOrBlank()) {
                txtComment.visibility = View.VISIBLE
                txtComment.text = "Note: ${task.comment}"
            } else {
                txtComment.visibility = View.GONE
            }
        }

        // 1. Snooze 10 Minutes
        btnSnooze10m.setOnClickListener {
            cancelTimeout()
            snoozeAlarm(10 * 60 * 1000L, "10 minutes")
        }

        // 2. Reschedule Due Date (changes task.dueDate)
        btnRescheduleDueDate.setOnClickListener {
            cancelTimeout()
            showRescheduleDueDatePicker()
        }

        // 3. Add Custom Reminder (schedules a custom alarm without modifying dueDate)
        btnAddCustomReminder.setOnClickListener {
            cancelTimeout()
            showAddCustomReminderPicker()
        }

        // 4. Mark Done
        btnMarkDone.setOnClickListener {
            cancelTimeout()
            markTaskCompleted()
        }

        // 5. Dismiss
        btnDismiss.setOnClickListener {
            cancelTimeout()
            stopAlarmAudioAndVibration()
            dismissAlarmNotification()
            finish()
        }
    }

    private fun startTimeoutCountdown(txtTimeSubtitle: TextView, baseSubtitle: String) {
        timeoutHandler.removeCallbacksAndMessages(null)
        var secondsRemaining = alertTimeoutSeconds

        countdownRunnable = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return

                if (secondsRemaining > 0) {
                    txtTimeSubtitle.text = "$baseSubtitle\n(Auto-dismiss in ${secondsRemaining}s if unresponded)"
                    secondsRemaining--
                    timeoutHandler.postDelayed(this, 1000L)
                } else {
                    handleAlarmTimeout()
                }
            }
        }
        timeoutHandler.post(countdownRunnable!!)
    }

    private fun cancelTimeout() {
        timeoutHandler.removeCallbacksAndMessages(null)
        countdownRunnable = null
    }

    private fun handleAlarmTimeout() {
        if (isFinishing || isDestroyed) return

        stopAlarmAudioAndVibration()
        dismissAlarmNotification()

        // Post missed alarm notification because the user did not respond in time
        postMissedAlarmNotification()

        finish()
    }

    private fun postMissedAlarmNotification() {
        val taskTitle = currentTask?.title
            ?: intent?.getStringExtra(TaskAlarmScheduler.EXTRA_TASK_TITLE)
            ?: "Task Alert"

        val notifId = ((taskId ?: "alarm").hashCode() and 0x7FFFFFFF) + 5000

        val openAppIntent = Intent(this, com.example.tascyn.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            notifId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isUrgentOrOverdueAlert) "Missed Urgent Alarm: $taskTitle" else "Missed Alarm: $taskTitle"
        val message = "Alarm auto-stopped after ${alertTimeoutSeconds}s without response. Tap to view task."

        val notification = NotificationCompat.Builder(this, TaskAlarmScheduler.CHANNEL_ATTENTION_ID)
            .setSmallIcon(R.drawable.ic_bell_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(this).notify(notifId, notification)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun snoozeAlarm(durationMillis: Long, durationLabel: String) {
        stopAlarmAudioAndVibration()
        dismissAlarmNotification()

        val task = currentTask
        val tId = taskId ?: task?.id
        if (tId != null) {
            val snoozeTriggerTime = System.currentTimeMillis() + durationMillis
            TaskAlarmScheduler.scheduleCustomAlarm(
                context = this,
                taskId = tId,
                taskTitle = task?.title ?: "Snoozed Task",
                triggerAtMillis = snoozeTriggerTime,
                message = "Snoozed reminder for: ${task?.title ?: "Task"}",
                dueDate = task?.dueDate
            )
            Toast.makeText(this, "Alarm snoozed for $durationLabel.", Toast.LENGTH_LONG).show()
        }

        finish()
    }

    private fun showRescheduleDueDatePicker() {
        val cal = Calendar.getInstance()
        val currentDueDate = currentTask?.dueDate
        if (currentDueDate != null && currentDueDate > System.currentTimeMillis()) {
            cal.timeInMillis = currentDueDate
        } else {
            cal.add(Calendar.HOUR_OF_DAY, 2)
        }

        DatePickerDialog(this, { _, year, month, dayOfMonth ->
            cal.set(Calendar.YEAR, year)
            cal.set(Calendar.MONTH, month)
            cal.set(Calendar.DAY_OF_MONTH, dayOfMonth)

            TimePickerDialog(this, { _, hourOfDay, minute ->
                cal.set(Calendar.HOUR_OF_DAY, hourOfDay)
                cal.set(Calendar.MINUTE, minute)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)

                val selectedMillis = cal.timeInMillis
                if (selectedMillis <= System.currentTimeMillis()) {
                    Toast.makeText(this, "Please select a future date and time for the due date.", Toast.LENGTH_SHORT).show()
                    return@TimePickerDialog
                }

                stopAlarmAudioAndVibration()
                dismissAlarmNotification()

                val repository = TaskManagerRepository.get()
                repository.attachContext(this)
                val task = currentTask ?: (if (taskId != null) repository.getTaskById(taskId!!) else null)
                if (task != null) {
                    task.dueDate = selectedMillis
                    repository.updateTask(task)
                    TaskAlarmScheduler.scheduleTaskAlarms(this, task)
                }

                val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                Toast.makeText(this, "Due date rescheduled to ${fmt.format(Date(selectedMillis))}", Toast.LENGTH_LONG).show()
                finish()

            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()

        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun showAddCustomReminderPicker() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.HOUR_OF_DAY, 1)

        DatePickerDialog(this, { _, year, month, dayOfMonth ->
            cal.set(Calendar.YEAR, year)
            cal.set(Calendar.MONTH, month)
            cal.set(Calendar.DAY_OF_MONTH, dayOfMonth)

            TimePickerDialog(this, { _, hourOfDay, minute ->
                cal.set(Calendar.HOUR_OF_DAY, hourOfDay)
                cal.set(Calendar.MINUTE, minute)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)

                val selectedMillis = cal.timeInMillis
                if (selectedMillis <= System.currentTimeMillis()) {
                    Toast.makeText(this, "Please select a future time for the reminder.", Toast.LENGTH_SHORT).show()
                    return@TimePickerDialog
                }

                stopAlarmAudioAndVibration()
                dismissAlarmNotification()

                val repository = TaskManagerRepository.get()
                repository.attachContext(this)
                val task = currentTask ?: (if (taskId != null) repository.getTaskById(taskId!!) else null)
                if (task != null) {
                    task.remainderDate = selectedMillis
                    repository.updateTask(task)
                    TaskAlarmScheduler.scheduleCustomAlarm(
                        context = this,
                        taskId = task.id,
                        taskTitle = task.title,
                        triggerAtMillis = selectedMillis,
                        message = "Reminder: ${task.title}",
                        dueDate = task.dueDate
                    )
                } else if (taskId != null) {
                    TaskAlarmScheduler.scheduleCustomAlarm(
                        context = this,
                        taskId = taskId!!,
                        taskTitle = "Task Reminder",
                        triggerAtMillis = selectedMillis,
                        message = "Custom reminder for task",
                        dueDate = null
                    )
                }

                val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                Toast.makeText(this, "Reminder scheduled for ${fmt.format(Date(selectedMillis))}", Toast.LENGTH_LONG).show()
                finish()

            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()

        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun markTaskCompleted() {
        stopAlarmAudioAndVibration()
        dismissAlarmNotification()

        val repository = TaskManagerRepository.get()
        repository.attachContext(this)
        val task = currentTask ?: (if (taskId != null) repository.getTaskById(taskId!!) else null)
        if (task != null) {
            task.status = TaskStatus.DONE
            task.completedAt = System.currentTimeMillis()
            repository.updateTask(task)
            TaskAlarmScheduler.cancelTaskAlarms(this, task.id)

            // If an active session is running for this task, end & log it and cancel notification
            val active = repository.getActiveSession()
            if (active != null && active.taskId == task.id) {
                repository.endCurrentActiveSession()
                SessionNotificationManager.cancelSessionNotification(this)
            }
            Toast.makeText(this, "Task marked as completed!", Toast.LENGTH_SHORT).show()
        }

        finish()
    }

    private fun startAlarmAudioAndVibration() {
        try {
            var alarmUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlarmAlertActivity, alarmUri!!)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            val pattern = longArrayOf(0, 800, 400, 800, 400, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopAlarmAudioAndVibration() {
        releaseActivityWakeLock()
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            vibrator?.cancel()
            vibrator = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun dismissAlarmNotification() {
        val tId = taskId ?: currentTask?.id
        if (tId != null) {
            val notifId = (tId.hashCode() and 0x7FFFFFFF)
            try {
                NotificationManagerCompat.from(this).cancel(notifId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelTimeout()
        stopAlarmAudioAndVibration()
        releaseActivityWakeLock()
    }
}
