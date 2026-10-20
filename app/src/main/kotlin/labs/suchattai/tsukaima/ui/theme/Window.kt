package labs.suchattai.tsukaima.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp

/*
 * The window's size from LocalWindowInfo (Compose's guidance over Configuration.screenWidthDp/screenHeightDp, which
 * are rounded, exclude decor differently across versions and do not follow multi-window resizing exactly).
 */

/** Width of the app's window, for the ≥720dp wide layout. */
@Composable
fun windowWidth(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }

/** Height of the app's window. */
@Composable
fun windowHeight(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
