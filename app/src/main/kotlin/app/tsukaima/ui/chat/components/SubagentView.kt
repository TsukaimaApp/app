package app.tsukaima.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.SubagentStatus
import app.tsukaima.ui.chat.SubagentWatch
import app.tsukaima.ui.components.ButtonSize
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.SecondaryButton
import app.tsukaima.ui.components.Tone
import app.tsukaima.ui.components.TsukaimaIcon
import app.tsukaima.ui.components.TsukaimaTextField
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/**
 * One delegated child on its own page: its goal and state, its live transcript ([SubagentWatch.tail], selectable,
 * kept at the newest line), and, while it runs and Hermes is reachable ([canAct]), a message field (the child reads
 * it after its current tool call) and Stop, which ends this child only. A finished child shows what it reported.
 */
@Composable
fun SubagentView(
    item: ChatItem.Subagent,
    watch: SubagentWatch,
    canAct: Boolean,
    onClose: () -> Unit,
    onStop: () -> Unit,
    onSteer: (text: String, done: (Boolean) -> Unit) -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.let { window ->
            SideEffect { window.setDimAmount(0f) }
        }
        val colors = MaterialTheme.tsukaima
        val running = item.status == SubagentStatus.Running
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.surfacePage)
                .systemBarsPadding()
                .imePadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .size(Dimens.hit)
                        .clickable(role = Role.Button, onClickLabel = "Close", onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    TsukaimaIcon(IconName.ArrowBack, contentDescription = "Close", tint = colors.ink2)
                }
                Text(
                    if (item.position != null) "Subagent ${item.position}" else "Subagent",
                    style = TsukaimaType.sectionTitle,
                    color = colors.ink1,
                    modifier = Modifier.weight(1f),
                )
                if (running && canAct) SecondaryButton("Stop", onStop, size = ButtonSize.Sm, icon = IconName.Stop, tone = Tone.Danger, modifier = Modifier.padding(end = 12.dp))
            }
            val scroll = rememberScrollState()
            val body = subagentBody(item, watch)
            // New lines arrive at the end: stay with them.
            LaunchedEffect(body.length) { scroll.scrollTo(scroll.maxValue) }
            SelectionContainer(Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(horizontal = Dimens.gutter, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (item.goal.isNotBlank()) Text(item.goal, style = TsukaimaType.body, color = colors.ink1)
                    Text(subagentState(item), style = TsukaimaType.caption, color = colors.ink3)
                    item.summary?.takeIf { !running }?.let { Text(it, style = TsukaimaType.body, color = colors.ink1) }
                    Text(body, style = TsukaimaType.caption.copy(fontFamily = TsukaimaType.mono.fontFamily), color = colors.ink2)
                }
            }
            if (running && canAct) {
                var draft by rememberSaveable(item.id) { mutableStateOf("") }
                // Not saved: after a rotation the answer of a send in flight has no one to clear it.
                var sending by remember(item.id) { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.gutter, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TsukaimaTextField(draft, { draft = it }, modifier = Modifier.weight(1f), placeholder = "Message this subagent", singleLine = false, maxLines = 4)
                    SecondaryButton(
                        "Send",
                        onClick = {
                            sending = true
                            onSteer(draft) { taken ->
                                sending = false
                                if (taken) draft = ""
                            }
                        },
                        enabled = draft.isNotBlank() && !sending,
                        size = ButtonSize.Sm,
                        icon = IconName.Send,
                    )
                }
            }
        }
    }
}

/**
 * What the page shows under the goal: the child's live transcript; before the first read "Loading…"; when the server
 * has none (the child ended before the page opened, or has written nothing yet) the row's own last progress line.
 */
internal fun subagentBody(item: ChatItem.Subagent, watch: SubagentWatch): String = when {
    !watch.tail.isNullOrBlank() -> watch.tail
    watch.tail == null && item.subagentId != null -> "Loading…"
    item.status == SubagentStatus.Running -> item.detail ?: "Nothing written yet"
    else -> item.detail?.takeIf { item.summary == null } ?: "The live transcript is gone: Hermes keeps it only while the subagent runs."
}
