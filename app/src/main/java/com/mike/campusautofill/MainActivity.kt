package com.mike.campusautofill

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mike.campusautofill.service.FillAccessibilityService
import com.mike.campusautofill.settings.UserSettings
import com.mike.campusautofill.vault.CredentialVault

/**
 * 主界面：服务状态 + 凭据录入 + ColorOS 设置清单 + 自测入口。
 * 凭据写入/读取均需先过 BiometricPrompt（每次验证），成功后在用户认证窗内完成加/解密。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var btnPause: Button
    private lateinit var editUsername: EditText
    private lateinit var editPassword: EditText

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
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnPause.setOnClickListener {
            UserSettings.setPaused(this, !UserSettings.isPaused)
            refreshStatus()
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener { onSave() }
        findViewById<Button>(R.id.btnTestRead).setOnClickListener { onTestRead() }
        findViewById<Button>(R.id.btnDelete).setOnClickListener { onDelete() }
        findViewById<Button>(R.id.btnSelfTest).setOnClickListener {
            FillAccessibilityService.debugScanSelfPackage = true
            startActivity(Intent(this, com.mike.campusautofill.ui.SelfTestActivity::class.java))
        }

        buildChecklist()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
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
        val serviceOn = isAccessibilityServiceEnabled()
        val paused = UserSettings.isPaused
        when {
            !serviceOn -> {
                statusText.setText(R.string.status_off)
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

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, FillAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == expected
        }
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

    // ── ColorOS 设置清单（代码构建行，一键跳转） ─────────────────────────

    private fun buildChecklist() {
        val container = findViewById<LinearLayout>(R.id.checklistContainer)
        val inflater = LayoutInflater.from(this)

        data class Item(val title: Int, val desc: Int, val action: (() -> Unit)?)
        // 所有跳转均经真机实测：应用详情/无障碍/悬浮窗/通知可达；OPPO 自启动/耗电子页
        // 有私有权限（OPLUS_COMPONENT_SAFE）连 ADB 都拒绝，只能给精确手动文案。
        val items = listOf(
            Item(R.string.checklist_restricted_title, R.string.checklist_restricted_desc) {
                openAppDetails()
            },
            Item(R.string.checklist_autostart_title, R.string.checklist_autostart_desc, null),
            Item(R.string.checklist_battery_title, R.string.checklist_battery_desc) {
                openAppDetails()
            },
            Item(R.string.checklist_lock_title, R.string.checklist_lock_desc, null),
            Item(R.string.checklist_a11y_title, R.string.checklist_a11y_desc) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            Item(R.string.checklist_overlay_title, R.string.checklist_overlay_desc) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            },
            Item(R.string.checklist_notify_title, R.string.checklist_notify_desc) {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
        )

        for (item in items) {
            val row = inflater.inflate(R.layout.item_checklist, container, false)
            row.findViewById<TextView>(R.id.itemTitle).setText(item.title)
            row.findViewById<TextView>(R.id.itemDesc).setText(item.desc)
            val btn = row.findViewById<Button>(R.id.itemGo)
            if (item.action == null) {
                btn.visibility = View.GONE
            } else {
                btn.setOnClickListener { item.action.invoke() }
            }
            container.addView(row)
        }
    }

    private fun openAppDetails() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$packageName"))
        )
    }
}
