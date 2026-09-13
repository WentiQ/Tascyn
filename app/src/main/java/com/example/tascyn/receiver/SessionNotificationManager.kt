package com.example.tascyn.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.tascyn.MainActivity
import com.example.tascyn.R
import com.example.tascyn.data.ActiveSessionState
import com.example.tascyn.service.SessionForegroundService

object SessionNotificationManager {

    const val CHANNEL_SESSION_ID = "tascyn_active_session_channel"
    const val CHANNEL_SESSION_NAME = "Active Focus Session Timer"
    const val SESSION_NOTIFICATION_ID = 88888

    const val ACTION_END_ACTIVE_SESSION = "com.example.tascyn.ACTION_END_ACTIVE_SESSION"

    fun startSessionService(context: Context) {
        SessionForegroundService.start(context)
    }

    fun stopSessionService(context: Context) {
        SessionForegroundService.stop(context)
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_SESSION_ID,
                CHANNEL_SESSION_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time live timer for ongoing working session"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun buildSessionNotification(context: Context, state: ActiveSessionState): Notification {
        createNotificationChannel(context)

        val taskTitle = state.task?.title ?: state.session.title
        val sessionTitle = "Focus Session · $taskTitle"

        val timeContentText = if (state.remainingSeconds != null) {
            if (state.isOvertime) {
                "Overtime: +${state.formattedRemaining}  (Total: ${state.formattedElapsed})"
            } else {
                "${state.formattedRemaining} remaining  (Elapsed: ${state.formattedElapsed})"
            }
        } else {
            "Elapsed Time: ${state.formattedElapsed}"
        }

        // 1. Intent to open MainActivity directly to Sessions tab
        val openSessionsIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_NAV_TAB", "SESSIONS")
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            SESSION_NOTIFICATION_ID,
            openSessionsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 2. Action to End the Active Session directly from Notification
        val endSessionIntent = Intent(context, TaskAlarmReceiver::class.java).apply {
            action = ACTION_END_ACTIVE_SESSION
        }
        val endPendingIntent = PendingIntent.getBroadcast(
            context,
            SESSION_NOTIFICATION_ID + 1,
            endSessionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_SESSION_ID)
            .setSmallIcon(R.drawable.ic_timer_precision)
            .setContentTitle(sessionTitle)
            .setContentText(timeContentText)
            .setSubText("Live Session")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_stop_precision, "End Session", endPendingIntent)
            .addAction(R.drawable.ic_play_precision, "Open Timer", openPendingIntent)
            .build()
    }

    fun showOrUpdateSessionNotification(context: Context, state: ActiveSessionState) {
        val notification = buildSessionNotification(context, state)
        try {
            NotificationManagerCompat.from(context).notify(SESSION_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cancelSessionNotification(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(SESSION_NOTIFICATION_ID)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
