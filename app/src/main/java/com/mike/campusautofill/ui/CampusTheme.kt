package com.mike.campusautofill.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// All component colors come from these semantic roles, never from literal colors.
internal val CampusLight = lightColorScheme(
    primary = Color(0xFF245FA6), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E3FF), onPrimaryContainer = Color(0xFF001C3B),
    secondary = Color(0xFF535F70), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E3F7), onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6B5778), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3DAFF), onTertiaryContainer = Color(0xFF251432),
    background = Color(0xFFFAFAFE), onBackground = Color(0xFF191C20),
    surface = Color(0xFFFAFAFE), onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC), onSurfaceVariant = Color(0xFF43474F),
    outline = Color(0xFF737780), outlineVariant = Color(0xFFC3C6D0),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
)
internal val CampusDark = darkColorScheme(
    primary = Color(0xFFA8C8FF), onPrimary = Color(0xFF003061),
    primaryContainer = Color(0xFF004788), onPrimaryContainer = Color(0xFFD5E3FF),
    secondary = Color(0xFFBBC7DB), onSecondary = Color(0xFF253141),
    secondaryContainer = Color(0xFF3C4858), onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFFD7BEE4), onTertiary = Color(0xFF3C2949),
    tertiaryContainer = Color(0xFF533F60), onTertiaryContainer = Color(0xFFF3DAFF),
    background = Color(0xFF111318), onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318), onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF43474F), onSurfaceVariant = Color(0xFFC3C6D0),
    outline = Color(0xFF8D919A), outlineVariant = Color(0xFF43474F),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6)
)

/** The system resolves this family, including OEM/user font replacement and script fallback. */
internal val SystemTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = FontFamily.Default),
        displayMedium = base.displayMedium.copy(fontFamily = FontFamily.Default),
        displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Default),
        headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.Default),
        headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Default),
        headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Default),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Default),
        titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Default),
        titleSmall = base.titleSmall.copy(fontFamily = FontFamily.Default),
        bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.Default),
        bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.Default),
        bodySmall = base.bodySmall.copy(fontFamily = FontFamily.Default),
        labelLarge = base.labelLarge.copy(fontFamily = FontFamily.Default),
        labelMedium = base.labelMedium.copy(fontFamily = FontFamily.Default),
        labelSmall = base.labelSmall.copy(fontFamily = FontFamily.Default)
    )
}

@Composable
fun CampusTheme(
    dynamicColor: Boolean = false,
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> CampusDark
        else -> CampusLight
    }
    MaterialTheme(colorScheme = colors, typography = SystemTypography, shapes = Shapes(
        extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(28.dp)
    ), content = content)
}
