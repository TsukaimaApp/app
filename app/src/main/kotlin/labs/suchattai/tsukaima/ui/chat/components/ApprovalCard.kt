package labs.suchattai.tsukaima.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.data.gateway.ApprovalChoice
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.ui.components.GhostButton
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.MenuItem
import labs.suchattai.tsukaima.ui.components.MenuSheet
import labs.suchattai.tsukaima.ui.components.PrimaryButton
import labs.suchattai.tsukaima.ui.components.Tone
import labs.suchattai.tsukaima.ui.components.TsukaimaIcon
import labs.suchattai.tsukaima.ui.components.floatShadow
import labs.suchattai.tsukaima.ui.theme.Dimens
import labs.suchattai.tsukaima.ui.theme.TsukaimaShapes
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

private const val CommandMaxLines = 8

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
 * The wider approval scopes the "Approve for…" sheet offers for [item]: `session` and `always`, each only when the
 * server allows it ([ChatItem.Approval.allowSession] / [ChatItem.Approval.allowAlways]). Empty hides the button.
 */
internal fun approvalScopes(item: ChatItem.Approval): List<ApprovalChoice> =
    listOfNotNull(ApprovalChoice.session.takeIf { item.allowSession }, ApprovalChoice.always.takeIf { item.allowAlways })

/** The answered card's caption for the [choice] sent; null (answered elsewhere) is a neutral "Answered". */
internal fun decisionLabel(choice: ApprovalChoice?): String = when (choice) {
    ApprovalChoice.once -> "Approved"
    ApprovalChoice.session -> "Approved for this session"
    ApprovalChoice.always -> "Always approved"
    ApprovalChoice.deny -> "Denied"
    null -> "Answered"
}

/**
 * Inline approval request. Deny and Approve (`once`) are the primary pair; when the server offers wider scopes
 * ([approvalScopes]) a small "Approve for…" button opens a sheet with "This session" / "Always", answered through
 * [onApproveFor]. [decision] is the choice sent once [ChatItem.Approval.answered] is true (the domain item only
 * records that it was answered); null shows a neutral "Answered". When not [answerable] (the app has no id that
 * binds the answer to this request) every button is disabled and the card points to the dashboard.
 */
@Composable
fun ApprovalCard(
    item: ChatItem.Approval,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
    decision: ApprovalChoice? = null,
    answerable: Boolean = true,
    onApproveFor: (ApprovalChoice) -> Unit = {},
) {
    val colors = MaterialTheme.tsukaima
    var scopesOpen by remember(item.id) { mutableStateOf(false) }
    FloatingCard(modifier) {
        CardHeader(IconName.GppMaybe, "Hermes wants to run a tool")
        Text(
            text = item.toolName.uppercase(),
            style = TsukaimaType.label,
            color = colors.ink3,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        val revealed = remember(item.command) { CommandText.reveal(item.command) }
        var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
        // null = not measured yet; fail closed so Approve is never live before the layout is known.
        var hiddenLines by remember(item.command) { mutableStateOf<Int?>(null) }
        val measurer = rememberTextMeasurer()
        val blocked = !expanded && hiddenLines != 0
        Text(
            text = revealed.text,
            style = TsukaimaType.mono,
            color = colors.codeInk,
            maxLines = if (expanded) Int.MAX_VALUE else CommandMaxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout ->
                if (!expanded) {
                    hiddenLines = if (!layout.hasVisualOverflow) {
                        0
                    } else {
                        // Visual lines: re-measure unconstrained by height at the same width.
                        val full = measurer.measure(
                            text = revealed.text,
                            style = TsukaimaType.mono,
                            constraints = Constraints(maxWidth = layout.size.width),
                        )
                        maxOf(1, full.lineCount - layout.lineCount)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.codeBg, TsukaimaShapes.code)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
        if (expanded || (hiddenLines ?: 0) > 0) {
            Text(
                text = if (expanded) "Show less" else "Show all",
                style = TsukaimaType.caption,
                color = colors.accent,
                modifier = Modifier
                    .heightIn(min = Dimens.hit)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(horizontal = 4.dp)
                    .wrapContentHeight(Alignment.CenterVertically),
            )
        }
        if (blocked && (hiddenLines ?: 0) > 0) {
            val n = hiddenLines ?: 0
            Text(
                text = "$n more ${if (n == 1) "line" else "lines"} hidden \u2014 Show all to approve",
                style = TsukaimaType.caption,
                color = colors.warn,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (revealed.hadHiddenChars) {
            Text(
                text = "This command contains hidden characters.",
                style = TsukaimaType.caption,
                color = colors.warn,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        ) {
            if (!item.answered && !answerable) {
                Text(
                    text = "Answer this on the dashboard",
                    style = TsukaimaType.caption,
                    color = colors.ink3,
                    modifier = Modifier.weight(1f),
                )
            }
            if (item.answered) {
                Text(
                    text = decisionLabel(decision),
                    style = TsukaimaType.caption,
                    color = colors.ink3,
                    modifier = Modifier.weight(1f),
                )
            } else if (answerable && approvalScopes(item).isNotEmpty()) {
                Text(
                    text = "Approve for…",
                    style = TsukaimaType.caption,
                    color = if (blocked) colors.ink3 else colors.accent,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = Dimens.hit)
                        .clickable(enabled = !blocked, role = Role.Button) { scopesOpen = true }
                        .padding(horizontal = 4.dp)
                        .wrapContentHeight(Alignment.CenterVertically),
                )
            }
            GhostButton("Deny", onDeny, tone = Tone.Danger, enabled = answerable && !item.answered)
            PrimaryButton("Approve", onApprove, icon = IconName.Check, enabled = answerable && !item.answered && !blocked)
        }
    }
    MenuSheet(
        visible = scopesOpen && !item.answered,
        onDismiss = { scopesOpen = false },
        items = approvalScopes(item).map { choice ->
            when (choice) {
                ApprovalChoice.session -> MenuItem("This session", IconName.History) { onApproveFor(choice) }
                else -> MenuItem("Always", IconName.Check) { onApproveFor(choice) }
            }
        },
    )
}

private fun sample(answered: Boolean) = ChatItem.Approval(
    id = "srq-1", requestId = "srq-1", command = "rm -rf build/ && ./gradlew assembleRelease --no-daemon --stacktrace\n" + "echo line\n".repeat(10) + "ls \u202Etxt.exe",
    toolName = "shell", answered = answered,
)

@Composable
private fun ApprovalPreview(dark: Boolean) = PreviewPage(dark) {
    ApprovalCard(sample(false), {}, {})
    ApprovalCard(sample(false).copy(id = "srq-2", command = "rm -r /tmp/x", allowSession = true, allowAlways = true), {}, {})
    ApprovalCard(sample(true), {}, {}, decision = ApprovalChoice.session)
    ApprovalCard(sample(false).copy(id = "pending-", requestId = "", command = "reboot"), {}, {}, answerable = false)
}

@Preview(name = "ApprovalCard light")
@Composable
private fun ApprovalLightPreview() = ApprovalPreview(dark = false)

@Preview(name = "ApprovalCard dark")
@Composable
private fun ApprovalDarkPreview() = ApprovalPreview(dark = true)
