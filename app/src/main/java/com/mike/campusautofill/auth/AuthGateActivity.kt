package com.mike.campusautofill.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.mike.campusautofill.R
import com.mike.campusautofill.service.FillCoordinator
import com.mike.campusautofill.vault.CredentialVault

/**
 * 透明中转 Activity，两阶段：
 *   ① 确认卡片「是否使用账号 xxx 的密码填充？」［填充］［取消］
 *   ② 点［填充］→ BiometricPrompt（指纹或锁屏密码，每次填充都验）
 * 成功 → 解密凭据 → 回调 FillCoordinator → finish。
 * 取消/划掉 → 回调 onAuthCancelled(byUser=true)（服务侧记录"本页不再弹"）。
 */
class AuthGateActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_HAS_EXTRA = "has_extra"

        fun createIntent(context: Context, sessionId: String, hasExtra: Boolean) =
            Intent(context, AuthGateActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_HAS_EXTRA, hasExtra)
            }
    }

    private var sessionId: String? = null
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 锁屏之上仍可显示（验证成功后需解密；用户刚通过认证，处于解锁状态，正常不会触顶）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_auth_gate)

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        if (sessionId == null || FillCoordinator.get(sessionId!!) == null) {
            finishQuietly()
            return
        }

        val username = CredentialVault.loadUsername(this).orEmpty()
        val msg = findViewById<TextView>(R.id.gateMessage)
        msg.text = if (username.isNotEmpty()) {
            getString(R.string.gate_message, username)
        } else {
            getString(R.string.gate_message_no_user)
        }
        if (intent.getBooleanExtra(EXTRA_HAS_EXTRA, false)) {
            findViewById<TextView>(R.id.gateExtraHint).visibility = TextView.VISIBLE
        }

        // 点卡片外/返回键 = 取消
        findViewById<android.view.View>(R.id.root).setOnClickListener { cancelByUser() }
        findViewById<android.view.View>(R.id.card).setOnClickListener { /* 消费，不透传 */ }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { cancelByUser() }
        findViewById<Button>(R.id.btnFill).setOnClickListener { startBiometric() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        cancelByUser()
    }

    private fun cancelByUser() {
        val id = sessionId ?: return finishQuietly()
        val session = FillCoordinator.get(id)
        if (session != null && !finished) {
            finished = true
            FillCoordinator.callback?.onAuthCancelled(session, byUser = true)
            FillCoordinator.complete(id)
        }
        finish()
    }

    private fun finishQuietly() {
        finished = true
        finish()
    }

    /** 兜底：Activity 被系统回收/划掉时若会话还开着，必须关掉，否则会话泄漏会永久堵死后续弹窗。 */
    override fun onDestroy() {
        val id = sessionId
        if (id != null && !finished) {
            finished = true
            val session = FillCoordinator.get(id)
            if (session != null) {
                android.util.Log.w("CampusAutofill", "gate destroyed with open session, releasing $id")
                FillCoordinator.callback?.onAuthCancelled(session, byUser = false)
                FillCoordinator.complete(id)
            }
        }
        super.onDestroy()
    }

    // ── 阶段②：BiometricPrompt ────────────────────────────────────────────

    private fun startBiometric() {
        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        val can = BiometricManager.from(this).canAuthenticate(authenticators)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            Toast.makeText(this, R.string.gate_no_lock, Toast.LENGTH_LONG).show()
            cancelByUser()
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.gate_biometric_title))
            .setSubtitle(getString(R.string.gate_biometric_subtitle))
            .setAllowedAuthenticators(authenticators)
            // 允许 DEVICE_CREDENTIAL 时禁止 setNegativeButtonText（会抛异常）；用户按返回即取消
            .build()

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onBiometricSuccess()
                }

                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    // USER_CANCELED / CANCELED / NEGATIVE → 视为用户取消；其余为系统错误，不记录"不再弹"
                    val byUser = code == BiometricPrompt.ERROR_USER_CANCELED ||
                        code == BiometricPrompt.ERROR_CANCELED ||
                        code == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    val id = sessionId ?: return finishQuietly()
                    val session = FillCoordinator.get(id)
                    if (session != null && !finished) {
                        finished = true
                        FillCoordinator.callback?.onAuthCancelled(session, byUser = byUser)
                        FillCoordinator.complete(id)
                    }
                    finish()
                }

                override fun onAuthenticationFailed() {
                    // 指纹不匹配：提示框仍停留，允许重试，无需处理
                }
            }
        )
        prompt.authenticate(promptInfo)
    }

    private fun onBiometricSuccess() {
        val id = sessionId ?: return finishQuietly()
        val session = FillCoordinator.get(id)
        if (session == null || finished) {
            android.util.Log.w("CampusAutofill", "gate success but session gone/finished (session=$session finished=$finished)")
            finish()
            return
        }
        finished = true

        val credsUser = CredentialVault.loadUsername(this).orEmpty()
        val credsPass = CredentialVault.loadPassword(this)
        if (credsPass == null) {
            android.util.Log.e("CampusAutofill", "gate: loadPassword failed after biometric")
            Toast.makeText(this, getString(R.string.read_fail, ""), Toast.LENGTH_LONG).show()
            FillCoordinator.callback?.onAuthCancelled(session, byUser = false)
        } else {
            android.util.Log.i("CampusAutofill", "gate: creds loaded, dispatching fill")
            FillCoordinator.callback?.onAuthSuccess(session, credsUser, credsPass)
        }
        FillCoordinator.complete(id)
        finish()
    }
}
