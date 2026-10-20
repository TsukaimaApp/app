package labs.suchattai.tsukaima.ui.voice

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import labs.suchattai.tsukaima.R
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.domain.chat.ChatItem
import labs.suchattai.tsukaima.domain.chat.ChatState
import labs.suchattai.tsukaima.domain.chat.ToolStatus
import labs.suchattai.tsukaima.domain.voice.VoiceMode
import labs.suchattai.tsukaima.domain.voice.VoicePhase
import labs.suchattai.tsukaima.domain.voice.VoiceState
import labs.suchattai.tsukaima.ui.chat.ChatUiState
import labs.suchattai.tsukaima.ui.chat.components.AssistantMessage
import labs.suchattai.tsukaima.ui.chat.components.ToolRow
import labs.suchattai.tsukaima.ui.components.ButtonSize
import labs.suchattai.tsukaima.ui.components.GhostButton
import labs.suchattai.tsukaima.ui.components.IconButton44
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.floatShadow
import labs.suchattai.tsukaima.ui.theme.TsukaimaShapes
import labs.suchattai.tsukaima.ui.theme.TsukaimaTheme
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/** The handoff's scrim, rgba(39,34,32,.38): light `ink1` at 38 %, the same in dark. */
private val AssistScrim = Color(0xFF272220).copy(alpha = 0.38f)

/** A downward drag on the sheet longer than this dismisses it. */
private val DismissDrag = 80.dp

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * The device-assistant sheet (handoff Screen B) over whatever app is in front: a scrim (tap = [onDismiss]) and a
 * bottom sheet with the mark, Keyboard and Open icons ([onKeyboard], [onOpenApp]), the [VoiceIndicator] (tap =
 * [onTap]: barge-in, or listen again when idle) beside the voice status line and the latest utterance, then this
 * exchange's results (tool rows, the reply as it is spoken) and "Continue in Tsukaima". A downward drag on the sheet
 * dismisses it. Request cards are never shown here: the voice controller opens the app on them. [canOpenApp] false
 * (the chat has no stored id yet) disables the three ways into the app.
 */
@Composable
fun AssistOverlay(
    state: ChatUiState,
    voice: VoiceState,
    onKeyboard: () -> Unit,
    onOpenApp: () -> Unit,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    canOpenApp: Boolean = true,
) {
    val colors = MaterialTheme.tsukaima
    val items = state.chat.items.drop(voice.startIndex)
    val lastUser = items.indexOfLast { it is ChatItem.UserMessage }
    // While listening, the partial result; otherwise the last thing said in this sheet.
    val utterance = voice.heard.ifBlank { (items.getOrNull(lastUser) as? ChatItem.UserMessage)?.text.orEmpty() }
    // This exchange's results: what followed the latest utterance (one exchange per invocation, or per re-listen).
    val results = items.drop(lastUser + 1).filter { it is ChatItem.ToolCall || it is ChatItem.AssistantMessage }
    val dismissPx = with(LocalDensity.current) { DismissDrag.toPx() }
    val noInteraction = remember { MutableInteractionSource() }
    val openAlpha = if (canOpenApp) 1f else 0.45f // the disabled look of ChatTopBar's More

    BoxWithConstraints(modifier.fillMaxSize()) {
        val maxSheet = maxHeight * 0.85f
        Box(
            Modifier
                .fillMaxSize()
                .background(AssistScrim)
                .clickable(interactionSource = noInteraction, indication = null, onClickLabel = "Dismiss", onClick = onDismiss),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = maxSheet)
                .floatShadow(SheetShape)
                .clip(SheetShape)
                .background(colors.surfaceRaised)
                // Also stops taps on the sheet from reaching the scrim beneath it.
                .pointerInput(onDismiss, dismissPx) {
                    // Net downward travel of the current drag (the sheet stays put: no moving frame to correct for).
                    var drag = 0f
                    detectVerticalDragGestures(
                        onDragStart = { drag = 0f },
                        onDragEnd = { if (drag > dismissPx) onDismiss() },
                        onDragCancel = { drag = 0f },
                    ) { change, amount ->
                        change.consume()
                        drag += amount
                    }
                }
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 32.dp, height = 4.dp)
                    .background(colors.hairline, TsukaimaShapes.pill),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(
                    painter = painterResource(R.drawable.mark),
                    contentDescription = null,
                    // mark.xml is the light lavender; the accent tint gives mark-dark.svg's #A89CFF in dark.
                    colorFilter = ColorFilter.tint(colors.accent),
                    modifier = Modifier.height(18.dp),
                )
                Text("Tsukaima", style = TsukaimaType.wordmark.copy(fontSize = 17.sp), color = colors.ink1)
                Spacer(Modifier.weight(1f))
                IconButton44(IconName.Keyboard, "Type", onKeyboard, Modifier.alpha(openAlpha), enabled = canOpenApp, size = 40.dp)
                IconButton44(IconName.OpenInNew, "Open Tsukaima", onOpenApp, Modifier.alpha(openAlpha), enabled = canOpenApp, size = 40.dp)
            }
            Row(
                modifier = Modifier.clickable(interactionSource = noInteraction, indication = null, role = Role.Button, onClick = onTap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                VoiceIndicator(voice.phase, 72.dp, onTap = onTap)
                Column(Modifier.weight(1f)) {
                    VoiceStatusLine(voiceLine(voice.phase, state.status, VoiceMode.Assist, voice.transcribing), fontSize = 14.sp, centred = false)
                    if (utterance.isNotBlank()) {
                        Text(utterance, style = TsukaimaType.body, color = colors.ink1, modifier = Modifier.padding(top = 4.dp))
                    }
                    voice.error?.let {
                        Text(it, style = TsukaimaType.caption, color = colors.danger, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            if (results.isNotEmpty()) Results(results, voice, Modifier.weight(1f, fill = false))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GhostButton("Continue in Tsukaima", onOpenApp, enabled = canOpenApp, size = ButtonSize.Sm)
            }
            // The design's gesture bar stands in for the system's: draw it only where the system draws none.
            if (WindowInsets.navigationBars.getBottom(LocalDensity.current) == 0) {
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .align(Alignment.CenterHorizontally)
                        .size(width = 108.dp, height = 4.dp)
                        .alpha(0.4f)
                        .background(colors.ink1, TsukaimaShapes.pill),
                )
            }
        }
    }
}

