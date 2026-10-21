package app.tsukaima.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import app.tsukaima.data.prefs.ThemeMode

val LocalTsukaimaColors = staticCompositionLocalOf { LightColors }

val MaterialTheme.tsukaima: TsukaimaColors
    @Composable
    @ReadOnlyComposable
    get() = LocalTsukaimaColors.current

private fun TsukaimaColors.toMaterial(dark: Boolean) = if (dark) {
    darkColorScheme(
        background = surfacePage, onBackground = ink1,
        surface = surfaceRaised, onSurface = ink1,
        surfaceVariant = surfaceSunken, onSurfaceVariant = ink2,
        primary = accent, onPrimary = onAccent,
        primaryContainer = accentSelected, onPrimaryContainer = ink1,
        outline = ink3, outlineVariant = hairline,
        error = danger, scrim = scrim,
    )
} else {
    lightColorScheme(
        background = surfacePage, onBackground = ink1,
        surface = surfaceRaised, onSurface = ink1,
        surfaceVariant = surfaceSunken, onSurfaceVariant = ink2,
        primary = accent, onPrimary = onAccent,
        primaryContainer = accentSelected, onPrimaryContainer = ink1,
        outline = ink3, outlineVariant = hairline,
        error = danger, scrim = scrim,
    )
}

/** Pressed accent derived from the dynamic primary: slightly darker in light mode, lighter in dark mode. */
internal fun dynamicAccentPressed(primary: Color, dark: Boolean): Color =
    lerp(primary, if (dark) Color.White else Color.Black, if (dark) 0.2f else 0.15f)

private val TsukaimaMaterialTypography = Typography().copy(
    bodyLarge = TsukaimaType.body,
    bodyMedium = TsukaimaType.ui,
    labelSmall = TsukaimaType.label,
    labelMedium = TsukaimaType.caption,
    titleLarge = TsukaimaType.title,
    titleMedium = TsukaimaType.titleSmall,
    headlineSmall = TsukaimaType.sectionTitle,
)

@Composable
fun TsukaimaTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) DarkColors else LightColors
    val context = LocalContext.current
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Only the accent family follows the wallpaper; warm surfaces and inks stay put.
        val dyn = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        base.copy(
            accent = dyn.primary,
            onAccent = dyn.onPrimary,
            accentPressed = dynamicAccentPressed(dyn.primary, darkTheme),
            accentSelected = dyn.primaryContainer,
            accentWash = lerp(base.surfacePage, dyn.primaryContainer, 0.45f),
        )
    } else {
        base
    }
    CompositionLocalProvider(LocalTsukaimaColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toMaterial(darkTheme),
            typography = TsukaimaMaterialTypography,
            content = content,
        )
    }
}

/** Whether [mode] (Settings → Theme) renders dark; null (settings still loading) follows the system. */
@Composable
@ReadOnlyComposable
fun isDarkTheme(mode: ThemeMode?): Boolean = when (mode ?: ThemeMode.System) {
    ThemeMode.Warm -> false
    ThemeMode.Dark -> true
    ThemeMode.System -> isSystemInDarkTheme()
}
