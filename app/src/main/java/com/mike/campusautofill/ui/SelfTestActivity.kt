package com.mike.campusautofill.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.mike.campusautofill.R
import com.mike.campusautofill.service.FillAccessibilityService

/**
 * 内置自测页：本地 HTML 模拟 3 种统一身份认证表单变体，脱离校园网即可验证整条链路。
 * 进入前 MainActivity 已打开 debugScanSelfPackage，允许服务扫描自身 WebView。
 */
class SelfTestActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_selftest)
        val root = findViewById<android.view.View>(R.id.root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val web = findViewById<WebView>(R.id.webview)
        web.settings.javaScriptEnabled = true // 仅加载本地 assets，无外链

        findViewById<Button>(R.id.btnVariantA).setOnClickListener {
            web.loadUrl("file:///android_asset/selftest_a.html")
        }
        findViewById<Button>(R.id.btnVariantB).setOnClickListener {
            web.loadUrl("file:///android_asset/selftest_b.html")
        }
        findViewById<Button>(R.id.btnVariantC).setOnClickListener {
            web.loadUrl("file:///android_asset/selftest_c.html")
        }

        web.loadUrl("file:///android_asset/selftest_a.html")
    }

    override fun onResume() {
        super.onResume()
        // 回到自测页（含验证弹窗关闭后）恢复扫描许可，保证 B/C 变体可继续测
        FillAccessibilityService.debugScanSelfPackage = true
    }

    override fun onPause() {
        // 离开自测页立即收回"扫描自身包名"的调试许可，避免误扫主界面的密码输入框
        FillAccessibilityService.debugScanSelfPackage = false
        super.onPause()
    }
}
