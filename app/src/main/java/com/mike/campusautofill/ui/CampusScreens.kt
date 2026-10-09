@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.mike.campusautofill.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mike.campusautofill.permissions.PermissionHelper

data class SettingRow(val title: String, val detail: String, val granted: Boolean?, val action: () -> Unit)

@Composable
fun CampusApp(
    page: String, permissions: PermissionHelper.Status, paused: Boolean, savedUser: String?,
    username: String, password: String, userError: Boolean, passError: Boolean,
    busy: Boolean, dynamicColor: Boolean, dynamicColorSupported: Boolean,
    settings: List<SettingRow>, device: String,
    onPage: (String) -> Unit, onUsername: (String) -> Unit, onPassword: (String) -> Unit,
    onService: () -> Unit, onPause: () -> Unit, onDisable: () -> Unit,
    onSave: () -> Unit, onVerify: () -> Unit, onDelete: () -> Unit,
    onSelfTest: () -> Unit, onExport: () -> Unit, onDynamicColor: (Boolean) -> Unit
) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(when (page) {
            "settings" -> "设置"; "editor" -> "统一身份认证"; else -> "校园认证助手"
        }) }, navigationIcon = {
            if (page != "home") TextButton(onClick = { onPage("home") }) { Text("返回") }
        }, actions = {
            if (page == "home") TextButton(onClick = { onPage("settings") }, modifier = Modifier.testTag("settings")) { Text("设置") }
        })
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            when (page) {
                "settings" -> SettingsScreen(settings, device, dynamicColor, dynamicColorSupported,
                    busy, onSelfTest, onExport, onDynamicColor)
                "editor" -> AccountEditor(username, password, userError, passError, busy,
                    onUsername, onPassword, onSave)
                else -> HomeScreen(permissions, paused, savedUser, busy, onService, onPause, onDisable,
                    { onPage("editor") }, onVerify, onDelete)
            }
        }
    }
}

