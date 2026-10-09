package com.mike.campusautofill

import android.app.Application
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import com.mike.campusautofill.diagnostics.DiagnosticLog
import com.mike.campusautofill.settings.UserSettings

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        UserSettings.load(this)
        DiagnosticLog.init(this)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                DiagnosticLog.snapshot("power change ${intent.action}")
            }
        }, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private fun log(a: Activity, state: String) = DiagnosticLog.i("Activity", "${a.javaClass.simpleName} $state")
            override fun onActivityCreated(a: Activity, b: Bundle?) { log(a, "created restored=${b != null}") }
            override fun onActivityStarted(a: Activity) { log(a, "started") }
            override fun onActivityResumed(a: Activity) { log(a, "resumed") }
            override fun onActivityPaused(a: Activity) { log(a, "paused") }
            override fun onActivityStopped(a: Activity) { log(a, "stopped") }
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) { log(a, "saved state") }
            override fun onActivityDestroyed(a: Activity) { log(a, "destroyed finishing=${a.isFinishing}") }
        })
    }
}
