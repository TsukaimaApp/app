package labs.suchattai.tsukaima.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class TsukaimaColors(
    val surfacePage: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val hairline: Color,
    val ink1: Color,
    val ink2: Color,
    val ink3: Color,
    val accent: Color,
    val accentPressed: Color,
    val onAccent: Color,
    val accentSelected: Color,
    val accentWash: Color,
    val ok: Color,
    val danger: Color,
    val warn: Color,
    val codeBg: Color,
    val codeInk: Color,
    val scrim: Color,
    val pressWash: Color,
    val hoverWash: Color,
    /** The palette is the dark one: picks dark-only assets (the app's theme setting, not the system's night mode). */
    val isDark: Boolean = false,
)

private val Ink1Light = Color(0xFF272220)

val LightColors = TsukaimaColors(
    surfacePage = Color(0xFFF7F4ED),
    surfaceRaised = Color(0xFFFFFDF7),
    surfaceSunken = Color(0xFFEFEAE0),
    hairline = Color(0xFFE6E0D4),
    ink1 = Ink1Light,
    ink2 = Color(0xFF5D564E),
    ink3 = Color(0xFF746B61),
    accent = Color(0xFF7C6FCF),
    accentPressed = Color(0xFF6A5EBF),
    onAccent = Color(0xFFFFFFFF),
    accentSelected = Color(0xFFC9C2F0),
    accentWash = Color(0xFFEAE6F8),
    ok = Color(0xFF5B8A68),
    danger = Color(0xFFB3564E),
    warn = Color(0xFFA8823E),
    codeBg = Color(0xFF272233),
    codeInk = Color(0xFFD8D3E8),
    scrim = Ink1Light.copy(alpha = 0.28f),
    pressWash = Ink1Light.copy(alpha = 0.08f),
    hoverWash = Ink1Light.copy(alpha = 0.04f),
)

private val Ink1Dark = Color(0xFFEDE8DF)

val DarkColors = TsukaimaColors(
    surfacePage = Color(0xFF1B1917),
    surfaceRaised = Color(0xFF242120),
    surfaceSunken = Color(0xFF141210),
    hairline = Color(0xFF38332E),
    ink1 = Ink1Dark,
    ink2 = Color(0xFFB5ACA0),
    ink3 = Color(0xFF978E84),
    accent = Color(0xFFA89CFF),
    accentPressed = Color(0xFFBCB2FF),
    onAccent = Color(0xFF1B1917),
    accentSelected = Color(0xFF4A4370),
    accentWash = Color(0xFF322E48),
    ok = Color(0xFF5B8A68),
    danger = Color(0xFFB3564E),
    warn = Color(0xFFA8823E),
    codeBg = Color(0xFF141218),
    codeInk = Color(0xFFCFC9E2),
    scrim = Color.Black.copy(alpha = 0.28f),
    pressWash = Ink1Dark.copy(alpha = 0.10f),
    hoverWash = Ink1Dark.copy(alpha = 0.05f),
    isDark = true,
)
