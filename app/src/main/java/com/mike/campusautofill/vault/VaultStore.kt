package com.mike.campusautofill.vault

import android.content.Context
import android.util.Base64

/**
 * 落盘层：密码 = AES-GCM 密文（iv+ct）；用户名 = 可逆混淆串（确认框需在验证前展示账号名，
 * 无法放进认证绑定的密文里）。无任何直接明文字段。
 */
object VaultStore {

    private const val PREFS = "vault"
    private const val K_IV = "iv"
    private const val K_CT = "ct"
    private const val K_USER = "user_obf"

    fun write(context: Context, iv: ByteArray, ciphertext: ByteArray, usernameObf: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString(K_CT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(K_USER, usernameObf)
            .apply()
    }

    fun readPasswordBlob(context: Context): Pair<ByteArray, ByteArray>? {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iv = sp.getString(K_IV, null) ?: return null
        val ct = sp.getString(K_CT, null) ?: return null
        return try {
            Base64.decode(iv, Base64.NO_WRAP) to Base64.decode(ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    fun readUsernameObf(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_USER, null)

    fun hasData(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(K_CT)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
