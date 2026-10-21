package app.tsukaima.ui.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import app.tsukaima.domain.chat.AgentStatus
import app.tsukaima.domain.chat.ChatItem
import app.tsukaima.domain.chat.ChatState
import app.tsukaima.domain.chat.ToolStatus
import app.tsukaima.domain.voice.VoiceMode
import app.tsukaima.domain.voice.VoicePhase
import app.tsukaima.domain.voice.VoiceState
import app.tsukaima.ui.chat.ChatActions
import app.tsukaima.ui.chat.ChatMenu
import app.tsukaima.ui.chat.ChatUiState
import app.tsukaima.ui.chat.TranscriptItem
import app.tsukaima.ui.chat.components.MediaHost
import app.tsukaima.ui.chat.components.UserBubble
import app.tsukaima.ui.components.Chip
import app.tsukaima.ui.components.IconButton44
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.TsukaimaIcon
import app.tsukaima.ui.components.pressWash
import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.ui.theme.TsukaimaTheme
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima

/**
 * How much of reply [item] voice mode shows: what was spoken so far ([revealed], from [VoiceState.revealed]); a reply
 * without a cursor shows nothing while it streams and in full once complete (nothing of it will be spoken).
 */
internal fun voiceText(item: ChatItem.AssistantMessage, revealed: Map<String, Int>): String =
    item.text.take(revealed[item.id] ?: if (item.streaming) 0 else item.text.length)

/**
 * In-app voice mode (handoff Screen A): back chevron, model chip and voice language button ([ChatMenu.VoiceLanguage])
 * on top; the [VoiceIndicator] (tap = barge-in or listen again), the voice status line and the conversation since
 * voice mode opened ([VoiceState.startIndex]) in a scrolling column that keeps the newest words in view; Mute, End
 * and Keyboard below. Replies appear as they are spoken. Request cards are the chat's own and are answered only by tapping them. [onModel] opens the model picker;
 * [onKeyboard] leaves for the composer.
 */
@Composable
fun VoiceScreen(
    state: ChatUiState,
    voice: VoiceState,
    actions: ChatActions,
    onModel: () -> Unit,
    modifier: Modifier = Modifier,
    onKeyboard: () -> Unit = actions.onVoiceKeyboard,
) {
    val colors = MaterialTheme.tsukaima
    val items = state.chat.items.drop(voice.startIndex)
    val media = remember(state.mediaState, actions) {
        MediaHost(
            state.mediaState, actions.onOpenMedia, actions.previewMedia,
            actions.previewImage, actions.imageAllowed, actions.onAllowImage, actions.previewToolImage,
        )
    }
    val scroll = rememberScrollState()
    // Every new item, revealed word and partial result grows the column: follow its bottom.
    LaunchedEffect(scroll) { snapshotFlow { scroll.maxValue }.collectLatest { scroll.animateScrollTo(it) } }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val topPad = with(LocalDensity.current) { (windowHeight * 0.04f).toDp() }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.surfacePage),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton44(IconName.ExpandMore, "Back to chat", actions.onVoiceClose)
            Spacer(Modifier.weight(1f))
            Chip(state.model ?: "Model", onClick = onModel, trailing = IconName.ExpandMore, borderless = true)
            Spacer(Modifier.weight(1f))
            IconButton44(IconName.Translate, "Voice language", onClick = { actions.onMenu(ChatMenu.VoiceLanguage) })
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(top = topPad, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VoiceIndicator(voice.phase, 180.dp, onTap = actions.onVoiceTap)
            VoiceStatusLine(
                voiceLine(voice.phase, state.status, VoiceMode.InApp, voice.transcribing),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp),
            )
            voice.error?.let {
                Text(
                    it,
                    style = TsukaimaType.caption,
                    color = colors.danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
                )
            }
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth()) {
                items.forEach { item -> key(item.id) { TranscriptItem(item, state, actions, media, revealed = voice.revealed) } }
                if (voice.heard.isNotBlank()) UserBubble(voice.heard)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
        ) {
            val raised = colors.surfaceRaised
            VoiceControl(
                icon = if (voice.muted) IconName.MicOff else IconName.Mic,
                description = if (voice.muted) "Unmute" else "Mute",
                size = 56.dp,
                fill = raised,
                tint = colors.ink1,
                border = colors.hairline,
                onClick = { actions.onVoiceMute(!voice.muted) },
            )
            // The handoff's cream-0 glyph is surfaceRaised: identical in light, still visible on dark's light ink1.
            VoiceControl(IconName.Close, "End voice", 64.dp, fill = colors.ink1, tint = raised, onClick = actions.onVoiceClose)
            VoiceControl(IconName.Keyboard, "Type instead", 56.dp, fill = raised, tint = colors.ink1, border = colors.hairline, onClick = onKeyboard)
        }
    }
}

