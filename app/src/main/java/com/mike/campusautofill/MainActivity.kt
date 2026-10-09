package com.mike.campusautofill

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.mike.campusautofill.diagnostics.DiagnosticLog
import com.mike.campusautofill.permissions.DeviceProfile
import com.mike.campusautofill.permissions.PermissionHelper
import com.mike.campusautofill.service.FillAccessibilityService
import com.mike.campusautofill.settings.UserSettings
import com.mike.campusautofill.ui.*
import com.mike.campusautofill.vault.CredentialVault

/** Platform actions stay in the Activity; composables render state and dispatch actions. */
class MainActivity : AppCompatActivity() {
    private var permissions by mutableStateOf(PermissionHelper.Status(false, false, false, false, false))
    private var paused by mutableStateOf(false)
    private var savedUser by mutableStateOf<String?>(null)
    private var page by mutableStateOf("home")
    private var dialog by mutableStateOf<String?>(null)
    private var username by mutableStateOf("")
    private var password by mutableStateOf("")
    private var userError by mutableStateOf(false)
    private var passError by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var dynamicColor by mutableStateOf(false)
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { refreshState(); handler.postDelayed(this, 1000) }
    }
    private val exportLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val app = applicationContext
            busy = true
            Thread {
                val result = runCatching {
                    DiagnosticLog.snapshot("export")
                    val report = DiagnosticLog.report()
                    (app.contentResolver.openOutputStream(uri, "wt") ?: error("No output stream"))
                        .bufferedWriter().use { it.write(report) }
                }
                runOnUiThread {
                    busy = false
                    Toast.makeText(app, if (result.isSuccess) R.string.diagnostics_exported
                        else R.string.diagnostics_failed, Toast.LENGTH_LONG).show()
                }
            }.start()
        }
    }
    private val notificationRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshState()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UserSettings.load(this)
        page = savedInstanceState?.getString("page") ?: "home"
        username = CredentialVault.loadUsername(this).orEmpty()
        dynamicColor = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("dynamic_color", false)
        refreshState()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    dialog != null -> dialog = null
                    page != "home" -> { password = ""; page = "home" }
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })
        setContent {
            CampusTheme(dynamicColor = dynamicColor) {
                CampusApp(
                    page = page, permissions = permissions, paused = paused, savedUser = savedUser,
                    username = username, password = password, userError = userError, passError = passError,
                    busy = busy, dynamicColor = dynamicColor, dynamicColorSupported = Build.VERSION.SDK_INT >= 31,
                    settings = settingRows(), device = "${DeviceProfile.detect(Build.MANUFACTURER, Build.BRAND).label} · Android ${Build.VERSION.RELEASE}",
                    onPage = { destination ->
                        if (destination == "editor") {
                            username = savedUser.orEmpty(); password = ""; userError = false; passError = false
                        } else password = ""
                        page = destination
                    },
                    onUsername = { username = it; userError = false }, onPassword = { password = it; passError = false },
                    onService = ::manageAccessibility,
                    onPause = {
                        UserSettings.setPaused(this, !paused)
                        DiagnosticLog.snapshot("pause changed"); refreshState()
                    },
                    onDisable = {
                        FillAccessibilityService.instance?.disableSelf() ?: PermissionHelper.openAccessibility(this)
                        DiagnosticLog.snapshot("disable requested"); refreshState()
                    },
                    onSave = ::saveCredentials, onVerify = ::verifyCredentials, onDelete = { dialog = "delete" },
                    onSelfTest = {
                        FillAccessibilityService.debugScanSelfPackage = true
                        startActivity(Intent(this, SelfTestActivity::class.java))
                    },
                    onExport = { exportLog.launch("campus-diagnostics-${System.currentTimeMillis()}.txt") },
                    onDynamicColor = {
                        dynamicColor = it
                        getSharedPreferences("appearance", MODE_PRIVATE).edit().putBoolean("dynamic_color", it).apply()
                    }
                )
                when (dialog) {
                    "recover" -> RecoveryDialog(onDismiss = { dialog = null }, onSettings = {
                        dialog = null; PermissionHelper.openAccessibility(this)
                    }, onBackground = { dialog = null; PermissionHelper.openAppDetails(this) })
                    "delete" -> DeleteAccountDialog(onDismiss = { dialog = null }, onDelete = {
                        dialog = null
                        authenticate {
                            CredentialVault.delete(this); username = ""; password = ""; refreshState()
                            toast(R.string.deleted_ok)
                        }
                    })
                }
            }
        }
    }
    override fun onResume() {
        super.onResume(); refreshState(); DiagnosticLog.snapshot("main resumed")
        handler.removeCallbacks(refresh); handler.postDelayed(refresh, 750)
    }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onStop() { if (!busy) password = ""; super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page) // Never save password drafts in instance state.
        super.onSaveInstanceState(outState)
    }
    private fun refreshState() {
        val next = PermissionHelper.status(this)
        if (next != permissions) DiagnosticLog.snapshot("permissions changed")
        permissions = next; paused = UserSettings.isPaused
        savedUser = if (CredentialVault.hasSaved(this)) CredentialVault.loadUsername(this) else null
    }
    private fun manageAccessibility() {
        if (permissions.accessibilityEnabled && !permissions.serviceConnected) {
            DiagnosticLog.snapshot("reconnect guide"); dialog = "recover"
        } else PermissionHelper.openAccessibility(this)
    }
    private fun saveCredentials() {
        userError = username.trim().isEmpty(); passError = password.isEmpty()
        if (userError || passError) return
        authenticate {
            try {
                CredentialVault.save(this, username.trim(), password)
                password = ""; page = "home"; refreshState(); toast(R.string.saved_ok)
            } catch (error: Exception) {
                DiagnosticLog.e("Vault", "save failed", error); toast(R.string.save_failed_short)
            }
        }
    }
    private fun verifyCredentials() {
        authenticate { toast(if (CredentialVault.loadPassword(this) != null) R.string.verify_ok else R.string.verify_failed) }
    }
    private fun authenticate(onSuccess: () -> Unit) {
        if (busy) return
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            toast(R.string.gate_no_lock); return
        }
        busy = true
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(getString(R.string.gate_biometric_title))
            .setAllowedAuthenticators(authenticators).build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { busy = false; onSuccess() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { busy = false }
        }).authenticate(info)
    }
    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun settingRows(): List<SettingRow> {
        val profile = DeviceProfile.detect(Build.MANUFACTURER, Build.BRAND)
        val rows = mutableListOf(
            SettingRow("无障碍", "识别登录页", permissions.accessibilityEnabled, ::manageAccessibility),
            SettingRow("悬浮窗", "显示填充确认", permissions.overlayGranted) { PermissionHelper.openOverlay(this) },
            SettingRow("后台运行", "减少省电造成的中断", permissions.batteryExempt) { PermissionHelper.openBattery(this) },
            SettingRow("系统后台设置", "自启动与后台限制", null) { PermissionHelper.openVendorBackground(this, profile) }
        )
        if (Build.VERSION.SDK_INT >= 33) rows += SettingRow("受限制的设置", "无障碍打不开时检查", null) {
            PermissionHelper.openAppDetails(this)
        }
        rows += SettingRow("通知", "可选", permissions.notificationsGranted, ::requestNotifications)
        return rows
    }
    private fun requestNotifications() {
        val prefs = getSharedPreferences("permission_guide", MODE_PRIVATE)
        val denied = Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (denied && (!prefs.getBoolean("notification_requested", false) ||
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            prefs.edit().putBoolean("notification_requested", true).apply()
            notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else PermissionHelper.openNotifications(this)
    }
}
