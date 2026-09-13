package com.example.tascyn.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.tascyn.MainActivity
import com.example.tascyn.R
import com.example.tascyn.data.TaskManagerRepository
import com.example.tascyn.ui.alarm.AlarmAlertActivity

class TaskAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "Tascyn:AlarmWakeLock"
        )
        wakeLock?.acquire(10000L) // hold wakelock for up to 10s to ensure screen and activity turn on
        var shouldKeepWakeLockForActivity = false

        try {
            val action = intent.action ?: return

            // Handle ending active session directly from notification action
            if (action == SessionNotificationManager.ACTION_END_ACTIVE_SESSION) {
                val repository = TaskManagerRepository.get()
                repository.attachContext(context)
                repository.endCurrentActiveSession()
                SessionNotificationManager.cancelSessionNotification(context)
                return
            }

            val taskId = intent.getStringExtra(TaskAlarmScheduler.EXTRA_TASK_ID) ?: return
            val alertMessage = intent.getStringExtra(TaskAlarmScheduler.EXTRA_ALERT_MESSAGE) ?: "Task requires your attention"
            val dueDate = intent.getLongExtra(TaskAlarmScheduler.EXTRA_DUE_DATE, 0L)
            val isAutoSnooze = intent.getBooleanExtra(TaskAlarmScheduler.EXTRA_IS_AUTO_SNOOZE, false)

            val repository = TaskManagerRepository.get()
            repository.attachContext(context)
            val task = repository.getTaskById(taskId)

            // If task was already completed or deleted, don't fire alarm
            if (task == null || task.isCompleted) {
                return
            }

            TaskAlarmScheduler.createNotificationChannels(context)

            val notifId = (taskId.hashCode() and 0x7FFFFFFF)

            if (action == TaskAlarmScheduler.ACTION_TRIGGER_ATTENTION) {
                // 1. PUSH NOTIFICATION FOR ATTENTION NEEDED
                val openAppIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val contentPendingIntent = PendingIntent.getActivity(
                    context,
                    notifId,
                    openAppIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val notification = NotificationCompat.Builder(context, TaskAlarmScheduler.CHANNEL_ATTENTION_ID)
                    .setSmallIcon(R.drawable.ic_bell_notification)
                    .setContentTitle("Attention Needed: ${task.title}")
                    .setContentText(alertMessage)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(alertMessage))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setContentIntent(contentPendingIntent)
                    .build()

                try {
                    NotificationManagerCompat.from(context).notify(notifId, notification)
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }

            } else {
                // 2. FULL-SCREEN ALARM ALERT FOR URGENT / OVERDUE / CUSTOM / SNOOZE
                val alarmIntent = Intent(context, AlarmAlertActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NO_USER_ACTION
                    putExtra(TaskAlarmScheduler.EXTRA_TASK_ID, taskId)
                    putExtra(TaskAlarmScheduler.EXTRA_TASK_TITLE, task.title)
                    putExtra(TaskAlarmScheduler.EXTRA_ALARM_TYPE, action)
                    putExtra(TaskAlarmScheduler.EXTRA_ALERT_MESSAGE, alertMessage)
                    putExtra(TaskAlarmScheduler.EXTRA_DUE_DATE, dueDate)
                    putExtra(TaskAlarmScheduler.EXTRA_IS_AUTO_SNOOZE, isAutoSnooze)
                }

                val fullScreenPendingIntent = PendingIntent.getActivity(
                    context,
                    notifId + 1,
                    alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val alarmTitle = when {
                    isAutoSnooze -> "AUTO-SNOOZED ALARM: ${task.title}"
                    action == TaskAlarmScheduler.ACTION_TRIGGER_OVERDUE_ALARM -> "TASK OVERDUE: ${task.title}"
                    action == TaskAlarmScheduler.ACTION_TRIGGER_URGENT_ALARM -> "URGENT ALARM: ${task.title}"
                    action == TaskAlarmScheduler.ACTION_TRIGGER_CUSTOM_ALARM -> "REMINDER: ${task.title}"
                    else -> "ALARM: ${task.title}"
                }

                val notification = NotificationCompat.Builder(context, TaskAlarmScheduler.CHANNEL_ALARM_ID)
                    .setSmallIcon(R.drawable.ic_bell_notification)
                    .setContentTitle(alarmTitle)
                    .setContentText(alertMessage)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(alertMessage))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setFullScreenIntent(fullScreenPendingIntent, true)
                    .setContentIntent(fullScreenPendingIntent)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .build()

                try {
                    NotificationManagerCompat.from(context).notify(notifId, notification)
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }

                // Also launch Activity directly
                try {
                    context.startActivity(alarmIntent)
                    shouldKeepWakeLockForActivity = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

        } finally {
            if (!shouldKeepWakeLockForActivity && wakeLock?.isHeld == true) {
                try {
                    wakeLock.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
