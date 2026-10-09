package com.mike.campusautofill.service

import android.graphics.Rect
import com.mike.campusautofill.diagnostics.DiagnosticLog as Log
import android.util.Pair
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 登录表单识别器。
 *
 * 谓词：恰好 1 个密码框 + ≥1 个可编辑非密码框（用户名候选）。
 * 用户名选取：排除 URL 形态的框（浏览器地址栏），在剩余候选里选**离密码框空间最近**的。
 * （真实 WebView 登录页的输入框往往无 id、无 hint、无文字——按树序取第一个会误抓地址栏。）
 *
 * 证据来源：isPassword / inputType / text / hintText / contentDescription /
 * viewIdResourceName / className；反射 getHtmlInfo()（部分系统有）作加成。
 * 只做正则匹配，不存储任何节点内容。
 */
object FieldFinder {

    private const val TAG = "CampusAutofill"

    private val USER_RE = Regex("(?i)(user|userid|username|login|account|uid|zhanghao|用户名|账号|学号|工号|学生号|student|sid|netid|email|邮箱|手机|phone|tel)")
    private val EXTRA_RE = Regex("(?i)(验证码|校验码|动态码|短信|sms|captcha|verify\\s*code|vcode|otp|mfa|两步|双因子|安全码|图形验证|滑动验证|拼图)")
    private val URL_RE = Regex("(?i)^(https?|ftp)://|\\b[a-z0-9-]+\\.(edu\\.cn|com|cn|net|org)(/|\\b)")

    private const val MAX_NODES = 2000
    private const val MAX_PAIR_DISTANCE_PX = 900 // 用户名框离密码框太远则视为噪音，放弃识别

    data class FormSnapshot(
        val usernameNode: AccessibilityNodeInfo,
        val passwordNode: AccessibilityNodeInfo,
        val hasExtraFields: Boolean,
        val fingerprint: String,
        val windowId: Int,
        val packageName: String,
        val hasInputFocus: Boolean = false
    )

