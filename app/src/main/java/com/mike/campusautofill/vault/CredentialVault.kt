package com.mike.campusautofill.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭据加密库：Android Keystore AES-256-GCM。
 *
 * - 密码：AES-GCM 密文落盘，密钥受用户认证保护（指纹或锁屏密码，30 秒有效窗——只覆盖
 *   "验证通过→解密"这一瞬间；每次填充都必须重新弹 BiometricPrompt，产品上无免验时间窗）。
 * - 用户名：可逆混淆存盘（确认框需在验证前展示账号名）；不是安全边界，安全边界是密码的 AES。
 * - 明文密码永不落盘、不进日志。
 */
object CredentialVault {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "campus_autofill_aes"
    private const val GCM_TAG_BITS = 128
    private const val AUTH_TIMEOUT_SEC = 30

    /** 需在 BiometricPrompt 成功之后调用（用户认证窗内）。 */
    fun save(context: Context, username: String, password: String) {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val ct = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        VaultStore.write(context, cipher.iv, ct, obfuscate(username))
    }

    /** 无需认证：确认框展示用。 */
    fun loadUsername(context: Context): String? =
        VaultStore.readUsernameObf(context)?.let { deobfuscate(it) }

    /** 需在 BiometricPrompt 成功之后调用；失败（密钥失效/超窗）返回 null。 */
    fun loadPassword(context: Context): String? {
        val (iv, ct) = VaultStore.readPasswordBlob(context) ?: return null
        // 认证令牌传播在个别 OEM 上有微小延迟，重试 3 次
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val key = getOrCreateKey()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                }
                return cipher.doFinal(ct).toString(Charsets.UTF_8)
            } catch (e: Exception) {
                lastError = e
                android.util.Log.w("CampusAutofill", "loadPassword attempt ${attempt + 1} failed: ${e.javaClass.simpleName}: ${e.message}")
                try { Thread.sleep(150) } catch (ignored: InterruptedException) {}
            }
        }
        android.util.Log.e("CampusAutofill", "loadPassword exhausted", lastError)
        return null
    }

    fun hasSaved(context: Context): Boolean = VaultStore.hasData(context)

    fun delete(context: Context) {
        VaultStore.clear(context)
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        }
    }

    // ── Keystore 密钥 ──────────────────────────────────────────────────────

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 30s 有效窗 + 同时接受强生物特征与设备凭据（锁屏密码）。
            // 不用 per-op(0)+CryptoObject：部分 OEM 的 DEVICE_CREDENTIAL 无法解锁 per-op 密钥，
            // 会挡住"锁屏密码验证"这条用户明确要求的路径。
            builder.setUserAuthenticationParameters(
                AUTH_TIMEOUT_SEC,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(AUTH_TIMEOUT_SEC)
        }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(builder.build()) }
            .generateKey()
    }

    // ── 用户名混淆（非安全边界，仅避免一眼可见） ───────────────────────────

    private fun obfuscate(s: String): String =
        s.map { (it.code xor 0x5A).toChar() }.joinToString("").reversed()

    private fun deobfuscate(s: String): String =
        s.reversed().map { (it.code xor 0x5A).toChar() }.joinToString("")
}
