package com.mike.campusautofill.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mike.campusautofill.service.FillAccessibilityService

/** Local HTML remains a real WebView so accessibility recognition is exercised end to end. */
class SelfTestActivity : AppCompatActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dynamic = getSharedPreferences("appearance", MODE_PRIVATE).getBoolean("dynamic_color", false)
        setContent {
            CampusTheme(dynamicColor = dynamic) {
                var variant by rememberSaveable { mutableStateOf(0) }
                val context = LocalContext.current
                val web = remember(context) { WebView(context).apply { settings.javaScriptEnabled = true } }
                DisposableEffect(web) { onDispose { web.stopLoading(); web.destroy() } }
                Scaffold(topBar = {
                    TopAppBar(title = { Text("测试填充") }, navigationIcon = {
                        TextButton(onClick = { finish() }) { Text("返回") }
                    })
                }) { insets ->
                    Column(Modifier.fillMaxSize().padding(insets).imePadding()) {
                        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("标准表单", "含验证码", "兼容表单").forEachIndexed { index, label ->
                                FilterChip(selected = variant == index, onClick = { variant = index }, label = { Text(label) })
                            }
                        }
                        AndroidView(factory = { web }, modifier = Modifier.fillMaxWidth().weight(1f), update = {
                            val path = "file:///android_asset/selftest_${('a'.code + variant).toChar()}.html"
                            if (it.url != path) it.loadUrl(path)
                        })
                    }
                }
            }
        }
    }
    override fun onResume() { super.onResume(); FillAccessibilityService.debugScanSelfPackage = true }
    override fun onPause() { FillAccessibilityService.debugScanSelfPackage = false; super.onPause() }
}