    fun findLoginForm(root: AccessibilityNodeInfo): FormSnapshot? {
        val editables = ArrayList<AccessibilityNodeInfo>()
        var visited = 0

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (isEditableField(node)) editables.add(node)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        val passNodes = editables.filter { isPasswordField(it) }
        if (passNodes.size != 1) {
            logReject("passNodes=${passNodes.size} editables=${editables.size}", editables)
            return null
        }
        val passNode = passNodes[0]

        // 用户名候选：可编辑、非密码、非 URL 栏
        val userCandidates = editables.filter {
            !isPasswordField(it) && !looksLikeUrlField(it)
        }
        if (userCandidates.isEmpty()) {
            logReject("no user candidate", editables)
            return null
        }

        // 按离密码框的空间距离择近选取（WebView 表单字段常常零文字证据）
        val userNode = userCandidates.minByOrNull { spatialDistance(it, passNode) } ?: return null
        val dist = spatialDistance(userNode, passNode)
        if (dist > MAX_PAIR_DISTANCE_PX) {
            logReject("nearest user ${dist}px away from pass", editables)
            return null
        }

        val pkg = passNode.packageName?.toString() ?: return null

        // 干扰字段（验证码/短信等）：只在表单邻域内扫描，排除可点击的设置链接
        // （aTrust 页面顶部有"安全码配置"这类链接，不能误报成 2FA 字段）
        val region = formRegion(userNode, passNode)
        var extraHit = false
        visited = 0
        queue.clear()
        queue.add(root)
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            val b = Rect()
            node.getBoundsInScreen(b)
            if (!node.isClickable && b.intersects(region.left, region.top, region.right, region.bottom)) {
                if (EXTRA_RE.containsMatchIn(evidence(node))) extraHit = true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        // 指纹只用稳定字段（resource-id）；刻意不掺 uniqueId——WebView 重建控件树时
        // uniqueId 会变，会导致"验证成功后重新查找时指纹不匹配→静默放弃"
        val fingerprint = listOf(
            pkg,
            passNode.viewIdResourceName ?: "",
            userNode.viewIdResourceName ?: ""
        ).joinToString("|")

        // 弹窗门槛：用户正点在表单输入框上（防「点了个按钮也弹」）。
        // X5 等内核 isFocused 可能失灵，故 findFocus(FOCUS_INPUT) 兜底；再不行由
        // 调用方的 lastIntentSourceEditable 放行。
        val inputFocused = userNode.isFocused || passNode.isFocused ||
            (root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                ?.let { isEditableField(it) } ?: false)

        Log.d(
            TAG,
            "form found pkg=$pkg user=${rectOf(userNode)} pass=${rectOf(passNode)} dist=$dist extra=$extraHit focus=$inputFocused"
        )

        return FormSnapshot(
            usernameNode = userNode,
            passwordNode = passNode,
            hasExtraFields = extraHit,
            fingerprint = fingerprint,
            windowId = passNode.windowId,
            packageName = pkg,
            hasInputFocus = inputFocused
        )
    }

    /** 识别失败时把可编辑字段清单打进日志（限 8 条）——没有它没法调微信这类内核。 */
    private fun logReject(reason: String, editables: List<AccessibilityNodeInfo>) {
        if (editables.isEmpty()) {
            Log.d(TAG, "reject($reason): no editable nodes")
            return
        }
        val sample = editables.take(8).joinToString("; ") { describe(it) }
        Log.i(TAG, "reject($reason): [$sample]")
    }

    private fun describe(n: AccessibilityNodeInfo): String {
        val r = Rect(); n.getBoundsInScreen(r)
        return "cls=${n.className} pwd=${n.isPassword} it=${n.inputType} " +
            "hasHint=${!n.hintText.isNullOrEmpty()} hasId=${n.viewIdResourceName != null} rect=${r.flattenToString()}"
    }

    fun isPasswordField(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        // inputType 的密码变体
        val it = node.inputType
        if (it != 0 && (it and android.text.InputType.TYPE_MASK_VARIATION) in PASSWORD_VARIATIONS) return true
        // 兜底：hint/描述/id 明确写着密码。微信 X5 等内核常不设 isPassword。
        // 边界 (?i)(^|[^a-z]) 排除 unPassword 这类用户名框（前面是字母 n，不命中）。
        val name = buildString {
            node.hintText?.let { append(it).append(' ') }
            node.contentDescription?.let { append(it).append(' ') }
            node.viewIdResourceName?.let { append(it) }
        }
        return name.isNotEmpty() && PASS_NAME_RE.containsMatchIn(name)
    }

    private val PASS_NAME_RE = Regex("(?i)(^|[^a-z])(password|passwd|pwd|mima|密码|口令)")

    fun isEditableField(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        val cls = node.className?.toString() ?: ""
        return cls.contains("EditText")
    }

    /** 浏览器/aTrust 地址栏：内容是 URL 形态，绝不能当用户名框。 */
    private fun looksLikeUrlField(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim().orEmpty()
        return text.isNotEmpty() && URL_RE.containsMatchIn(text)
    }

    private val PASSWORD_VARIATIONS = setOf(
        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
        android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
    )

    /** 两框中心的曼哈顿距离（像素）。 */
    private fun spatialDistance(a: AccessibilityNodeInfo, b: AccessibilityNodeInfo): Int {
        val ra = Rect(); a.getBoundsInScreen(ra)
        val rb = Rect(); b.getBoundsInScreen(rb)
        val dx = (ra.exactCenterX() - rb.exactCenterX()).toInt()
        val dy = (ra.exactCenterY() - rb.exactCenterY()).toInt()
        return kotlin.math.abs(dx) + kotlin.math.abs(dy)
    }

    /** 表单邻域：两框包围盒外扩，用于圈定"属于这个表单"的标签/干扰字段。 */
    private fun formRegion(user: AccessibilityNodeInfo, pass: AccessibilityNodeInfo): Rect {
        val r = Rect()
        user.getBoundsInScreen(r)
        val rp = Rect()
        pass.getBoundsInScreen(rp)
        r.union(rp)
        r.inset(-200, -120)
        return r
    }

    private fun rectOf(node: AccessibilityNodeInfo): String {
        val r = Rect(); node.getBoundsInScreen(r); return r.flattenToString()
    }

    /** 拼接字段证据串（只用于正则匹配，不存储）。 */
    private fun evidence(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        node.text?.let { sb.append(it).append(' ') }
        node.hintText?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it).append(' ') }
        node.viewIdResourceName?.let { sb.append(it).append(' ') }
        sb.append(htmlEvidence(node))
        return sb.toString()
    }

    /**
     * 可选 html 证据：AccessibilityNodeInfo.getHtmlInfo() 在部分系统版本存在
     * （API 36 的 SDK stubs 中已不见），反射尽力而为。
     */
    private fun htmlEvidence(node: AccessibilityNodeInfo): String {
        return try {
            val html = AccessibilityNodeInfo::class.java.getMethod("getHtmlInfo").invoke(node)
                ?: return ""
            val sb = StringBuilder()
            html.javaClass.getMethod("getTag").invoke(html)?.let { sb.append(it).append(' ') }
            val attrs = html.javaClass.getMethod("getAttributes").invoke(html) as? List<*>
            attrs?.forEach { item ->
                if (item is Pair<*, *>) {
                    sb.append(item.first ?: "").append('=').append(item.second ?: "").append(' ')
                }
            }
            sb.toString()
        } catch (e: Throwable) {
            ""
        }
    }
}
