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

        private const val KEY_THEME_MODE = "app_theme_mode"

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        @Volatile
        private var instance: AppSettingsManager? = null

        fun getInstance(context: Context): AppSettingsManager {
            return instance ?: synchronized(this) {
                instance ?: AppSettingsManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext: Context = context.applicationContext

    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value).apply()

    fun applyTheme(mode: String = themeMode, context: Context? = null) {
        val nightMode = when (mode) {
            THEME_LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)

        // Ensure MainActivity component is enabled without killing or closing the app
        val targetCtx = context ?: appContext
        try {
            val pm = targetCtx.packageManager
            val pkg = targetCtx.packageName
            val mainComp = android.content.ComponentName(pkg, "$pkg.MainActivity")
            pm.setComponentEnabledSetting(
                mainComp,
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            // ignore
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
        get() = prefs.getString(KEY_AI_MODEL, "gemini-2.5-flash") ?: "gemini-2.5-flash"
        set(value) = prefs.edit().putString(KEY_AI_MODEL, value.trim()).apply()

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
