package com.a0soft.gphone.camonitor.data.camera

import android.content.Context

/** Non-sensitive feature flag, persistent across Android process restarts. */
object SmartEventSettings {
    private const val PREFS = "cammonitor_camera_features"
    private const val KEY = "smart_event_monitoring_enabled"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, value).apply()
    }
}
