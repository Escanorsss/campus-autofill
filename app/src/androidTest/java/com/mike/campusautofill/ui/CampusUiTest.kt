package com.mike.campusautofill.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.core.graphics.ColorUtils
import com.mike.campusautofill.MainActivity
import com.mike.campusautofill.auth.AuthGateActivity
import com.mike.campusautofill.permissions.PermissionHelper
import com.mike.campusautofill.service.FillCoordinator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** UI regressions only use empty or fabricated accounts, never real credentials. */
class CampusUiTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun navigationAndInlineValidation() {
        ui.onNodeWithTag("editAccount").performClick()
        ui.onNodeWithTag("saveAccount").performClick()
        ui.onNodeWithText("请输入一卡通号").assertIsDisplayed()
        ui.onNodeWithText("请输入密码").assertIsDisplayed()
        snapshot("editor-errors", ui.onRoot())
        ui.onNodeWithText("返回").performClick()
        ui.onNodeWithTag("serviceStatus").assertIsDisplayed()
        ui.onNodeWithTag("settings").performClick()
        ui.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("selfTest"))
        ui.onNodeWithTag("selfTest").assertIsDisplayed()
        ui.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("exportLog"))
        ui.onNodeWithTag("exportLog").assertIsDisplayed()
        ui.onNodeWithTag("settingsList").performScrollToNode(hasText("使用说明"))
        ui.onNodeWithText("使用说明").performClick()
        ui.onNodeWithText("收起使用说明").assertIsDisplayed()
    }

    @Test fun dynamicColorToggleAndReturn() {
        ui.onNodeWithTag("settings").performClick()
        ui.onNodeWithTag("settingsList").performScrollToNode(hasTestTag("dynamicColor"))
        val toggle = ui.onNodeWithTag("dynamicColor")
        toggle.assertIsOff().performClick().assertIsOn()
        toggle.performClick().assertIsOff() // Restore the initial appearance preference.
        ui.onNodeWithText("返回").performClick()
        ui.onNodeWithTag("editAccount").assertIsDisplayed()
    }

    @Test fun serviceStatesHaveCorrectActions() {
        val cases = listOf(
            Triple(PermissionHelper.Status(false, false, false, false, false), false, "未开启"),
            Triple(PermissionHelper.Status(true, false, false, false, false), false, "连接中断"),
            Triple(PermissionHelper.Status(true, true, false, false, false), true, "已暂停"),
            Triple(PermissionHelper.Status(true, true, false, false, false), false, "运行中")
        )
        for ((permissions, paused, label) in cases) {
            render { HomeScreen(permissions, paused, null, false, {}, {}, {}, {}, {}, {}) }
            ui.onNodeWithTag("serviceStatus").assertTextEquals(label)
            if (permissions.serviceConnected) ui.onNodeWithTag("pause").assertIsDisplayed()
            else ui.onNodeWithTag("enableService").assertIsDisplayed()
        }
    }

    @Test fun homeLightDarkAndLargeText() {
        val p = PermissionHelper.Status(true, true, true, true, true)
        render { HomeScreen(p, false, "TESTUSER", false, {}, {}, {}, {}, {}, {}) }
        snapshot("home-light", ui.onRoot())
        render(dark = true) { HomeScreen(p, false, "TESTUSER", false, {}, {}, {}, {}, {}, {}) }
        snapshot("home-dark", ui.onRoot())
        render(scale = 2f) { HomeScreen(p, false, "TESTUSER", false, {}, {}, {}, {}, {}, {}) }
        ui.onNodeWithTag("pause").assertIsDisplayed()
        ui.onNodeWithTag("disableService").performScrollTo().assertIsDisplayed()
        snapshot("home-large-text", ui.onRoot())
    }

    @Test fun confirmationCanCancelInDarkAndLargeText() {
        val cancelled = AtomicBoolean()
        render(dark = true, scale = 2f) {
            FillConfirmation("TESTUSER", true, false, { cancelled.set(true) }, {})
        }
        ui.onNodeWithText("填充账号密码？").assertIsDisplayed()
        ui.onNodeWithTag("confirmFill").assertIsDisplayed()
        snapshot("confirmation-dark-large", ui.onNodeWithTag("fillConfirmation"))
        ui.onNodeWithText("取消").performClick()
        assertTrue(cancelled.get())
    }

    @Test fun realGateCancellationReleasesSession() {
        val cancelled = AtomicBoolean()
        ui.runOnUiThread {
            FillCoordinator.callback = object : FillCoordinator.Callback {
                override fun onAuthSuccess(session: FillCoordinator.Session, username: String, password: String) {
                    fail("Cancellation must never dispatch credentials")
                }
                override fun onAuthCancelled(session: FillCoordinator.Session, byUser: Boolean) {
                    cancelled.set(byUser)
                }
            }
            val session = FillCoordinator.newSession(ui.activity.packageName, 0, "test", false)
            ui.activity.startActivity(AuthGateActivity.createIntent(ui.activity, session.id, false))
        }
        try {
            ui.onNodeWithText("填充账号密码？").assertIsDisplayed()
            ui.onNodeWithText("取消").performClick()
            ui.waitUntil(5000) { cancelled.get() }
            assertFalse(FillCoordinator.hasActiveSession())
        } finally {
            ui.runOnUiThread { FillCoordinator.clearAll(); FillCoordinator.callback = null }
        }
    }

    @Test fun fallbackThemesMeetContrastAndUsePairedRoles() {
        for (scheme in listOf(CampusLight, CampusDark)) {
            val pairs = listOf(scheme.onPrimary to scheme.primary, scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.onSurface to scheme.surface, scheme.onSurfaceVariant to scheme.surface,
                scheme.onError to scheme.error, scheme.onErrorContainer to scheme.errorContainer)
            for ((foreground, background) in pairs) assertTrue(
                "Text contrast below 4.5:1", ColorUtils.calculateContrast(foreground.toArgb(), background.toArgb()) >= 4.5)
            assertTrue("Control outline contrast below 3:1",
                ColorUtils.calculateContrast(scheme.outline.toArgb(), scheme.surface.toArgb()) >= 3)
        }
    }

    @Test fun allTypographyRolesResolveTheSystemFont() {
        val t = SystemTypography
        listOf(t.displayLarge, t.displayMedium, t.displaySmall, t.headlineLarge, t.headlineMedium,
            t.headlineSmall, t.titleLarge, t.titleMedium, t.titleSmall, t.bodyLarge, t.bodyMedium,
            t.bodySmall, t.labelLarge, t.labelMedium, t.labelSmall).forEach {
            assertEquals(FontFamily.Default, it.fontFamily)
        }
    }

    @Test fun reusablePasswordFieldMasksTogglesAndDisablesSafely() {
        render {
            CampusInputField("TEST-SECRET", {}, "密码", "请输入密码", "testPassword", true, false,
                KeyboardOptions(keyboardType = KeyboardType.Password), password = true)
        }
        ui.onNodeWithTag("testPassword").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        ui.onNodeWithContentDescription("显示密码").performClick()
        ui.onNodeWithTag("testPassword").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
        ui.onNodeWithContentDescription("隐藏密码").performClick()
        ui.onNodeWithTag("testPassword").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        render {
            CampusInputField("TEST-SECRET", {}, "密码", "请输入密码", "testPassword", false, false,
                KeyboardOptions(keyboardType = KeyboardType.Password), password = true)
        }
        ui.onNodeWithTag("togglePassword").assertIsNotEnabled()
        ui.onNodeWithTag("testPassword").assertIsNotEnabled()
    }

    private fun render(dark: Boolean = false, scale: Float = 1f, content: @Composable () -> Unit) {
        ui.runOnUiThread {
            ui.activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    CampusTheme(dark = dark) { Surface(content = content) }
                }
            }
        }
        ui.waitForIdle()
    }
    private fun snapshot(name: String, node: SemanticsNodeInteraction) {
        val file = File(ui.activity.filesDir, "ui-test-screenshots/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
