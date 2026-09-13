package com.example.tascyn.receiver

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import com.example.tascyn.data.Task
import com.example.tascyn.data.TaskManagerRepository
import com.example.tascyn.domain.NotionFormulas

object TaskAlarmScheduler {

    const val CHANNEL_ATTENTION_ID = "tascyn_attention_channel"
    const val CHANNEL_ATTENTION_NAME = "Tasks Needing Attention"
    const val CHANNEL_ALARM_ID = "tascyn_alarm_channel"
    const val CHANNEL_ALARM_NAME = "Urgent & Overdue Full-Screen Alarms"

    const val ACTION_TRIGGER_ATTENTION = "com.example.tascyn.ACTION_TRIGGER_ATTENTION"
    const val ACTION_TRIGGER_URGENT_ALARM = "com.example.tascyn.ACTION_TRIGGER_URGENT_ALARM"
    const val ACTION_TRIGGER_OVERDUE_ALARM = "com.example.tascyn.ACTION_TRIGGER_OVERDUE_ALARM"
    const val ACTION_TRIGGER_REMAINDER_ALARM = "com.example.tascyn.ACTION_TRIGGER_REMAINDER_ALARM"
    const val ACTION_TRIGGER_CUSTOM_ALARM = "com.example.tascyn.ACTION_TRIGGER_CUSTOM_ALARM"

