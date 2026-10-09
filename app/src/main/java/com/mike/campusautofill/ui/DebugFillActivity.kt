package com.mike.campusautofill.ui

import android.os.Bundle
import com.mike.campusautofill.diagnostics.DiagnosticLog as Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mike.campusautofill.service.FieldFinder
import com.mike.campusautofill.service.FillAccessibilityService
import com.mike.campusautofill.service.Filler

/**
 * 调试直填：不弹验证、用固定测试文本填入当前屏幕上的登录表单，供 ADB 端到端验证识别+填入链路。
 * 用法：adb shell am start -n com.mike.campusautofill/.ui.DebugFillActivity [--es pkg com.sangfor.atrust]
 * 只写入 TESTUSER/TESTPASS 测试串，不触碰真实凭据库。
 */
class DebugFillActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CampusAutofill"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wantPkg = intent.getStringExtra("pkg")
        val svc = FillAccessibilityService.instance
        if (svc == null) {
            Toast.makeText(this, "无障碍服务未运行", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Thread {
            val result = runFill(svc, wantPkg)
            runOnUiThread {
                Toast.makeText(this, result, Toast.LENGTH_LONG).show()
                Log.i(TAG, "DEBUGFILL result: $result")
                finish()
            }
        }.start()
    }

    private fun runFill(svc: FillAccessibilityService, wantPkg: String?): String {
        // 遍历所有窗口找登录表单（本 Activity 盖在上面，不能只看 activeWindow）
        val windows = svc.windows ?: return "windows=null"
        for (w in windows) {
            val root = w.root ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (pkg == packageName) continue
            if (wantPkg != null && pkg != wantPkg) continue

            val form = try {
                FieldFinder.findLoginForm(root)
            } catch (e: Exception) {
                Log.e(TAG, "DEBUGFILL scan error", e)
                null
            }
            if (form == null) continue

            Log.i(TAG, "DEBUGFILL found form in $pkg, filling test values")
            val r1 = Filler.fill(this, form.usernameNode, "TESTUSER")
            val r2 = Filler.fill(this, form.passwordNode, "TESTPASS")

            // 读回用户名框验证（密码框读不回）
            form.usernameNode.refresh()
            val readback = form.usernameNode.text?.toString() ?: "<null>"
            val matches = readback == "TESTUSER"
            Log.i(TAG, "DEBUGFILL user fill=${r1.ok}/${r1.method} pass fill=${r2.ok}/${r2.method} testReadbackMatches=$matches")
            return "user=${r1.method}:${r1.ok} pass=${r2.method}:${r2.ok} testReadbackMatches=$matches"
        }
        return "未找到登录表单 (pkg filter=$wantPkg)"
    }
}
