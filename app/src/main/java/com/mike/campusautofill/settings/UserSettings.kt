package com.mike.campusautofill.settings

import android.content.Context

/** 暂停总开关：暂停时服务第一行就返回（真零开销）。 */
object UserSettings {

    private const val PREFS = "settings"
    private const val K_PAUSED = "paused"

    @Volatile
    var isPaused: Boolean = false

    fun load(context: Context) {
        isPaused = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(K_PAUSED, false)
    }

    fun setPaused(context: Context, paused: Boolean) {
        isPaused = paused
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(K_PAUSED, paused).apply()
    }
}
