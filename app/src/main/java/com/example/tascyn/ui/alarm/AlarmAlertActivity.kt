package com.example.tascyn.ui.alarm

import android.app.DatePickerDialog
import android.app.KeyguardManager
import android.app.TimePickerDialog
import android.content.Context
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
import androidx.core.app.NotificationManagerCompat
import com.example.tascyn.R
import com.example.tascyn.data.Task
import com.example.tascyn.data.TaskManagerRepository
import com.example.tascyn.data.TaskStatus
import com.example.tascyn.domain.NotionFormulas
import com.example.tascyn.receiver.TaskAlarmScheduler
import java.text.SimpleDateFormat
import java.util.*

class AlarmAlertActivity : AppCompatActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    private var currentTask: Task? = null
    private var taskId: String? = null
    private var alarmType: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAndUnlockScreen()
        setContentView(R.layout.activity_alarm_alert)

        taskId = intent.getStringExtra(TaskAlarmScheduler.EXTRA_TASK_ID)
        alarmType = intent.getStringExtra(TaskAlarmScheduler.EXTRA_ALARM_TYPE)

        val repository = TaskManagerRepository.get()
        repository.attachContext(this)
        currentTask = if (taskId != null) repository.getTaskById(taskId!!) else null

        initUi()
        startAlarmAudioAndVibration()
    }

    private fun wakeAndUnlockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        }

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
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
        val btnReschedule = findViewById<Button>(R.id.btnAlarmReschedule)
        val btnMarkDone = findViewById<Button>(R.id.btnAlarmMarkDone)
        val btnDismiss = findViewById<Button>(R.id.btnAlarmDismiss)

        val task = currentTask
        val title = task?.title ?: intent.getStringExtra(TaskAlarmScheduler.EXTRA_TASK_TITLE) ?: "Task Alert"
        txtTaskTitle.text = title

        val isOverdue = alarmType == TaskAlarmScheduler.ACTION_TRIGGER_OVERDUE_ALARM || (task?.dueDate != null && System.currentTimeMillis() >= task.dueDate!!)

        if (isOverdue) {
            txtBadgeLabel.text = "TASK OVERDUE"
            txtBadgeLabel.setTextColor(Color.parseColor("#DC2626"))
            layoutBadge.setBackgroundResource(R.drawable.bg_badge_disconnected)
            dotIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#EF4444"))
            txtTimeSubtitle.text = "Due date has passed. Immediate action required."
        } else if (alarmType == TaskAlarmScheduler.ACTION_TRIGGER_URGENT_ALARM) {
            txtBadgeLabel.text = "URGENT ALARM"
            txtBadgeLabel.setTextColor(Color.parseColor("#D97706"))
            layoutBadge.setBackgroundResource(R.drawable.bg_badge_connected)
            layoutBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FEF3C7"))
            dotIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F59E0B"))
            txtTimeSubtitle.text = "Approaching minimum time buffer. Action required."
        } else {
            txtBadgeLabel.text = "TASK REMINDER"
            txtBadgeLabel.setTextColor(Color.parseColor("#4F46E5"))
            layoutBadge.setBackgroundResource(R.drawable.bg_badge_connected)
            layoutBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#EEF2FF"))
            dotIndicator.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#6366F1"))
            txtTimeSubtitle.text = "Scheduled reminder alert."
        }

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
            }
        }

        // 1. Snooze 10 Minutes
        btnSnooze10m.setOnClickListener {
            snoozeAlarm(10 * 60 * 1000L, "10 minutes")
        }

        // 2. Reschedule / Custom Reminder
        btnReschedule.setOnClickListener {
            showCustomReminderPicker()
        }

        // 3. Mark Done
        btnMarkDone.setOnClickListener {
            markTaskCompleted()
        }

        // 4. Dismiss
        btnDismiss.setOnClickListener {
            stopAlarmAudioAndVibration()
            dismissAlarmNotification()
            finish()
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

    private fun showCustomReminderPicker() {
        val cal = Calendar.getInstance()
        val currentDueDate = currentTask?.dueDate ?: currentTask?.remainderDate
        if (currentDueDate != null && currentDueDate > System.currentTimeMillis()) {
            cal.timeInMillis = currentDueDate
        } else {
            cal.add(Calendar.HOUR_OF_DAY, 1)
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
                    Toast.makeText(this, "Please select a future time for the reminder.", Toast.LENGTH_SHORT).show()
                    return@TimePickerDialog
                }

                stopAlarmAudioAndVibration()
                dismissAlarmNotification()

                // Update task and reschedule
                val task = currentTask
                if (task != null) {
                    val repository = TaskManagerRepository.get()
                    task.remainderDate = selectedMillis
                    task.dueDate = selectedMillis
                    repository.updateTask(task)
                    TaskAlarmScheduler.scheduleTaskAlarms(this, task)
                } else if (taskId != null) {
                    TaskAlarmScheduler.scheduleCustomAlarm(
                        context = this,
                        taskId = taskId!!,
                        taskTitle = "Rescheduled Task",
                        triggerAtMillis = selectedMillis,
                        message = "Custom reminder for task",
                        dueDate = selectedMillis
                    )
                }

                val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                Toast.makeText(this, "Alarm rescheduled for ${fmt.format(Date(selectedMillis))}", Toast.LENGTH_LONG).show()
                finish()

            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), false).show()

        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun markTaskCompleted() {
        stopAlarmAudioAndVibration()
        dismissAlarmNotification()

        val task = currentTask
        if (task != null) {
            val repository = TaskManagerRepository.get()
            task.status = TaskStatus.DONE
            task.completedAt = System.currentTimeMillis()
            repository.updateTask(task)
            TaskAlarmScheduler.cancelTaskAlarms(this, task.id)
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
        stopAlarmAudioAndVibration()
    }
}
