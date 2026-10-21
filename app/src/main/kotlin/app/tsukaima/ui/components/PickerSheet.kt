package app.tsukaima.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/** A node of a [PickerSheet]: a [Group] drills down into its children, a [Leaf] picks and closes the sheet. */
sealed interface PickerNode {
    val label: String

    /** [checked] marks the current selection; a group is checked when the selection is somewhere inside it. */
    val checked: Boolean

    data class Leaf(override val label: String, override val checked: Boolean = false, val pick: () -> Unit) : PickerNode

    data class Group(override val label: String, val children: List<PickerNode>) : PickerNode {
        override val checked: Boolean get() = children.any { it.checked }
    }
}

/**
 * Bottom sheet for a tree of choices (provider → model → flavor): each level is a scrollable list, a header
 * with a back arrow names the group being browsed, and a leaf pick hides the sheet before firing, exactly once
 * per visibility (like [MenuSheet]). The browsed path resets whenever the sheet is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    nodes: List<PickerNode>,
) {
    if (!visible) return
    val colors = MaterialTheme.tsukaima
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val fired = remember { AtomicBoolean(false) }
    var path by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    val groups = path.runningFold(nodes) { level, index -> (level.getOrNull(index) as? PickerNode.Group)?.children.orEmpty() }
    val level = groups.last()
    val crumbs = path.mapIndexedNotNull { depth, index -> groups[depth].getOrNull(index)?.label }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = TsukaimaShapes.sheetTop,
        containerColor = colors.surfaceRaised,
        scrimColor = colors.scrim,
        tonalElevation = 0.dp,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(start = 8.dp, end = 8.dp, bottom = 18.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (path.isNotEmpty()) {
                    IconButton44(IconName.ArrowBack, contentDescription = "Back", onClick = { path = path.dropLast(1) })
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = (listOf(title) + crumbs).joinToString(" › "),
                    style = TsukaimaType.caption,
                    color = colors.ink3,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                )
            }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                level.forEachIndexed { index, node ->
                    when (node) {
                        is PickerNode.Group -> TsukaimaRow(
                            title = node.label,
                            subtitle = "${node.children.size} ${if (node.children.size == 1) "option" else "options"}",
                            trailing = {
                                if (node.checked) CheckMark()
                                TsukaimaIcon(IconName.ChevronRight, contentDescription = null, size = 20.dp, tint = colors.ink3)
                            },
                            onClick = { path = path + index },
                        )
                        is PickerNode.Leaf -> TsukaimaRow(
                            title = node.label,
                            trailing = { if (node.checked) CheckMark() },
                            onClick = {
                                // One-shot per sheet visibility: taps during the hide animation are ignored.
                                if (!fired.compareAndSet(false, true)) return@TsukaimaRow
                                scope.launch { sheetState.hide() }.invokeOnCompletion { cause ->
                                    if (cause == null && !sheetState.isVisible) {
                                        onDismiss()
                                        node.pick()
                                    } else {
                                        fired.set(false)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckMark() {
    TsukaimaIcon(IconName.Check, contentDescription = "Current", size = 20.dp, tint = MaterialTheme.tsukaima.accent)
}
