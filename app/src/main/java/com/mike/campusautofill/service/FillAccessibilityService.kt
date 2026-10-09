package com.mike.campusautofill.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.mike.campusautofill.diagnostics.DiagnosticLog as Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.mike.campusautofill.R
import com.mike.campusautofill.auth.AuthGateActivity
import com.mike.campusautofill.settings.UserSettings
import java.util.concurrent.Executors

/**
 * 核心引擎：监听用户点输入框 → 防抖 → 识别登录表单 → 弹确认/验证 → 填入。
 *
 * 触发模型（真机日志校准过两轮）：
 * - 点选信号：可编辑控件的 CLICKED，或页面稳定后的 SELECTION。
 *   FOCUSED 只在设备上报近期触屏时使用，避免页面自动聚焦误弹。
 * - CONTENT_CHANGED **不是**换页信号！CAS/微信 WebView 会以 ~100ms 频率刷 SUBTREE
 *   （动画/框架重绘）——曾把它当换页导致宽限期被永久续期、点击全被吞（"第二次必死"）。
 *   它只在已有意图时补扫（点了输入框后表单才出现的情况）。
 * - 只有 TYPE_WINDOW_STATE_CHANGED（真·换窗口/Activity）才重置换页状态。
 */
class FillAccessibilityService : AccessibilityService(), FillCoordinator.Callback {

