package app.tsukaima.ui.chat.components

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tsukaima.ui.chat.ProcessRow
import app.tsukaima.ui.components.ButtonSize
import app.tsukaima.ui.components.GhostButton
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.SecondaryButton
import app.tsukaima.ui.components.SheetHandle
import app.tsukaima.ui.components.Tone
import app.tsukaima.ui.components.TsukaimaIcon
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima
import kotlinx.coroutines.delay

/**
 * The status line's "2 running": how many commands the agent left running in the background. A tap opens the
 * [ProcessSheet]. It takes the context meter's place on the line while anything runs.
 */
@Composable
fun ProcessChip(running: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    Row(
        modifier = modifier
            .clip(TsukaimaShapes.pill)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$running running in the background" }
            .defaultMinSize(minHeight = Dimens.hit)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(StatusVisual(colors.accent, pulsing = true))
        Text("$running running", style = TsukaimaType.caption, color = colors.accent, maxLines = 1)
    }
}

/**
 * "Running in the background": each command with how long it has run and its last output line; Output unfolds the
 * end of its output, live; Stop ([onStop]) ends that command only. A command that ended lately stays listed, marked
 * with how it ended, so its output can still be read.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessSheet(processes: List<ProcessRow>, canAct: Boolean, onDismiss: () -> Unit, onStop: (String) -> Unit) {
    val colors = MaterialTheme.tsukaima
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = TsukaimaShapes.sheetTop,
        containerColor = colors.surfacePage,
        scrimColor = colors.scrim,
        tonalElevation = 0.dp,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Running in the background", style = TsukaimaType.titleSmall, color = colors.ink1)
            if (processes.isEmpty()) Text("Nothing is running.", style = TsukaimaType.body, color = colors.ink2)
            processes.forEach { ProcessCard(it, canAct, onStop) }
        }
    }
}

@Composable
private fun ProcessCard(row: ProcessRow, canAct: Boolean, onStop: (String) -> Unit) {
    val colors = MaterialTheme.tsukaima
    var open by rememberSaveable(row.id) { mutableStateOf(false) }
    val shape = TsukaimaShapes.row
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceRaised, shape)
            .border(1.dp, colors.hairline, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TsukaimaIcon(IconName.Terminal, contentDescription = null, size = 18.dp, tint = if (row.running) colors.accent else colors.ink3)
            Text(
                row.command, style = TsukaimaType.mono, color = colors.ink1,
                maxLines = if (open) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(processMeta(row), style = TsukaimaType.caption, color = if (row.exitCode != null && row.exitCode != 0) colors.danger else colors.ink3, maxLines = 1)
        }
        val last = row.tail.trimEnd().substringAfterLast('\n')
        if (!open && last.isNotBlank()) {
            Text(last, style = TsukaimaType.caption.copy(fontFamily = TsukaimaType.mono.fontFamily), color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (open) ProcessOutput(row.tail)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(if (open) "Hide output" else "Output", { open = !open }, size = ButtonSize.Sm)
            if (row.running) {
                GhostButton(if (row.stopping) "Stopping…" else "Stop", { onStop(row.id) }, size = ButtonSize.Sm, enabled = canAct && !row.stopping, tone = Tone.Danger)
            }
        }
    }
}

/** The end of a command's output, following its last line as it grows; selectable. */
@Composable
private fun ProcessOutput(tail: String) {
    val colors = MaterialTheme.tsukaima
    val scroll = rememberScrollState()
    LaunchedEffect(tail.length) { scroll.scrollTo(scroll.maxValue) }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .clip(TsukaimaShapes.code)
            .background(colors.surfaceSunken)
            .verticalScroll(scroll)
            .horizontalScroll(rememberScrollState())
            .padding(10.dp),
    ) {
        SelectionContainer {
            Text(
                tail.trimEnd().ifEmpty { "No output yet" },
                style = TsukaimaType.caption.copy(fontFamily = TsukaimaType.mono.fontFamily),
                color = colors.ink2,
            )
        }
    }
}

/** "2:14" for a running command (ticking), "Finished", "Exit 1" or "Stopped" for one that ended. */
@Composable
private fun processMeta(row: ProcessRow): String {
    if (!row.running) return endedText(row.exitCode)
    val since = row.since ?: return "Running"
    val seconds by produceState((System.currentTimeMillis() - since) / 1000, since) {
        val base = SystemClock.elapsedRealtime()
        val start = (System.currentTimeMillis() - since) / 1000
        while (true) {
            value = start + (SystemClock.elapsedRealtime() - base) / 1000
            delay(1000)
        }
    }
    return elapsedText(seconds)
}

/** How a background command ended: a signal (a negative code, as from Stop) is "Stopped". */
internal fun endedText(exitCode: Int?): String = when {
    exitCode == null || exitCode == 0 -> "Finished"
    exitCode < 0 -> "Stopped"
    else -> "Exit $exitCode"
}

@Composable
private fun ProcessPreview(dark: Boolean) = PreviewPage(dark) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProcessChip(2, onClick = {})
        ProcessCard(ProcessRow("p1", "npm run build", running = true, since = System.currentTimeMillis() - 134_000, tail = "transforming…\nrendering chunks (18)…\n"), true) {}
        ProcessCard(ProcessRow("p2", "pytest -q", running = false, tail = "3 failed, 41 passed\n", exitCode = 1), true) {}
    }
}

@Preview(name = "Processes light")
@Composable
private fun ProcessLightPreview() = ProcessPreview(dark = false)

@Preview(name = "Processes dark")
@Composable
private fun ProcessDarkPreview() = ProcessPreview(dark = true)
