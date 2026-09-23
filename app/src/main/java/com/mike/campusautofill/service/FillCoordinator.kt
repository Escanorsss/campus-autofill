package com.mike.campusautofill.service

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程内会话表：桥接 AuthGateActivity（验证 UI）与 FillAccessibilityService（填入执行）。
 * 注意：AccessibilityNodeInfo 不跨边界传递——验证后由服务重新遍历查找表单。
 */
object FillCoordinator {

    data class Session(
        val id: String,
        val packageName: String,
        val windowId: Int,
        val fingerprint: String,
        val hasExtraFields: Boolean,
        val createdAt: Long = System.currentTimeMillis()
    )

    interface Callback {
        fun onAuthSuccess(session: Session, username: String, password: String)
        fun onAuthCancelled(session: Session, byUser: Boolean)
    }

    private const val SESSION_TTL_MS = 90_000L

    private val sessions = ConcurrentHashMap<String, Session>()
    @Volatile
    var callback: Callback? = null

    fun register(session: Session): Session {
        sessions[session.id] = session
        return session
    }

    fun newSession(
        packageName: String,
        windowId: Int,
        fingerprint: String,
        hasExtraFields: Boolean
    ): Session = register(
        Session(UUID.randomUUID().toString(), packageName, windowId, fingerprint, hasExtraFields)
    )

    fun get(id: String): Session? = sessions[id]

    /**
     * 是否有验证弹窗在途（在途时服务不再触发新弹窗）。
     * 会话泄漏防护：Activity 被系统杀掉等异常路径可能留下孤儿会话——
     * 超时（90s）自动过期，否则 hasActiveSession() 永远为 true、再也弹不出确认框。
     */
    fun hasActiveSession(): Boolean {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { now - it.value.createdAt > SESSION_TTL_MS }
        return sessions.isNotEmpty()
    }

    fun complete(id: String) {
        sessions.remove(id)
    }

    fun clearAll() {
        sessions.clear()
    }
}