    companion object {
        private const val TAG = "CampusAutofill"
        private const val DEBOUNCE_TAP_MS = 150L     // 点选信号 → 快扫
        private const val DEBOUNCE_CONTENT_MS = 300L // 有意图时的内容变化补扫
        private const val COOLDOWN_MS = 1500L
        private const val TOUCH_INTENT_MS = 1000L
        private const val SELECTION_GRACE_MS = 500L
        private const val INTENT_TTL_MS = 3000L      // 「用户点过输入框」信号有效期
        private const val KEYBOARD_GUARD_MS = 500L   // 点完输入框键盘弹起也发换页事件，别吞掉刚点的意图
        private const val MEM_TEARDOWN_GUARD_MS = 2000L // 弹窗关闭/键盘开合 ≠ 换页，宽限期内保留「已取消/已填」记忆
        private const val MEMORY_TTL_MS = 90_000L    // 同页记忆兜底过期（防 SPA 导航不清记忆）
        private const val TOAST_RATE_MS = 4000L

        @Volatile
        var instance: FillAccessibilityService? = null
            private set

        /** 自测页调试开关：临时允许扫描自身包名（SelfTestActivity 专用 WebView 表单） */
        @Volatile
        var debugScanSelfPackage = false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val healthCheck = object : Runnable {
        override fun run() {
            Log.snapshot("service heartbeat")
            mainHandler.postDelayed(this, 60_000)
        }
    }
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private var pendingScan: Runnable? = null

    // "本页已取消 / 已填过"记忆：fingerprint → 记录时刻
    private val dismissed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val filled = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val sessionForms = java.util.concurrent.ConcurrentHashMap<String, FieldFinder.FormSnapshot>()
    private var lastTriggerMs = 0L
    private var lastSuppressedToastMs = 0L
    private var inputMethodPackage = ""

    @Volatile private var lastWindowChangeMs = 0L
    @Volatile private var lastTouchMs = 0L
    @Volatile private var lastUserIntentMs = 0L
    @Volatile private var lastIntentPackage: String? = null
    @Volatile private var lastEventForm: FieldFinder.FormSnapshot? = null
    @Volatile private var lastEventFormMs = 0L
    /** 最近一次点选信号是否来自可编辑控件本身（部分内核 isFocused 失灵时的放行依据） */
    @Volatile private var lastIntentSourceEditable = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        FillCoordinator.callback = this
        UserSettings.load(this) // 服务被系统单独拉起时也要恢复暂停开关
        inputMethodPackage = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
            .orEmpty()
        Log.i(TAG, "service connected")
        mainHandler.removeCallbacks(healthCheck)
        mainHandler.post(healthCheck)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "service unbound")
        detach()
        return super.onUnbind(intent)
    }

    private fun detach() {
        mainHandler.removeCallbacks(healthCheck)
        pendingScan?.let { mainHandler.removeCallbacks(it) }
        pendingScan = null
        if (instance === this) {
            instance = null
            FillCoordinator.callback = null
            FillCoordinator.clearAll()
            sessionForms.clear()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "service destroyed")
        detach()
        scanExecutor.shutdown()
        super.onDestroy()
    }

    override fun onInterrupt() { Log.i(TAG, "service interrupted (not an unbind)") }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 廉价过滤①：暂停开关 = 真零开销
        if (UserSettings.isPaused) return

        // 触屏事件通常没有 package/source，必须在包名过滤前记下。
        // 页面自动聚焦不会产生触屏事件，用户点输入框才会。
        if (event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
            lastTouchMs = System.currentTimeMillis()
            Log.d(TAG, "touch interaction start")
            return
        }

        // 廉价过滤②：自身包名（除自测调试）
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName && !debugScanSelfPackage) return
        // 廉价过滤③：系统/输入法等
        if (pkg.startsWith("com.android.systemui") ||
            pkg.startsWith("com.android.inputmethod") ||
            pkg.startsWith("com.google.android.inputmethod") ||
            pkg == "com.oplus.securitykeyboard" ||
            pkg == inputMethodPackage
        ) return

        Log.rate("event-$pkg-${event.eventType}", "pkg=$pkg type=${event.eventType} " +
            "window=${event.windowId} sourceClass=${event.className}")

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> onTapSignal(event)
            // 内容变化：不是换页、不算意图。仅有意图时补扫（表单可能晚于点击出现）。
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg == lastIntentPackage && pendingScan == null &&
                    System.currentTimeMillis() - lastUserIntentMs <= INTENT_TTL_MS) {
                    scheduleScan(DEBOUNCE_CONTENT_MS)
                }
            }
            // 只有真·换窗口/Activity 才重置换页状态
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged(pkg)
            else -> Unit
        }
    }

    private fun onTapSignal(event: AccessibilityEvent) {
        val src = event.source
        val cls = src?.className?.toString().orEmpty()
        val editable = src != null && FieldFinder.isEditableField(src)
        if (!editable) {
            Log.rate("noneditable", "input signal ignored: source absent/noneditable type=${event.eventType} cls=$cls")
            return
        }

        val now = System.currentTimeMillis()
        val clicked = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        val recentTouch = now - lastTouchMs in 0..TOUCH_INTENT_MS &&
            lastTouchMs >= lastWindowChangeMs
        val selected = event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED &&
            now - lastWindowChangeMs > SELECTION_GRACE_MS
        Log.i(TAG, "input-signal type=${event.eventType} cls=$cls clicked=$clicked selected=$selected touch=$recentTouch")
        if (!clicked && !selected && !recentTouch) {
            Log.d(TAG, "input focus/selection without a recent touch ignored")
            return
        }
        // 微信 X5 的窗口根节点是空的，但输入框事件节点可以向上找到完整 WebView 表单。
        // Any embedded WebView may expose only its event ancestry, not a window root.
        lastEventForm = findFormFromEvent(src)?.takeIf {
            it.packageName == event.packageName?.toString()
        }
        lastEventFormMs = now
        lastUserIntentMs = now
        lastIntentPackage = event.packageName?.toString()
        lastIntentSourceEditable = true
        scheduleScan(DEBOUNCE_TAP_MS)
    }

    private fun findFormFromEvent(source: AccessibilityNodeInfo?): FieldFinder.FormSnapshot? {
        var node = source?.parent
        repeat(4) {
            val current = node ?: return null
            FieldFinder.findLoginForm(current)?.let {
                Log.i(TAG, "form found via input event parent")
                return it
            }
            node = current.parent
        }
        return null
    }

    private fun onWindowChanged(pkg: String) {
        val now = System.currentTimeMillis()
        lastWindowChangeMs = now
        // 键盘弹起紧跟点击——刚记下的意图不能被这种换页事件吞掉
        if (now - lastUserIntentMs > KEYBOARD_GUARD_MS) {
            lastUserIntentMs = 0L
            lastIntentPackage = null
            lastEventForm = null
            lastIntentSourceEditable = false
        }
        // 弹窗关闭/键盘开合也会来换页事件：刚写入记忆 2s 内不算真换页
        val memWriteAt = maxOf(
            dismissed.values.maxOrNull() ?: 0L,
            filled.values.maxOrNull() ?: 0L
        )
        if (now - memWriteAt < MEM_TEARDOWN_GUARD_MS) {
            Log.d(TAG, "window change ($pkg) near memory write, keep page memory")
        } else if (dismissed.isNotEmpty() || filled.isNotEmpty()) {
            Log.i(TAG, "page memory cleared (window change $pkg)")
            dismissed.clear()
            filled.clear()
        }
    }

    private fun scheduleScan(delayMs: Long) {
        pendingScan?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            pendingScan = null
            executeSafely("scan") { runScan() }
        }
        pendingScan = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun executeSafely(operation: String, action: () -> Unit) {
        try {
            scanExecutor.execute {
                try { action() } catch (e: Exception) {
                    Log.e(TAG, "$operation failed", e)
                    if (operation == "fill") showToast(R.string.fill_failed)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Log.e(TAG, "$operation rejected after service shutdown", e)
        }
    }

    /** 后台线程：全树遍历识别（多窗口，焦点窗口优先）。每条放弃路径都留日志。 */
    private fun runScan() {
        if (UserSettings.isPaused) return
        if (FillCoordinator.hasActiveSession()) {
            Log.i(TAG, "scan skip: verification UI in flight")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastTriggerMs < COOLDOWN_MS) {
            Log.i(TAG, "scan skip: cooldown")
            return
        }
        if (now - lastUserIntentMs > INTENT_TTL_MS) {
            Log.i(TAG, "scan skip: no recent user tap")
            return
        }

        val targetPackage = lastIntentPackage ?: return
        val form = findFormInAnyWindow(targetPackage)
            ?: lastEventForm?.takeIf {
                it.packageName == targetPackage && now - lastEventFormMs <= INTENT_TTL_MS
            }
        if (form == null) {
            Log.i(TAG, "scan skip: no login form here")
            return
        }

        // 产品语义「点输入框才弹」：表单在场且用户正点在输入框上。
        // isFocused 失灵的内核走 lastIntentSourceEditable 放行（点击信号来自输入框本身）。
        if (!form.hasInputFocus && !lastIntentSourceEditable) {
            Log.i(TAG, "scan skip: form present but no input focus (tap was elsewhere)")
            return
        }

        // 去重：本页已取消 或 已填过（记忆 90s 兜底过期，防 SPA 导航永不清除）
        val dismissedAt = dismissed[form.fingerprint]
        val filledAt = filled[form.fingerprint]
        if (dismissedAt != null && now - dismissedAt < MEMORY_TTL_MS) {
            toastRateLimited("本页已取消过，不再重复询问；返回重进后可再次询问")
            return
        }
        if (filledAt != null && now - filledAt < MEMORY_TTL_MS) {
            toastRateLimited("本页已填充过；返回重进后可再次填充")
            return
        }

        if (!com.mike.campusautofill.vault.CredentialVault.hasSaved(this)) {
            Log.w(TAG, "scan skip: no saved credential")
            toastRateLimited("尚未保存账号密码，请先到应用内录入")
            return
        }

        triggerAuth(form, now)
    }

    /** 逐窗口找登录表单。微信这类 App 的 WebView 可能不在「活动窗口」里。 */
    private fun findFormInAnyWindow(targetPackage: String): FieldFinder.FormSnapshot? {
        val roots = ArrayList<AccessibilityNodeInfo>()
        val wins = windows?.sortedBy { if (it.isFocused) 0 else 1 } ?: emptyList()
        Log.i(TAG, "scan windows=${wins.size} target=$targetPackage")
        for (w in wins) {
            w.root?.let { roots.add(it) }
        }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }

        for (root in roots) {
            try {
                if (root.packageName?.toString() != targetPackage) continue
                Log.i(TAG, "scan root pkg=${root.packageName} children=${root.childCount} window=${root.windowId}")
                val form = FieldFinder.findLoginForm(root)
                if (form != null && form.packageName == targetPackage) return form
            } catch (e: Exception) {
                Log.e(TAG, "scan failed", e)
            } finally {
                runCatching { root.recycle() }
            }
        }
        return null
    }

    private fun toastRateLimited(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastSuppressedToastMs < TOAST_RATE_MS) return
        lastSuppressedToastMs = now
        showToast(msg)
    }

    private fun showToast(msg: String) {
        mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun showToast(resId: Int) {
        mainHandler.post { Toast.makeText(this, resId, Toast.LENGTH_SHORT).show() }
    }

    private fun triggerAuth(form: FieldFinder.FormSnapshot, now: Long) {
        lastTriggerMs = now
        Log.i(TAG, "trigger auth pkg=${form.packageName} window=${form.windowId} extra=${form.hasExtraFields}")

        val session = FillCoordinator.newSession(
            packageName = form.packageName,
            windowId = form.windowId,
            fingerprint = form.fingerprint,
            hasExtraFields = form.hasExtraFields
        )
        sessionForms[session.id] = form
        lastEventForm = null

        // 确认框里的账号名由 AuthGateActivity 自己读凭据库（用户名仅混淆存盘、验证前可见）
        val intent = AuthGateActivity.createIntent(this, session.id, hasExtra = form.hasExtraFields)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startActivity blocked", e)
            FillCoordinator.complete(session.id)
            sessionForms.remove(session.id)
            showToast("检测到登录页，但无法弹窗，请到本应用内重试")
        }
    }

    // ── FillCoordinator.Callback：验证结果回流 ────────────────────────────

    override fun onAuthSuccess(session: FillCoordinator.Session, username: String, password: String) {
        executeSafely("fill") {
            try {
                performFill(session, username, password)
            } finally {
                // password: String 无法擦除，靠短生命周期
            }
        }
    }

    override fun onAuthCancelled(session: FillCoordinator.Session, byUser: Boolean) {
        sessionForms.remove(session.id)
        if (byUser) {
            // 用户明确取消 → 本页不再弹
            dismissed[session.fingerprint] = System.currentTimeMillis()
            Log.i(TAG, "session cancelled by user pkg=${session.packageName}")
        }
        // 非用户原因（解密失败等）不记，允许再次询问
    }

    /** 验证成功后重新遍历查找表单（验证期间节点引用已失效，页面也可能刷新）。 */
    private fun performFill(session: FillCoordinator.Session, username: String, password: String) {
        // 透明验证页此刻可能正在关闭动画中（rootInActiveWindow 会指错），
        // 窗口也可能在重建——重试若干轮，每轮留出稳定时间
        var form: FieldFinder.FormSnapshot? = null
        for (attempt in 0 until 4) {
            // 优先按窗口 ID 找回登录页窗口；否则找目标包名的窗口
            val root = windows?.firstOrNull { it.id == session.windowId }?.root
                ?: windows?.firstOrNull { it.root?.packageName?.toString() == session.packageName }?.root
                ?: rootInActiveWindow?.takeIf { it.packageName?.toString() == session.packageName }
            if (root != null) {
                try {
                    val found = FieldFinder.findLoginForm(root)
                    if (found != null && found.packageName == session.packageName) {
                        form = found
                        break
                    }
                    Log.i(TAG, "fill retry $attempt: form not found or wrong package")
                } catch (e: Exception) {
                    Log.e(TAG, "fill retry $attempt scan error", e)
                } finally {
                    runCatching { root.recycle() }
                }
            } else {
                Log.i(TAG, "fill retry $attempt: window not found yet")
            }
            if (attempt < 3) {
                try { Thread.sleep(350) } catch (ignored: InterruptedException) {}
            }
        }

        val cached = sessionForms.remove(session.id)
        val resolved = form ?: cached?.takeIf {
            it.packageName == session.packageName &&
                it.usernameNode.refresh() && it.passwordNode.refresh()
        }
        if (form == null && resolved != null) {
            Log.i(TAG, "fill using refreshed input-event nodes")
        }
        if (resolved == null) {
            Log.e(TAG, "fill aborted: login form not re-found after retries pkg=${session.packageName}")
            showToast("未找到登录表单，未能填入")
            return
        }

        var okUser = false
        var okPass = false
        try {
            val r1 = Filler.fill(this, resolved.usernameNode, username)
            val r2 = Filler.fill(this, resolved.passwordNode, password)
            okUser = r1.ok; okPass = r2.ok
            Log.i(TAG, "fill result user=${r1.method}:${r1.ok} pass=${r2.method}:${r2.ok}")
        } finally {
            runCatching { resolved.usernameNode.recycle() }
            runCatching { resolved.passwordNode.recycle() }
        }

        if (okUser && okPass) {
            filled[resolved.fingerprint] = System.currentTimeMillis()
            showToast(R.string.fill_done)
        } else {
            showToast(R.string.fill_failed)
        }
    }
}
