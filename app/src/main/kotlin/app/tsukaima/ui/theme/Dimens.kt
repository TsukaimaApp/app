package app.tsukaima.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object Dimens {
    val gutter = 20.dp
    val hit = 44.dp
    val drawerWidth = 296.dp
    val paneWidth = 300.dp
    val contentMax = 760.dp
    val wideBreakpoint = 720.dp

    // Float shadow: 0 2px 12px rgba(60,50,40,.10) light, 0 2px 14px rgba(0,0,0,.45) dark.
    val shadowElevation = 2.dp
    val shadowColorLight = Color(0xFF3C3228).copy(alpha = 0.10f)
    val shadowColorDark = Color.Black.copy(alpha = 0.45f)
}
