package labs.suchattai.tsukaima.ui.chat.components

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
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.chat.ToolRisk
import labs.suchattai.tsukaima.domain.chat.ToolStatus
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.TsukaimaIcon
import labs.suchattai.tsukaima.ui.components.pressWash
import labs.suchattai.tsukaima.ui.theme.Dimens
import labs.suchattai.tsukaima.ui.theme.Motion
import labs.suchattai.tsukaima.ui.theme.TsukaimaShapes
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/** Slim expandable row for a tool call: quiet by default, output on tap. */
@Composable
fun ToolRow(item: ChatItem.ToolCall, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(Motion.base, easing = Motion.easing), label = "toolChevron")
    val thinking = item.name.contains("think", ignoreCase = true) || item.name.contains("reason", ignoreCase = true)
    val running = item.status == ToolStatus.Running
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
                if (thinking) IconName.Psychology else IconName.Terminal,
                size = 18.dp,
                tint = if (running) colors.accent else colors.ink3,
            )
            Text(
                text = buildAnnotatedString {
                    append(item.name)
                    if (!item.context.isNullOrBlank()) {
                        withStyle(SpanStyle(color = colors.ink3)) { append(" · ${item.context}") }
                    }
                },
                style = TsukaimaType.ui,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when (item.status) {
                ToolStatus.Running -> Unit
                ToolStatus.Failed -> TsukaimaIcon(IconName.Error, size = 16.dp, tint = colors.danger, contentDescription = "Failed")
                ToolStatus.Done -> TsukaimaIcon(IconName.Check, size = 16.dp, tint = colors.ink3, contentDescription = "Done")
            }
            TsukaimaIcon(IconName.ExpandMore, size = 18.dp, tint = colors.ink2, modifier = Modifier.rotate(rotation))
        }
        item.risk?.let { RiskNote(it) }
        if (open) {
            Text(
                text = item.output?.takeIf { it.isNotBlank() } ?: "No output",
                style = if (thinking) TsukaimaType.caption else TsukaimaType.caption.copy(fontFamily = TsukaimaType.mono.fontFamily),
                color = colors.ink2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
        }
    }
}

/** `tool.output_risk` under the header: a small amber line, always visible (not only when expanded). */
@Composable
private fun RiskNote(risk: ToolRisk) {
    val colors = MaterialTheme.tsukaima
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TsukaimaIcon(IconName.Warning, size = 14.dp, tint = colors.warn)
        Text(riskText(risk), style = TsukaimaType.caption, color = colors.warn, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** "Output flagged: high risk · prompt injection · redacted". */
internal fun riskText(risk: ToolRisk): String =
    (listOf("Output flagged: ${risk.level} risk") + risk.findings.map { it.replace('_', ' ') } + listOfNotNull("redacted".takeIf { risk.redacted }))
        .joinToString(" · ")

private fun sample(status: ToolStatus, output: String? = "ok  agent.log: 50 lines") = ChatItem.ToolCall(
    id = "t-$status", name = "shell", context = "tail -n 50 /var/log/hermes/agent.log --follow --verbose --lines=50",
    args = null, status = status, output = output, exitCode = null, durationS = null,
)

@Composable
private fun ToolRowPreview(dark: Boolean) = PreviewPage(dark) {
    ToolRow(sample(ToolStatus.Done))
    ToolRow(sample(ToolStatus.Running))
    ToolRow(sample(ToolStatus.Failed, null))
    ToolRow(sample(ToolStatus.Done).copy(id = "t-risk", risk = ToolRisk("high", listOf("prompt_injection"), redacted = true)))
}

@Preview(name = "ToolRow light")
@Composable
private fun ToolRowLightPreview() = ToolRowPreview(dark = false)

@Preview(name = "ToolRow dark")
@Composable
private fun ToolRowDarkPreview() = ToolRowPreview(dark = true)
