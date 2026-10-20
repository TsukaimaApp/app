package labs.suchattai.tsukaima.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.ui.components.GhostButton
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.PrimaryButton
import labs.suchattai.tsukaima.ui.components.Tone
import labs.suchattai.tsukaima.ui.components.TsukaimaIcon
import labs.suchattai.tsukaima.ui.components.floatShadow
import labs.suchattai.tsukaima.ui.theme.Dimens
import labs.suchattai.tsukaima.ui.theme.TsukaimaShapes
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/** Floating-card shell shared by [ApprovalCard] and [PairingCard]: raised, hairline, shadow, 16dp pad. */
@Composable
internal fun FloatingCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = MaterialTheme.tsukaima
    val shape = TsukaimaShapes.card
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.gutter, vertical = 8.dp)
            .floatShadow(shape)
            .background(colors.surfaceRaised, shape)
            .border(1.dp, colors.hairline, shape)
            .padding(16.dp),
    ) { content() }
}

/** Header row: amber icon plus serif-italic title. */
@Composable
internal fun CardHeader(icon: IconName, title: String) {
    val colors = MaterialTheme.tsukaima
    Row(
        modifier = Modifier.padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TsukaimaIcon(icon, size = 20.dp, tint = colors.warn)
        Text(title, style = TsukaimaType.statusLine, color = colors.ink1)
    }
}

/**
 * Inline approval request. [approved] is the decision once [ChatItem.Approval.answered] is
 * true (the domain item only records that it was answered); null shows a neutral "Answered".
 */
@Composable
fun ApprovalCard(
    item: ChatItem.Approval,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
    approved: Boolean? = null,
) {
    val colors = MaterialTheme.tsukaima
    FloatingCard(modifier) {
        CardHeader(IconName.GppMaybe, "Hermes wants to run a tool")
        Text(
            text = item.toolName.uppercase(),
            style = TsukaimaType.label,
            color = colors.ink3,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Text(
            text = item.command,
            style = TsukaimaType.mono,
            color = colors.codeInk,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.codeBg, TsukaimaShapes.code)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            if (item.answered) {
                Text(
                    text = when (approved) {
                        true -> "Approved"
                        false -> "Denied"
                        null -> "Answered"
                    },
                    style = TsukaimaType.caption,
                    color = colors.ink3,
                    modifier = Modifier.weight(1f),
                )
            }
            GhostButton("Deny", onDeny, tone = Tone.Danger, enabled = !item.answered)
            PrimaryButton("Approve", onApprove, icon = IconName.Check, enabled = !item.answered)
        }
    }
}

private fun sample(answered: Boolean) = ChatItem.Approval(
    id = "srq-1", requestId = "srq-1", command = "rm -rf build/ && ./gradlew assembleRelease --no-daemon --stacktrace",
    toolName = "shell", answered = answered,
)

@Composable
private fun ApprovalPreview(dark: Boolean) = PreviewPage(dark) {
    ApprovalCard(sample(false), {}, {})
    ApprovalCard(sample(true), {}, {}, approved = true)
}

@Preview(name = "ApprovalCard light")
@Composable
private fun ApprovalLightPreview() = ApprovalPreview(dark = false)

@Preview(name = "ApprovalCard dark")
@Composable
private fun ApprovalDarkPreview() = ApprovalPreview(dark = true)