/** A round control of the voice screen: [size] circle in [fill], optional 1dp [border], a 24dp [icon] in [tint]. */
@Composable
private fun VoiceControl(
    icon: IconName,
    description: String,
    size: Dp,
    fill: Color,
    tint: Color,
    onClick: () -> Unit,
    border: Color? = null,
) {
    val source = remember { MutableInteractionSource() }
    val shape = TsukaimaShapes.pill
    Row(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(fill, shape)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .pressWash(source, shape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        TsukaimaIcon(icon, contentDescription = description, size = 24.dp, tint = tint)
    }
}

// ---- previews -------------------------------------------------------------------------------------

private val previewChat = ChatState(
    items = listOf(
        ChatItem.UserMessage("u0", "Earlier text chat, not shown in voice mode."),
        ChatItem.UserMessage("u1", "What happened with the nightly backup?"),
        ChatItem.ToolCall("t1", "terminal", "journalctl -u backup --since today", null, ToolStatus.Done, "401 Unauthorized", 0, 0.6),
        ChatItem.AssistantMessage(
            "a1",
            "It failed at 3am with a 401: the storage token expired on the 16th. I can rotate it and restart the agent. Want me to?",
            streaming = false,
            reasoning = "Check the journal first.",
        ),
    ),
    status = AgentStatus.Idle,
    storedSessionId = "20261020_010916_48c967",
)

@Composable
private fun VoiceScreenPreview(dark: Boolean, voice: VoiceState, status: AgentStatus = AgentStatus.Idle) {
    TsukaimaTheme(darkTheme = dark) {
        VoiceScreen(
            state = ChatUiState(chat = previewChat.copy(status = status), model = "Hermes 4 · 405B", isNew = false),
            voice = voice,
            actions = ChatActions(),
            onModel = {},
        )
    }
}

private val previewSpeaking = VoiceState(phase = VoicePhase.Speaking, startIndex = 1, revealed = mapOf("a1" to 62))
private val previewListening = VoiceState(phase = VoicePhase.Listening, startIndex = 1, heard = "Yes, go ahead")

@Preview(name = "VoiceScreen speaking light", heightDp = 760)
@Composable
private fun VoiceScreenSpeakingLight() = VoiceScreenPreview(false, previewSpeaking, AgentStatus.Replying)

@Preview(name = "VoiceScreen speaking dark", heightDp = 760)
@Composable
private fun VoiceScreenSpeakingDark() = VoiceScreenPreview(true, previewSpeaking, AgentStatus.Replying)

@Preview(name = "VoiceScreen listening light", heightDp = 760)
@Composable
private fun VoiceScreenListeningLight() = VoiceScreenPreview(false, previewListening)

@Preview(name = "VoiceScreen listening dark", heightDp = 760)
@Composable
private fun VoiceScreenListeningDark() = VoiceScreenPreview(true, previewListening)

@Preview(name = "VoiceScreen muted error light", heightDp = 760)
@Composable
private fun VoiceScreenErrorLight() =
    VoiceScreenPreview(false, VoiceState(phase = VoicePhase.Idle, muted = true, startIndex = 4, error = "Speech recognition isn’t available on this device"))

@Preview(name = "VoiceScreen muted error dark", heightDp = 760)
@Composable
private fun VoiceScreenErrorDark() =
    VoiceScreenPreview(true, VoiceState(phase = VoicePhase.Idle, muted = true, startIndex = 4, error = "Speech recognition isn’t available on this device"))
