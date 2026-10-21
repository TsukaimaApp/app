package labs.suchattai.tsukaima.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import labs.suchattai.tsukaima.ui.chat.AttachmentKind
import labs.suchattai.tsukaima.ui.chat.AttachmentStatus
import labs.suchattai.tsukaima.ui.chat.Attachments
import labs.suchattai.tsukaima.ui.chat.PendingAttachment
import labs.suchattai.tsukaima.ui.components.TsukaimaIcon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import labs.suchattai.tsukaima.ui.components.IconButton44
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.Tone
import labs.suchattai.tsukaima.ui.components.floatShadow
import labs.suchattai.tsukaima.ui.theme.Dimens
import labs.suchattai.tsukaima.ui.theme.TsukaimaShapes
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/** What the 44dp trailing slot of the [Composer] does. */
enum class ComposerAction { Voice, Send, Stop }

/**
 * Pure choice for the trailing slot: [ComposerAction.Stop] while a turn [running] on an [enabled] composer;
 * [ComposerAction.Voice] when it is enabled, voice is [voiceAvailable] and there is nothing to send (blank
 * [value], no attachments); otherwise [ComposerAction.Send].
 */
fun composerAction(
    value: String,
    hasAttachments: Boolean,
    enabled: Boolean,
    running: Boolean,
    voiceAvailable: Boolean,
): ComposerAction = when {
    running && enabled -> ComposerAction.Stop
    enabled && voiceAvailable && value.isBlank() && !hasAttachments -> ComposerAction.Voice
    else -> ComposerAction.Send
}

/**
 * Bottom composer pill: pending attachments (chips) above the row of attach, multiline field (max 120dp) and
 * send. The IME Send action and a hardware Enter send; hardware Shift+Enter inserts a newline. [canSend]
 * gates sending (text or a ready attachment); the caller clears [value] in [onSend]. While the agent is
 * [running] a turn, send becomes a danger-filled Stop ([onStop]) in the same 44dp slot, and Enter does nothing. When [onVoice] is set and there is nothing to send, send
 * becomes an accent mic that calls it (see [composerAction]). [focusRequester], when set, is attached to the text
 * field (leaving voice mode for the keyboard focuses it). While a sent message is edited, [editCaption] ("Editing —
 * tap to cancel") sits above the field; a tap on it calls [onCancelEdit].
 */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String = "Message your agent",
    canSend: Boolean = enabled && value.isNotBlank(),
    attachments: List<PendingAttachment> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
    onRetryAttachment: (String) -> Unit = {},
    running: Boolean = false,
    onStop: () -> Unit = {},
    disabledPlaceholder: String = "Hermes is unreachable",
    onVoice: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    editCaption: String? = null,
    onCancelEdit: () -> Unit = {},
) {
    val colors = MaterialTheme.tsukaima
    val shape = TsukaimaShapes.composer
    val send = { if (enabled && canSend && !running) onSend() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 14.dp)
            .alpha(if (enabled) 1f else 0.55f)
            .floatShadow(shape)
            .background(colors.surfaceRaised, shape)
            .border(1.dp, colors.hairline, shape)
            .padding(6.dp),
    ) {
        if (editCaption != null) EditingCaption(editCaption, onCancelEdit)
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 6.dp, end = 6.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                attachments.forEach { a ->
                    AttachmentChip(a, onRemove = { onRemoveAttachment(a.localId) }, onRetry = { onRetryAttachment(a.localId) })
                }
            }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton44(IconName.Add, "Attach", onClick = onAttach, enabled = enabled)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = TsukaimaType.body.copy(color = colors.ink1),
                cursorBrush = SolidColor(colors.accent),
                // Default, not Send: the on-screen keyboard's Enter inserts a newline; the Send button sends.
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 120.dp)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onPreviewKeyEvent { event ->
                        // A physical keyboard sends on Enter (Shift+Enter = newline); the virtual one never does.
                        if (event.key == Key.Enter &&
                            ComposerKeys.enterSends(event.nativeKeyEvent.deviceId, event.isShiftPressed)
                        ) {
                            if (event.type == KeyEventType.KeyDown) send()
                            true
                        } else {
                            false
                        }
                    },
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = 4.dp, vertical = 11.dp)) {
                        if (value.isEmpty()) {
                            Text(
                                text = if (enabled) placeholder else disabledPlaceholder,
                                style = TsukaimaType.body,
                                color = colors.ink3,
                            )
                        }
                        inner()
                    }
                },
            )
            when (composerAction(value, attachments.isNotEmpty(), enabled, running, voiceAvailable = onVoice != null)) {
                ComposerAction.Stop ->
                    IconButton44(IconName.Stop, "Stop", onClick = onStop, filled = true, tone = Tone.Danger)
                ComposerAction.Voice ->
                    IconButton44(IconName.Mic, "Voice", onClick = { onVoice?.invoke() }, filled = true)
                ComposerAction.Send ->
                    IconButton44(IconName.ArrowUpward, "Send", onClick = send, filled = enabled && canSend, enabled = enabled && canSend)
            }
        }
    }
}

