package com.mike.campusautofill.permissions

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mike.campusautofill.R
import com.mike.campusautofill.service.FillAccessibilityService
import com.mike.campusautofill.diagnostics.DiagnosticLog

object PermissionHelper {
    data class Status(
        val accessibilityEnabled: Boolean,
        val serviceConnected: Boolean,
        val overlayGranted: Boolean,
        val notificationsGranted: Boolean,
        val batteryExempt: Boolean
    )

    fun status(context: Context): Status {
        val expected = ComponentName(context, FillAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':').any { ComponentName.unflattenFromString(it) == expected }
        val notificationPermission = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return Status(
            accessibilityEnabled = enabled,
            serviceConnected = FillAccessibilityService.instance != null,
            overlayGranted = Settings.canDrawOverlays(context),
            notificationsGranted = notificationPermission &&
                NotificationManagerCompat.from(context).areNotificationsEnabled(),
            batteryExempt = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName)
        )
    }

    private fun appDetails(context: Context) = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")
    )

    fun openAppDetails(activity: Activity) = open(activity, appDetails(activity))

    fun openAccessibility(activity: Activity) = open(
        activity, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    )

    fun openOverlay(activity: Activity) = open(
        activity,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")),
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
    )

    fun openNotifications(activity: Activity) = open(
        activity, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
    )

    fun openBattery(activity: Activity) {
        val exempt = activity.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(activity.packageName)
        if (exempt) {
            open(activity, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } else {
            open(activity,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${activity.packageName}")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    fun openVendorBackground(activity: Activity, profile: DeviceProfile) {
        if (profile == DeviceProfile.SAMSUNG) {
            // Official Samsung application-management deeplink; unavailable versions fall back.
            open(activity, Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")
                .setPackage("com.samsung.android.lool").putExtra("activity_type", 2))
        } else {
            openAppDetails(activity)
        }
    }

    /** OEM pages may exist but reject callers with a signature-only permission. */
    private fun open(activity: Activity, vararg preferred: Intent) {
        for (intent in preferred.toList() + appDetails(activity) + Intent(Settings.ACTION_SETTINGS)) {
            try {
                activity.startActivity(intent)
                DiagnosticLog.i("Settings", "opened action=${intent.action}")
                return
            } catch (_: ActivityNotFoundException) {
                DiagnosticLog.w("Settings", "missing action=${intent.action}")
                // Try the next standard entry point.
            } catch (_: SecurityException) {
                DiagnosticLog.w("Settings", "denied action=${intent.action}")
                // A vendor may protect even an otherwise resolvable settings page.
            }
        }
        Toast.makeText(activity, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
    }
}