    const val EXTRA_TASK_ID = "extra_task_id"
    const val EXTRA_TASK_TITLE = "extra_task_title"
    const val EXTRA_ALARM_TYPE = "extra_alarm_type"
    const val EXTRA_ALERT_MESSAGE = "extra_alert_message"
    const val EXTRA_DUE_DATE = "extra_due_date"
    const val EXTRA_IS_AUTO_SNOOZE = "extra_is_auto_snooze"

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. Attention Needed Channel (Heads-up Notification)
            val attentionChannel = NotificationChannel(
                CHANNEL_ATTENTION_ID,
                CHANNEL_ATTENTION_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when tasks enter Attention Needed window"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(attentionChannel)

            // 2. Urgent / Overdue Full-Screen Alarm Channel
            val alarmSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val alarmChannel = NotificationChannel(
                CHANNEL_ALARM_ID,
                CHANNEL_ALARM_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Plays alarm sound and full-screen alert for urgent & overdue tasks"
                setSound(alarmSoundUri, audioAttributes)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                setBypassDnd(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(alarmChannel)
        }
    }

    fun scheduleAllAlarms(context: Context) {
        createNotificationChannels(context)
        val repository = TaskManagerRepository.get()
        repository.attachContext(context)
        val allTasks = repository.getAllTasks()
        for (task in allTasks) {
            if (!task.isCompleted) {
                scheduleTaskAlarms(context, task)
            } else {
                cancelTaskAlarms(context, task.id)
            }
        }
    }

    fun scheduleTaskAlarms(context: Context, task: Task) {
        createNotificationChannels(context)
        if (task.isCompleted) {
            cancelTaskAlarms(context, task.id)
            return
        }

        val now = System.currentTimeMillis()
        val dueDate = task.dueDate

        if (dueDate != null) {
            val minMinutes = NotionFormulas.parseMinimumTimeToMinutes(task.minimumTimeRequired)
            val minMillis = (minMinutes.coerceAtLeast(1L)) * 60 * 1000L

            // 1. Attention Needed milestone: dueDate - 2 * minMillis
            val attentionTime = dueDate - (2 * minMillis)
            if (attentionTime > now) {
                scheduleAlarmIntent(
                    context = context,
                    taskId = task.id,
                    taskTitle = task.title,
                    action = ACTION_TRIGGER_ATTENTION,
                    triggerAtMillis = attentionTime,
                    requestCode = getRequestCode(task.id, 1),
                    message = "Task requires attention soon (Due in ${NotionFormulas.formatMinutesToDuration(2 * minMinutes)})",
                    dueDate = dueDate
                )
            }

            // 2. Urgent milestone: dueDate - 1 * minMillis
            val urgentTime = dueDate - minMillis
            if (urgentTime > now) {
                scheduleAlarmIntent(
                    context = context,
                    taskId = task.id,
                    taskTitle = task.title,
                    action = ACTION_TRIGGER_URGENT_ALARM,
                    triggerAtMillis = urgentTime,
                    requestCode = getRequestCode(task.id, 2),
                    message = "Task is URGENT! Less than minimum required time left (${NotionFormulas.formatMinutesToDuration(minMinutes)})",
                    dueDate = dueDate
                )
            }

            // 3. Overdue milestone: dueDate
            if (dueDate > now) {
                scheduleAlarmIntent(
                    context = context,
                    taskId = task.id,
                    taskTitle = task.title,
                    action = ACTION_TRIGGER_OVERDUE_ALARM,
                    triggerAtMillis = dueDate,
                    requestCode = getRequestCode(task.id, 3),
                    message = "Task is OVERDUE! Due time has arrived.",
                    dueDate = dueDate
                )
            }
        }

        // 4. Remainder Date milestone (if distinct from due date)
        val remainderDate = task.remainderDate
        if (remainderDate != null && remainderDate > now && remainderDate != dueDate) {
            scheduleAlarmIntent(
                context = context,
                taskId = task.id,
                taskTitle = task.title,
                action = ACTION_TRIGGER_REMAINDER_ALARM,
                triggerAtMillis = remainderDate,
                requestCode = getRequestCode(task.id, 4),
                message = "Reminder for task: ${task.title}",
                dueDate = dueDate ?: remainderDate
            )
        }
    }

    fun scheduleCustomAlarm(
        context: Context,
        taskId: String,
        taskTitle: String,
        triggerAtMillis: Long,
        message: String,
        dueDate: Long? = null,
        isAutoSnooze: Boolean = false
    ) {
        createNotificationChannels(context)
        scheduleAlarmIntent(
            context = context,
            taskId = taskId,
            taskTitle = taskTitle,
            action = ACTION_TRIGGER_CUSTOM_ALARM,
            triggerAtMillis = triggerAtMillis,
            requestCode = getRequestCode(taskId, 5),
            message = message,
            dueDate = dueDate ?: triggerAtMillis,
            isAutoSnooze = isAutoSnooze
        )
    }

    private fun scheduleAlarmIntent(
        context: Context,
        taskId: String,
        taskTitle: String,
        action: String,
        triggerAtMillis: Long,
        requestCode: Int,
        message: String,
        dueDate: Long,
        isAutoSnooze: Boolean = false
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_TASK_TITLE, taskTitle)
            putExtra(EXTRA_ALARM_TYPE, action)
            putExtra(EXTRA_ALERT_MESSAGE, message)
            putExtra(EXTRA_DUE_DATE, dueDate)
            putExtra(EXTRA_IS_AUTO_SNOOZE, isAutoSnooze)
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Use setAlarmClock for highest reliability to wake device from deep sleep & Doze
                val showIntent = if (action == ACTION_TRIGGER_ATTENTION) {
                    Intent(context, com.example.tascyn.MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                } else {
                    Intent(context, com.example.tascyn.ui.alarm.AlarmAlertActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        putExtra(EXTRA_TASK_ID, taskId)
                        putExtra(EXTRA_TASK_TITLE, taskTitle)
                        putExtra(EXTRA_ALARM_TYPE, action)
                        putExtra(EXTRA_ALERT_MESSAGE, message)
                        putExtra(EXTRA_DUE_DATE, dueDate)
                        putExtra(EXTRA_IS_AUTO_SNOOZE, isAutoSnooze)
                    }
                }
                val showPendingIntent = PendingIntent.getActivity(context, requestCode + 1000, showIntent, flags)
                val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, showPendingIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } catch (e: SecurityException) {
            // In case exact alarm permission isn't granted yet, fallback to setAndAllowWhileIdle
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cancelTaskAlarms(context: Context, taskId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        // Cancel all potential milestone request codes (1..5)
        for (i in 1..5) {
            val intent = Intent(context, TaskAlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(context, getRequestCode(taskId, i), intent, flags)
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun getRequestCode(taskId: String, milestone: Int): Int {
        return (taskId.hashCode() and 0x7FFFFFFF) * 7 + milestone
    }
}
