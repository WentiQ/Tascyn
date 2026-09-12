package com.example.tascyn.data

import android.content.Context
import android.content.SharedPreferences

class AppSettingsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("tascyn_app_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GOOGLE_ACCOUNT = "google_account"
        private const val KEY_IS_GOOGLE_SYNCED = "is_google_synced"
        private const val KEY_AI_MODEL = "ai_model"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_DEFAULT_SESSION_MIN = "default_session_minutes"

        @Volatile
        private var instance: AppSettingsManager? = null

        fun getInstance(context: Context): AppSettingsManager {
            return instance ?: synchronized(this) {
                instance ?: AppSettingsManager(context.applicationContext).also { instance = it }
            }
        }
    }

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API_KEY, value.trim()).apply()

    var googleAccountEmail: String
        get() = prefs.getString(KEY_GOOGLE_ACCOUNT, "dinesh@gmail.com") ?: "dinesh@gmail.com"
        set(value) = prefs.edit().putString(KEY_GOOGLE_ACCOUNT, value.trim()).apply()

    var isGoogleSynced: Boolean
        get() = prefs.getBoolean(KEY_IS_GOOGLE_SYNCED, true)
        set(value) = prefs.edit().putBoolean(KEY_IS_GOOGLE_SYNCED, value).apply()

    var selectedAiModel: String
        get() = prefs.getString(KEY_AI_MODEL, "gemini-3.7-flash") ?: "gemini-3.7-flash"
        set(value) = prefs.edit().putString(KEY_AI_MODEL, value).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()

    var defaultSessionMinutes: Int
        get() = prefs.getInt(KEY_DEFAULT_SESSION_MIN, 45)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_SESSION_MIN, value).apply()

    fun hasValidGeminiKey(): Boolean {
        return geminiApiKey.isNotBlank() && geminiApiKey.length >= 5
    }
}
