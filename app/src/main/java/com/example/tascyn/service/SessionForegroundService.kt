package com.example.tascyn.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.tascyn.data.TaskManagerRepository
import com.example.tascyn.receiver.SessionNotificationManager

class SessionForegroundService : Service() {

    companion object {
        const val ACTION_START_SERVICE = "com.example.tascyn.service.ACTION_START_SESSION_SERVICE"
        const val ACTION_STOP_SERVICE = "com.example.tascyn.service.ACTION_STOP_SESSION_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, SessionForegroundService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SessionForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private val repository = TaskManagerRepository.get()
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    private val tickerRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val activeState = repository.getActiveSessionState()
            if (activeState == null) {
                stopSelfAndNotification()
                return
            }
            SessionNotificationManager.showOrUpdateSessionNotification(this@SessionForegroundService, activeState)
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository.attachContext(this)
        SessionNotificationManager.createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                stopSelfAndNotification()
                return START_NOT_STICKY
            }
            ACTION_START_SERVICE, null -> {
                val activeState = repository.getActiveSessionState()
                if (activeState == null) {
                    stopSelfAndNotification()
                    return START_NOT_STICKY
                }
                val notification = SessionNotificationManager.buildSessionNotification(this, activeState)
                startForeground(SessionNotificationManager.SESSION_NOTIFICATION_ID, notification)
                if (!isRunning) {
                    isRunning = true
                    handler.removeCallbacks(tickerRunnable)
                    handler.post(tickerRunnable)
                }
            }
        }
        return START_STICKY
    }

    private fun stopSelfAndNotification() {
        isRunning = false
        handler.removeCallbacks(tickerRunnable)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        SessionNotificationManager.cancelSessionNotification(this)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(tickerRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