/** Tool rows and the reply (revealed as spoken), scrolling to keep the newest words in view on a long answer. */
@Composable
private fun Results(results: List<ChatItem>, voice: VoiceState, modifier: Modifier) {
    val scroll = rememberScrollState()
    LaunchedEffect(scroll) { snapshotFlow { scroll.maxValue }.collectLatest { scroll.animateScrollTo(it) } }
    Column(modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        results.forEach { item ->
            key(item.id) {
                when (item) {
                    is ChatItem.ToolCall -> ToolRow(item, margin = PaddingValues(0.dp))
                    is ChatItem.AssistantMessage -> {
                        val text = voiceText(item, voice.revealed)
                        AssistantMessage(text, item.streaming || text.length < item.text.length, reasoning = null, padding = PaddingValues(0.dp))
                    }
                    else -> Unit
                }
            }
        }
    }
}

// ---- previews -------------------------------------------------------------------------------------

private val previewChat = ChatState(
    items = listOf(
        ChatItem.UserMessage("u1", "Pause the nightly backup job."),
        ChatItem.ToolCall("t1", "cron", "pause nightly-backup", null, ToolStatus.Done, null, 0, 0.4),
        ChatItem.AssistantMessage("a1", "Paused. It won’t run at 3am — say resume when you want it back.", streaming = false, reasoning = null),
    ),
    status = AgentStatus.Idle,
    storedSessionId = "20261020_010916_48c967",
)

@Composable
private fun AssistOverlayPreview(dark: Boolean, voice: VoiceState, status: AgentStatus = AgentStatus.Idle) {
    TsukaimaTheme(darkTheme = dark) {
        Box(Modifier.background(if (dark) Color(0xFF2A2725) else Color(0xFFE9E6E0))) {
            AssistOverlay(
                state = ChatUiState(chat = previewChat.copy(status = status), isNew = false),
                voice = voice,
                onKeyboard = {},
                onOpenApp = {},
                onTap = {},
                onDismiss = {},
            )
        }
    }
}

private val previewSpeaking = VoiceState(phase = VoicePhase.Speaking, revealed = mapOf("a1" to 38))
private val previewIdle = VoiceState(phase = VoicePhase.Idle)

@Preview(name = "AssistOverlay speaking light", heightDp = 760)
@Composable
private fun AssistOverlaySpeakingLight() = AssistOverlayPreview(false, previewSpeaking, AgentStatus.Replying)

@Preview(name = "AssistOverlay speaking dark", heightDp = 760)
@Composable
private fun AssistOverlaySpeakingDark() = AssistOverlayPreview(true, previewSpeaking, AgentStatus.Replying)

@Preview(name = "AssistOverlay idle light", heightDp = 760)
@Composable
private fun AssistOverlayIdleLight() = AssistOverlayPreview(false, previewIdle)

@Preview(name = "AssistOverlay idle dark", heightDp = 760)
@Composable
private fun AssistOverlayIdleDark() = AssistOverlayPreview(true, previewIdle)
