package com.mike.campusautofill

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mike.campusautofill.permissions.DeviceProfile
import com.mike.campusautofill.permissions.PermissionHelper
import com.mike.campusautofill.service.FillAccessibilityService
import com.mike.campusautofill.settings.UserSettings
import com.mike.campusautofill.vault.CredentialVault
import com.mike.campusautofill.diagnostics.DiagnosticLog

/**
 * 主界面：服务状态 + 凭据录入 + 按设备选择的权限清单 + 自测入口。
 * 凭据写入/读取均需先过 BiometricPrompt（每次验证），成功后在用户认证窗内完成加/解密。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnPause: Button
    private lateinit var editUsername: EditText
    private lateinit var editPassword: EditText
    private val statusHandler = Handler(Looper.getMainLooper())
    private val refreshAfterBinding = object : Runnable {
        override fun run() {
            refreshStatus()
            statusHandler.postDelayed(this, 1000)
        }
    }
    private var lastStatus: PermissionHelper.Status? = null
    private val exportLog = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            val app = applicationContext
            Thread {
                val result = runCatching {
                    DiagnosticLog.snapshot("export")
                    val report = DiagnosticLog.report()
                    val output = app.contentResolver.openOutputStream(uri, "wt")
                        ?: error("No output stream")
                    output.bufferedWriter().use { it.write(report) }
                }
                runOnUiThread {
                    Toast.makeText(app, if (result.isSuccess) R.string.diagnostics_exported
                        else R.string.diagnostics_failed, Toast.LENGTH_LONG).show()
                }
            }.start()
        }
    }
    private val notificationRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        buildChecklist()
        if (!granted) {
            Toast.makeText(this, R.string.notification_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets()
        UserSettings.load(this)

        statusText = findViewById(R.id.statusText)
        btnPause = findViewById(R.id.btnPause)
        editUsername = findViewById(R.id.editUsername)
        editPassword = findViewById(R.id.editPassword)

        findViewById<Button>(R.id.btnEnableService).setOnClickListener {
            manageAccessibility()
        }
        btnPause.setOnClickListener {
            UserSettings.setPaused(this, !UserSettings.isPaused)
            DiagnosticLog.snapshot("pause changed")
            refreshStatus()
        }
        findViewById<Button>(R.id.btnDisableService).setOnClickListener {
            FillAccessibilityService.instance?.disableSelf()
                ?: PermissionHelper.openAccessibility(this)
            DiagnosticLog.snapshot("disable requested")
            refreshStatus()
        }
        findViewById<Button>(R.id.btnExportLog).setOnClickListener {
            exportLog.launch("campus-diagnostics-${System.currentTimeMillis()}.txt")
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { onSave() }
        findViewById<Button>(R.id.btnTestRead).setOnClickListener { onTestRead() }
        findViewById<Button>(R.id.btnDelete).setOnClickListener { onDelete() }
        findViewById<Button>(R.id.btnSelfTest).setOnClickListener {
            FillAccessibilityService.debugScanSelfPackage = true
            startActivity(Intent(this, com.mike.campusautofill.ui.SelfTestActivity::class.java))
        }

    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        buildChecklist()
        DiagnosticLog.snapshot("main resumed")
        // Returning from Settings may precede the accessibility service binding.
        statusHandler.postDelayed(refreshAfterBinding, 750)
    }

    override fun onPause() {
        statusHandler.removeCallbacks(refreshAfterBinding)
        super.onPause()
    }

    /** targetSdk 36 沉浸式下内容会顶到状态栏/导航栏底下，按系统栏高度给根布局留边。 */
    private fun applySystemBarInsets() {
        val root = findViewById<android.view.View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    // ── 状态 ──────────────────────────────────────────────────────────────

    private fun refreshStatus() {
        val permissions = PermissionHelper.status(this)
        if (lastStatus != permissions) {
            DiagnosticLog.snapshot("permissions changed")
            lastStatus = permissions
            buildChecklist()
        }
        findViewById<Button>(R.id.btnEnableService).setText(when {
            !permissions.accessibilityEnabled -> R.string.btn_enable_service
            !permissions.serviceConnected -> R.string.btn_reconnect_service
            else -> R.string.btn_manage_settings
        })
        findViewById<Button>(R.id.btnDisableService).visibility =
            if (permissions.accessibilityEnabled) View.VISIBLE else View.GONE
        val paused = UserSettings.isPaused
        when {
            !permissions.accessibilityEnabled -> {
                statusText.setText(R.string.status_off)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.status_off))
                btnPause.visibility = View.GONE
            }
            !permissions.serviceConnected -> {
                statusText.setText(R.string.status_disconnected)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.status_off))
                btnPause.visibility = View.GONE
            }
            paused -> {
                statusText.setText(R.string.status_paused)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.status_pause))
                btnPause.visibility = View.VISIBLE
                btnPause.setText(R.string.btn_resume)
            }
            else -> {
                statusText.setText(R.string.status_running)
                statusText.setTextColor(ContextCompat.getColor(this, R.color.status_on))
                btnPause.visibility = View.VISIBLE
                btnPause.setText(R.string.btn_pause)
            }
        }
    }

    private fun manageAccessibility() {
        val state = PermissionHelper.status(this)
        if (state.accessibilityEnabled && !state.serviceConnected) {
            DiagnosticLog.snapshot("reconnect guide")
            AlertDialog.Builder(this)
                .setTitle(R.string.reconnect_title)
                .setMessage(R.string.reconnect_message)
                .setPositiveButton(R.string.btn_go_settings) { _, _ -> PermissionHelper.openAccessibility(this) }
                .setNeutralButton(R.string.btn_background_settings) { _, _ -> PermissionHelper.openAppDetails(this) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else PermissionHelper.openAccessibility(this)
    }

    // ── 凭据（全部经 BiometricPrompt） ────────────────────────────────────

    private fun onSave() {
        val user = editUsername.text.toString().trim()
        val pass = editPassword.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, R.string.input_required, Toast.LENGTH_SHORT).show()
            return
        }
        authenticate {
            try {
                CredentialVault.save(this, user, pass)
                editPassword.setText("")
                Toast.makeText(this, R.string.saved_ok, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.saved_fail, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onTestRead() {
        if (!CredentialVault.hasSaved(this)) {
            Toast.makeText(this, R.string.read_none, Toast.LENGTH_SHORT).show()
            return
        }
        authenticate {
            val pass = CredentialVault.loadPassword(this)
            val user = CredentialVault.loadUsername(this).orEmpty()
            if (pass == null) {
                Toast.makeText(this, getString(R.string.read_fail, ""), Toast.LENGTH_LONG).show()
            } else {
                // 绝不显示密码本体，只证明可解密
                Toast.makeText(this, getString(R.string.read_ok, user), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onDelete() {
        if (!CredentialVault.hasSaved(this)) {
            Toast.makeText(this, R.string.read_none, Toast.LENGTH_SHORT).show()
            return
        }
        authenticate {
            CredentialVault.delete(this)
            editUsername.setText("")
            editPassword.setText("")
            Toast.makeText(this, R.string.deleted_ok, Toast.LENGTH_SHORT).show()
        }
    }

    private inline fun authenticate(crossinline onOk: () -> Unit) {
        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators)
            != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            Toast.makeText(this, R.string.gate_no_lock, Toast.LENGTH_LONG).show()
            return
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.gate_biometric_title))
            .setSubtitle(getString(R.string.gate_biometric_subtitle))
            .setAllowedAuthenticators(authenticators)
            .build()
        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            }
        ).authenticate(promptInfo)
    }

    // ── 权限状态与设备设置引导 ─────────────────────────────────────────

    private fun buildChecklist() {
        val scroll = findViewById<ScrollView>(R.id.mainScroll)
        val previousScroll = scroll.scrollY
        val container = findViewById<LinearLayout>(R.id.checklistContainer)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val profile = DeviceProfile.detect(Build.MANUFACTURER, Build.BRAND)
        val permissions = PermissionHelper.status(this)
        findViewById<TextView>(R.id.deviceGuidance).text = getString(
            R.string.device_guidance, profile.label, Build.VERSION.RELEASE
        )

        data class Item(
            val title: Int, val desc: Int, val granted: Boolean?, val action: (() -> Unit)?
        )
        val items = mutableListOf<Item>()
        if (Build.VERSION.SDK_INT >= 33) {
            items += Item(R.string.checklist_restricted_title,
                R.string.checklist_restricted_desc, null) { PermissionHelper.openAppDetails(this) }
        }
        items += Item(R.string.checklist_a11y_title, R.string.checklist_a11y_desc,
            permissions.accessibilityEnabled) { manageAccessibility() }
        items += Item(R.string.checklist_overlay_title, R.string.checklist_overlay_desc,
            permissions.overlayGranted) { PermissionHelper.openOverlay(this) }
        items += Item(R.string.checklist_battery_title, R.string.checklist_battery_desc,
            permissions.batteryExempt) { PermissionHelper.openBattery(this) }
        items += Item(R.string.checklist_vendor_title, profile.guidance, null) {
            PermissionHelper.openVendorBackground(this, profile)
        }
        if (profile != DeviceProfile.GENERIC && profile != DeviceProfile.SAMSUNG) {
            items += Item(R.string.checklist_lock_title, R.string.checklist_lock_desc, null, null)
        }
        items += Item(R.string.checklist_notify_title, R.string.checklist_notify_desc,
            permissions.notificationsGranted) { requestNotifications() }

        for (item in items) {
            val row = inflater.inflate(R.layout.item_checklist, container, false)
            row.findViewById<TextView>(R.id.itemTitle).setText(item.title)
            row.findViewById<TextView>(R.id.itemDesc).setText(item.desc)
            row.findViewById<TextView>(R.id.itemStatus).apply {
                setText(when (item.granted) {
                    true -> R.string.permission_granted
                    false -> R.string.permission_not_granted
                    null -> R.string.permission_manual
                })
                setTextColor(ContextCompat.getColor(this@MainActivity, when (item.granted) {
                    true -> R.color.status_on
                    false -> R.color.status_pause
                    null -> R.color.text_secondary
                }))
            }
            val btn = row.findViewById<Button>(R.id.itemGo)
            if (item.action == null) {
                btn.visibility = View.GONE
            } else {
                if (item.granted == true) btn.setText(R.string.btn_manage_settings)
                btn.setOnClickListener { item.action.invoke() }
            }
            container.addView(row)
        }
        scroll.post { scroll.scrollTo(0, previousScroll) }
    }

    private fun requestNotifications() {
        val prefs = getSharedPreferences("permission_guide", MODE_PRIVATE)
        val denied = Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
        if (denied && (!prefs.getBoolean("notification_requested", false) ||
                    shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            prefs.edit().putBoolean("notification_requested", true).apply()
            notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            PermissionHelper.openNotifications(this)
        }
    }
}
