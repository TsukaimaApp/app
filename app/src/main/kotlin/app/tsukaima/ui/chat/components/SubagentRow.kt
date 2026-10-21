package app.tsukaima.ui.chat.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.SubagentStatus
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.TsukaimaIcon
import app.tsukaima.ui.components.pressWash
import app.tsukaima.ui.theme.Dimens
import app.tsukaima.ui.theme.Motion
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/**
 * A delegated child agent (`subagent.*`), styled like [ToolRow]: "Subagent · goal" with its status icon; tap shows
 * its latest progress line and, once finished, its summary.
 */
@Composable
fun SubagentRow(item: ChatItem.Subagent, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(Motion.base, easing = Motion.easing), label = "subagentChevron")
    val shape = TsukaimaShapes.row
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.gutter, vertical = 6.dp)
            .clip(shape)
            .background(colors.surfaceRaised, shape)
            .border(1.dp, colors.hairline, shape)
            .animateContentSize(tween(Motion.base, easing = Motion.easing)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Dimens.hit)
                .pressWash(source)
                .clickable(interactionSource = source, indication = null, role = Role.Button) { open = !open }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TsukaimaIcon(
                IconName.AccountTree,
                contentDescription = null,
                size = 18.dp,
                tint = if (item.status == SubagentStatus.Running) colors.accent else colors.ink3,
            )
            Text(
                text = buildAnnotatedString {
                    append(if (item.position != null) "Subagent ${item.position}" else "Subagent")
                    if (item.goal.isNotBlank()) withStyle(SpanStyle(color = colors.ink3)) { append(" · ${item.goal}") }
                },
                style = TsukaimaType.ui,
                color = colors.ink2,
                // One line while collapsed; opening the row unfolds the whole goal.
                maxLines = if (open) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when (item.status) {
                SubagentStatus.Running -> Unit
                SubagentStatus.Failed -> TsukaimaIcon(IconName.Error, size = 16.dp, tint = colors.danger, contentDescription = "Failed")
                SubagentStatus.Done -> TsukaimaIcon(IconName.Check, size = 16.dp, tint = colors.ink3, contentDescription = "Done")
            }
            TsukaimaIcon(IconName.ExpandMore, contentDescription = null, size = 18.dp, tint = colors.ink2, modifier = Modifier.rotate(rotation))
        }
        if (open) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(subagentState(item), style = TsukaimaType.caption, color = colors.ink3)
                item.detail?.let { Text(it, style = TsukaimaType.caption, color = colors.ink2) }
                item.summary?.let { Text(it, style = TsukaimaType.caption, color = colors.ink1) }
            }
        }
    }
}

private fun subagentState(item: ChatItem.Subagent): String = when (item.status) {
    SubagentStatus.Running -> "Running…"
    SubagentStatus.Done -> "Finished"
    SubagentStatus.Failed -> "Did not finish"
}

@Composable
private fun SubagentPreview(dark: Boolean) = PreviewPage(dark) {
    SubagentRow(ChatItem.Subagent("sub-1", "Reply with the single word pong.", SubagentStatus.Running, detail = "deliberating…"))
    SubagentRow(ChatItem.Subagent("sub-2", "Summarize the gateway log", SubagentStatus.Done, summary = "No errors since 09:00.", position = "2/3"))
    SubagentRow(ChatItem.Subagent("sub-3", "Fetch the release notes", SubagentStatus.Failed))
}

@Preview(name = "SubagentRow light")
@Composable
private fun SubagentLightPreview() = SubagentPreview(dark = false)

@Preview(name = "SubagentRow dark")
@Composable
private fun SubagentDarkPreview() = SubagentPreview(dark = true)