/**
 * One pending attachment: kind icon, name and size, and a close button. Uploading shows at 55 % alpha; a
 * failed upload turns danger and retries on tap.
 */
@Composable
private fun AttachmentChip(a: PendingAttachment, onRemove: () -> Unit, onRetry: () -> Unit) {
    val colors = MaterialTheme.tsukaima
    val failed = a.status == AttachmentStatus.Failed
    val ink = if (failed) colors.danger else colors.ink2
    val shape = TsukaimaShapes.pill
    Row(
        modifier = Modifier
            .alpha(if (a.status == AttachmentStatus.Uploading) 0.55f else 1f)
            .clip(shape)
            .border(1.dp, if (failed) colors.danger else colors.hairline, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The label is one merged node (its Retry action when failed); Remove stays a sibling so TalkBack
        // reaches it separately instead of losing it inside the merge.
        Row(
            modifier = Modifier
                .clickable(enabled = failed, role = Role.Button, onClickLabel = "Retry upload", onClick = onRetry)
                .semantics(mergeDescendants = true) {
                    stateDescription = when (a.status) {
                        AttachmentStatus.Uploading -> "Uploading"
                        AttachmentStatus.Ready -> "Attached"
                        AttachmentStatus.Failed -> "Upload failed, tap to retry"
                    }
                }
                .padding(start = 10.dp)
                .heightIn(min = Dimens.hit),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TsukaimaIcon(
                when {
                    failed -> IconName.Refresh
                    a.kind == AttachmentKind.Image -> IconName.Image
                    a.kind == AttachmentKind.Pdf -> IconName.PictureAsPdf
                    else -> IconName.AttachFile
                },
                contentDescription = null,
                size = 16.dp,
                tint = ink,
            )
            // A PDF that landed shows its page count (and the server's note, e.g. the page cap, under it).
            val pdfReady = a.kind == AttachmentKind.Pdf && a.status == AttachmentStatus.Ready
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = a.name,
                        style = TsukaimaType.caption,
                        color = if (failed) colors.danger else colors.ink1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                    Text(
                        if (pdfReady) Attachments.pageCount(a.pages) else Attachments.humanSize(a.sizeBytes),
                        style = TsukaimaType.caption,
                        color = ink,
                    )
                }
                if (pdfReady && a.note != null) {
                    Text(
                        a.note,
                        style = TsukaimaType.caption,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .size(Dimens.hit) // the 44dp hit minimum; the pill grows to match
                .clickable(role = Role.Button, onClickLabel = "Remove ${a.name}", onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            TsukaimaIcon(IconName.Close, size = 16.dp, tint = ink, contentDescription = "Remove")
        }
    }
}

/** [text] ("Editing — tap to cancel") above the field while a sent message is being edited; the whole 44dp row cancels. */
@Composable
private fun EditingCaption(text: String, onCancel: () -> Unit) {
    val colors = MaterialTheme.tsukaima
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.hit)
            .clip(TsukaimaShapes.pill)
            .clickable(onClickLabel = "Cancel the edit", role = Role.Button, onClick = onCancel)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TsukaimaIcon(IconName.Edit, size = 14.dp, tint = colors.ink2, contentDescription = null)
        Text(text, style = TsukaimaType.caption, color = colors.ink2)
    }
}

@Composable
private fun ComposerPreview(dark: Boolean) = PreviewPage(dark) {
    var text by remember { mutableStateOf("") }
    Composer(text, { text = it }, {}, {}, enabled = true)
    Composer("Restart the agent and tail the logs.", {}, {}, {}, enabled = true)
    Composer("", {}, {}, {}, enabled = false)
    Composer("", {}, {}, {}, enabled = true, running = true)
    Composer("", {}, {}, {}, enabled = true, onVoice = {})
    Composer("And the init system?", {}, {}, {}, enabled = true, editCaption = "Editing — attachments not included — tap to cancel")
    Composer(
        "What is in these?", {}, {}, {}, enabled = true, canSend = false,
        attachments = listOf(
            PendingAttachment("a1", "notes.txt", AttachmentKind.File, 2_048, AttachmentStatus.Ready, refText = "@file:notes.txt"),
            PendingAttachment("a2", "screenshot_2026-10-20.png", AttachmentKind.Image, 1_400_000, AttachmentStatus.Uploading),
            PendingAttachment("a3", "report.pdf", AttachmentKind.Pdf, 9_000_000, AttachmentStatus.Failed),
        ),
    )
    Composer(
        "Summarize these.", {}, {}, {}, enabled = true,
        attachments = listOf(
            PendingAttachment("p1", "invoice.pdf", AttachmentKind.Pdf, 240_000, AttachmentStatus.Ready, pages = 2),
            PendingAttachment(
                "p2", "annual_report_2025.pdf", AttachmentKind.Pdf, 8_400_000, AttachmentStatus.Ready,
                pages = 25, note = "First 25 pages attached",
            ),
        ),
    )
}

@Preview(name = "Composer light")
@Composable
private fun ComposerLightPreview() = ComposerPreview(dark = false)

@Preview(name = "Composer dark")
@Composable
private fun ComposerDarkPreview() = ComposerPreview(dark = true)
