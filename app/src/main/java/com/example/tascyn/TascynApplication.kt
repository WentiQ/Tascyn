package com.example.tascyn

import android.app.Application
import com.example.tascyn.data.AppSettingsManager
import com.example.tascyn.data.TaskManagerRepository
import com.example.tascyn.receiver.TaskAlarmScheduler

class TascynApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 1. Immediately apply the saved theme (Light, Dark, or System) before any activity is created
        val settings = AppSettingsManager.getInstance(this)
        settings.applyTheme()

        // 2. Initialize repository and notification channels
        val repository = TaskManagerRepository.get()
        repository.attachContext(this)
        TaskAlarmScheduler.createNotificationChannels(this)
    }
}
