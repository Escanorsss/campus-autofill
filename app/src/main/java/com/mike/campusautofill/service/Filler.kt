package com.mike.campusautofill.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import com.mike.campusautofill.diagnostics.DiagnosticLog as Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 填入链路。两个关键坑（真实 aTrust 页实测）：
 * 1) WebView 对 ACTION_SET_TEXT 经常「返回 true 但实际没写进去」——必须读回校验，不信返回值；
 * 2) ACTION_PASTE 是异步的——粘贴后立刻清剪贴板会让粘贴读到空剪贴板，必须延迟清。
 *
 * 流程：点击聚焦 → SET_TEXT → 读回校验 → 失败则清空 → 剪贴板粘贴 → 延迟清剪贴板 → 再校验。
 * 只填文本，不点击任何按钮。
 */
object Filler {

    private const val TAG = "CampusAutofill"
    private val mainHandler = Handler(Looper.getMainLooper())

    enum class Method { SET_TEXT, PASTE }

    data class Result(val ok: Boolean, val method: Method?)

    fun fill(context: Context, node: AccessibilityNodeInfo, value: String): Result {
        // ── 尝试 1：ACTION_SET_TEXT（快，对原生 EditText 有效） ──
        clickAndFocus(node)
        val setTextOk = performSetText(node, value)
        if (setTextOk && verified(node, value, isPassword = node.isPassword)) {
            Log.d(TAG, "fill ok via SET_TEXT")
            return Result(true, Method.SET_TEXT)
        }

        // ── 尝试 2：剪贴板粘贴（WebView 的可靠路径） ──
        // 先清空字段，避免 SET_TEXT"谎报成功"造成双份内容
        performSetText(node, "")
        clickAndFocus(node)

        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("fill", value)
        runCatching {
            clip.description.extras = PersistableBundle().apply {
                // The key is also safe to attach on older systems that ignore it.
                putBoolean("android.content.extra.IS_SENSITIVE", true) // Android 13+ 不展示预览
            }
        }
        cm.setPrimaryClip(clip)

        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

        // ★ ACTION_PASTE 异步执行：延迟清剪贴板，给粘贴留出读取时间。
        // 收紧到 800ms，缩短剪贴板被占用的窗口（主路径 SET_TEXT 不经过剪贴板，此路径仅兜底）
        mainHandler.postDelayed({
            runCatching {
                if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
                else cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }, 800)

        sleep(250) // 等粘贴落盘
        node.refresh()
        val ok = verified(node, value, isPassword = node.isPassword) || (pasted && node.isPassword)
        Log.d(TAG, "fill paste pasted=$pasted verified=$ok")
        return if (ok) Result(true, Method.PASTE) else Result(false, null)
    }

    private fun clickAndFocus(node: AccessibilityNodeInfo) {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        sleep(60)
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        sleep(60)
    }

    private fun performSetText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val claimed = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        sleep(80)
        return claimed
    }

    /**
     * 读回校验。密码框的 getText() 在多数实现里恒为空/掩码，无法读回时返回 null 表示"不确定"。
     */
    private fun verified(node: AccessibilityNodeInfo, value: String, isPassword: Boolean): Boolean {
        node.refresh()
        val text = node.text?.toString()
        if (text == value) return true
        if (isPassword) {
            // 掩码形式（••••）视为成功；空串表示不确定，交由调用方决定是否走粘贴
            if (text != null && text.isNotEmpty() && text.length == value.length) return true
            return text == null // null = 拿不到，无法证伪
        }
        return false
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
