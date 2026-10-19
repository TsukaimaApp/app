package labs.suchattai.tsukaima.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import labs.suchattai.tsukaima.ui.theme.TsukaimaShapes
import labs.suchattai.tsukaima.ui.theme.TsukaimaTheme
import labs.suchattai.tsukaima.ui.theme.tsukaima

/** One entry of a [MenuSheet]. [checked] draws a trailing check (the current selection). */
data class MenuItem(
    val label: String,
    val icon: IconName?,
    val danger: Boolean = false,
    val checked: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Bottom-sheet context menu (long-press on a row, model/workspace pickers). Destructive items
 * should be last. Choosing an item hides the sheet, then calls [onDismiss] followed by the
 * item's `onClick`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    items: List<MenuItem>,
) {
    if (!visible) return
    val colors = MaterialTheme.tsukaima
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = TsukaimaShapes.sheetTop,
        containerColor = colors.surfaceRaised,
        scrimColor = colors.scrim,
        tonalElevation = 0.dp,
        dragHandle = { SheetHandle() },
    ) {
        MenuSheetItems(items) { item ->
            scope.launch { sheetState.hide() }.invokeOnCompletion {
                onDismiss()
                item.onClick()
            }
        }
    }
}

@Composable
private fun SheetHandle() {
    Box(
        Modifier
            .padding(top = 10.dp, bottom = 10.dp)
            .size(width = 32.dp, height = 4.dp)
            .background(MaterialTheme.tsukaima.hairline, TsukaimaShapes.pill),
    )
}

@Composable
private fun MenuSheetItems(items: List<MenuItem>, onPick: (MenuItem) -> Unit) {
    Column(
        Modifier
            .navigationBarsPadding()
            .padding(start = 8.dp, end = 8.dp, bottom = 18.dp),
    ) {
        items.forEach { item ->
            TsukaimaRow(
                title = item.label,
                leading = item.icon,
                danger = item.danger,
                trailing = {
                    if (item.checked) {
                        TsukaimaIcon(IconName.Check, size = 20.dp, tint = MaterialTheme.tsukaima.accent)
                    }
                },
                onClick = { onPick(item) },
            )
        }
    }
}

@Composable
private fun MenuPreview(dark: Boolean) {
    TsukaimaTheme(darkTheme = dark) {
        Column(
            Modifier.background(MaterialTheme.tsukaima.surfaceRaised, TsukaimaShapes.sheetTop),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SheetHandle()
            MenuSheetItems(
                listOf(
                    MenuItem("Rename", IconName.Edit) {},
                    MenuItem("Hermes 4 · 405B", null, checked = true) {},
                    MenuItem("Archive", IconName.Archive) {},
                    MenuItem("Delete", IconName.Delete, danger = true) {},
                ),
            ) {}
        }
    }
}

@Preview(name = "Menu light")
@Composable
private fun MenuLightPreview() = MenuPreview(dark = false)

@Preview(name = "Menu dark")
@Composable
private fun MenuDarkPreview() = MenuPreview(dark = true)