@Composable
fun HomeScreen(
    permissions: PermissionHelper.Status, paused: Boolean, savedUser: String?, busy: Boolean,
    onService: () -> Unit, onPause: () -> Unit, onDisable: () -> Unit,
    onEdit: () -> Unit, onVerify: () -> Unit, onDelete: () -> Unit
) {
    val connected = permissions.accessibilityEnabled && permissions.serviceConnected
    val interrupted = permissions.accessibilityEnabled && !permissions.serviceConnected
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("自动填充", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(when {
                    !permissions.accessibilityEnabled -> "未开启"
                    interrupted -> "连接中断"
                    paused -> "已暂停"
                    else -> "运行中"
                }, style = MaterialTheme.typography.headlineMedium,
                    color = if (interrupted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }.testTag("serviceStatus"))
                Text(when {
                    !permissions.accessibilityEnabled -> "开启后，点登录页的输入框即可填充。"
                    interrupted -> "权限还在，需要重新连接。"
                    paused -> "恢复后继续识别登录页。"
                    else -> "点登录页的输入框，验证后填充。"
                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (connected) {
                    FilledTonalButton(onClick = onPause, modifier = Modifier.fillMaxWidth().testTag("pause")) {
                        Text(if (paused) "恢复填充" else "暂停填充")
                    }
                } else Button(onClick = onService, modifier = Modifier.fillMaxWidth().testTag("enableService")) {
                    Text(if (interrupted) "重新连接" else "开启服务")
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("统一身份认证")
            if (savedUser == null) {
                Text("还没有保存账号", style = MaterialTheme.typography.bodyLarge)
                Text("填充前会验证指纹或锁屏密码。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().testTag("editAccount")) { Text("添加账号") }
            } else {
                Text(savedUser, style = MaterialTheme.typography.titleLarge)
                Text("密码已加密保存", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onEdit, enabled = !busy, modifier = Modifier.testTag("editAccount")) { Text("修改") }
                    TextButton(onClick = onVerify, enabled = !busy) { Text("验证密码") }
                    TextButton(onClick = onDelete, enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (permissions.accessibilityEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDisable, modifier = Modifier.testTag("disableService")) { Text("关闭无障碍") }
                Text("支付前可关闭；暂停仍保留权限。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("仅在本机保存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AccountEditor(
    username: String, password: String, userError: Boolean, passError: Boolean, busy: Boolean,
    onUsername: (String) -> Unit, onPassword: (String) -> Unit, onSave: () -> Unit
) {
    val focus = LocalFocusManager.current
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
        .padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CampusInputField(value = username, onValueChange = onUsername, label = "一卡通号",
            hint = "请输入一卡通号", tag = "username", enabled = !busy, error = userError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }))
        CampusInputField(value = password, onValueChange = onPassword, label = "密码",
            hint = "请输入密码", tag = "password", enabled = !busy, error = passError, password = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); onSave() }))
        Text("保存时验证身份，密码只留在本机。", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onSave, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("saveAccount")) {
            Text(if (busy) "正在验证…" else "保存")
        }
    }
}

@Composable
private fun SettingsScreen(
    rows: List<SettingRow>, device: String, dynamicColor: Boolean, dynamicColorSupported: Boolean,
    busy: Boolean, onSelfTest: () -> Unit, onExport: () -> Unit, onDynamicColor: (Boolean) -> Unit
) {
    var showHelp by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("settingsList"), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SectionTitle("权限") }
        item { Text(device, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(rows, key = { it.title }) { row ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium)
                Text(row.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(when (row.granted) { true -> "已开启"; false -> "未开启"; null -> "手动检查" },
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = row.action) { Text(if (row.granted == true) "管理" else "去设置") }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionTitle("壁纸配色")
                    Text(if (dynamicColorSupported) "跟随系统壁纸" else "需要 Android 12 或以上",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = dynamicColor, onCheckedChange = onDynamicColor, enabled = dynamicColorSupported,
                    modifier = Modifier.testTag("dynamicColor"))
            }
        }
        item { Spacer(Modifier.height(16.dp)); SectionTitle("测试与帮助") }
        item { OutlinedButton(onClick = onSelfTest, modifier = Modifier.fillMaxWidth().testTag("selfTest")) { Text("测试填充") } }
        item { OutlinedButton(onClick = onExport, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("exportLog")) {
            Text(if (busy) "正在导出…" else "导出日志")
        } }
        item { Text("不含账号、密码或屏幕文字。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { TextButton(onClick = { showHelp = !showHelp }) { Text(if (showHelp) "收起使用说明" else "使用说明") } }
        if (showHelp) item {
            Text("省电后无法填充：退出省电模式，关闭再打开无障碍。\n\n无障碍打不开：在应用详情里检查“允许受限制的设置”。\n\n后台容易被清理：允许自启动和后台运行，并在最近任务中锁定应用。\n\n对比新版时，请先关闭旧版无障碍，避免同时弹窗。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium,
    modifier = Modifier.semantics { heading() })

@Composable
fun RecoveryDialog(onDismiss: () -> Unit, onSettings: () -> Unit, onBackground: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("重新连接") },
        text = { Text("退出省电模式，在无障碍设置里先关闭，再打开校园认证助手。") },
        confirmButton = { TextButton(onClick = onSettings) { Text("打开无障碍设置") } },
        dismissButton = {
            FlowRow { TextButton(onClick = onDismiss) { Text("取消") }; TextButton(onClick = onBackground) { Text("后台设置") } }
        })
}

@Composable
fun DeleteAccountDialog(onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("删除账号？") },
        text = { Text("删除本机保存的账号和密码。") },
        confirmButton = { TextButton(onClick = onDelete,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Preview(name = "首页·浅色", showBackground = true)
@Preview(name = "首页·深色", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true)
@Preview(name = "首页·大字", fontScale = 2f, widthDp = 320, heightDp = 640, showBackground = true)
@Composable
private fun HomePreview() {
    CampusTheme { HomeScreen(PermissionHelper.Status(false, false, false, false, false), false,
        null, false, {}, {}, {}, {}, {}, {}) }
}
