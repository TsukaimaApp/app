package labs.suchattai.tsukaima.ui.status

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import labs.suchattai.tsukaima.R
import labs.suchattai.tsukaima.data.HermesState
import labs.suchattai.tsukaima.data.api.DiskDto
import labs.suchattai.tsukaima.data.api.PairingDto
import labs.suchattai.tsukaima.data.api.SessionDto
import labs.suchattai.tsukaima.data.api.StatusDto
import labs.suchattai.tsukaima.data.api.SystemStatsDto
import labs.suchattai.tsukaima.data.gateway.ConnectionState
import labs.suchattai.tsukaima.data.gatewayStatus
import labs.suchattai.tsukaima.domain.chat.AgentStatus
import labs.suchattai.tsukaima.ui.chat.components.PairingCard
import labs.suchattai.tsukaima.ui.chat.components.StatusLine
import labs.suchattai.tsukaima.ui.components.ConfirmDialog
import labs.suchattai.tsukaima.ui.components.GhostButton
import labs.suchattai.tsukaima.ui.components.IconButton44
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.PrimaryButton
import labs.suchattai.tsukaima.ui.components.RaisedCard
import labs.suchattai.tsukaima.ui.components.SecondaryButton
import labs.suchattai.tsukaima.ui.components.SectionLabel
import labs.suchattai.tsukaima.ui.components.Tone
import labs.suchattai.tsukaima.ui.components.TsukaimaIcon
import labs.suchattai.tsukaima.ui.components.TsukaimaRow
import labs.suchattai.tsukaima.ui.settings.PairingEntry
import labs.suchattai.tsukaima.ui.theme.TsukaimaTheme
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/** Callbacks out of the Status screen. Session ids are the stored Hermes ids (what Chat's route takes). */
class StatusActions(
    val onMenu: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onNewChat: () -> Unit = {},
    val onCron: () -> Unit = {},
    val onContinue: (String) -> Unit = {},
    val onOpenCached: (String) -> Unit = {},
)

/** Home: gateway state, gateway controls, pending pairing, and shortcuts into Chat and Cron. */
@Composable
fun StatusScreen(
    vm: StatusViewModel,
    onMenu: () -> Unit,
    showMenu: Boolean,
    onSettings: () -> Unit,
    onNewChat: () -> Unit,
    onCron: () -> Unit,
    onContinue: (String) -> Unit,
    onOpenCached: (String) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.setVisible(true)
        onPauseOrDispose { vm.setVisible(false) }
    }
    // Re-evaluated every 30 s so "N MIN AGO" keeps counting while the screen is open (offline emits no new state).
    val label by produceState(vm.lastKnownLabel(), state) {
        while (true) {
            value = vm.lastKnownLabel()
            delay(30_000)
        }
    }
    StatusContent(
        state = state,
        lastKnownLabel = label,
        showMenu = showMenu,
        actions = StatusActions(onMenu, onSettings, onNewChat, onCron, onContinue, onOpenCached),
        onRetry = vm::refresh,
        onStart = { vm.startGateway() },
        onAskRestart = vm::askRestart,
        onAskStop = vm::askStop,
        onConfirm = vm::confirm,
        onDismissConfirm = vm::dismissConfirm,
        onApprove = vm::approvePairing,
    )
}

@Composable
internal fun StatusContent(
    state: StatusUiState,
    lastKnownLabel: String,
    showMenu: Boolean,
    actions: StatusActions,
    onRetry: () -> Unit = {},
    onStart: () -> Unit = {},
    onAskRestart: () -> Unit = {},
    onAskStop: () -> Unit = {},
    onConfirm: () -> Unit = {},
    onDismissConfirm: () -> Unit = {},
    onApprove: (PairingEntry) -> Unit = {},
) {
    val colors = MaterialTheme.tsukaima
    val agentStatus = when {
        state.busy && !state.offline -> AgentStatus.Thinking("Working on the gateway…")
        else -> state.hermes.gatewayStatus() ?: AgentStatus.Idle
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surfacePage),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (showMenu) IconButton44(IconName.Menu, "Conversations", actions.onMenu)
            Row(
                Modifier.padding(start = if (showMenu) 2.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.mark),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colors.accent),
                    modifier = Modifier.height(20.dp),
                )
                Text("Tsukaima", style = TsukaimaType.wordmark.copy(fontSize = 20.sp, lineHeight = 26.sp), color = colors.ink1)
            }
            Spacer(Modifier.weight(1f))
            IconButton44(IconName.Settings, "Settings", actions.onSettings)
        }
        StatusLine(agentStatus)
        if (state.offline) {
            OfflineBody(state, lastKnownLabel, actions, onRetry)
        } else {
            OnlineBody(state, actions, onStart, onAskRestart, onAskStop, onApprove)
        }
    }
    state.confirm?.let { which ->
        val stop = which == Confirm.Stop
        ConfirmDialog(
            title = if (stop) "Stop the gateway?" else "Restart the gateway?",
            body = if (stop) {
                "${platformNames(state)} will stop answering until you start it again."
            } else {
                "Platforms reconnect in a few seconds. Running sessions are kept."
            },
            confirmText = if (stop) "Stop" else "Restart",
            onConfirm = onConfirm,
            onDismiss = onDismissConfirm,
            danger = stop,
        )
    }
}

private fun platformNames(state: StatusUiState): String {
    val names = state.hermes.status?.gatewayPlatforms?.keys.orEmpty().map { it.replaceFirstChar(Char::titlecase) }
    return if (names.isEmpty()) "Messaging platforms" else names.joinToString(" and ")
}

@Composable
private fun OnlineBody(
    state: StatusUiState,
    actions: StatusActions,
    onStart: () -> Unit,
    onAskRestart: () -> Unit,
    onAskStop: () -> Unit,
    onApprove: (PairingEntry) -> Unit,
) {
    val session = state.lastSession
    val pending = state.pendingPairing
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PrimaryButton("New chat", actions.onNewChat, Modifier.weight(1f), icon = IconName.Add)
            SecondaryButton("Cron", actions.onCron, Modifier.weight(1f), icon = IconName.Schedule)
        }
        if (session != null) ContinueRow(session, "Continue last session") { actions.onContinue(session.id) }
        if (pending != null) {
            PairingCard(pending.platform, pending.who, pending.userId, onApprove = { onApprove(pending) })
        }
        SectionLabel("Status")
        state.facts().forEach { FactRow(it) }
        if (state.hermes.status != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.running) {
                    SecondaryButton("Restart gateway", onAskRestart, icon = IconName.RestartAlt, enabled = !state.busy)
                    GhostButton("Stop", onAskStop, tone = Tone.Danger, enabled = !state.busy)
                } else {
                    PrimaryButton("Start gateway", onStart, icon = IconName.PlayArrow, enabled = !state.busy)
                }
            }
        }
    }
}

@Composable
private fun OfflineBody(state: StatusUiState, lastKnownLabel: String, actions: StatusActions, onRetry: () -> Unit) {
    val colors = MaterialTheme.tsukaima
    val session = state.lastSession
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RaisedCard(Modifier.padding(horizontal = 12.dp)) {
            Column(Modifier.padding(4.dp)) { // RaisedCard pads 16; the design's card is 20
                Text("Can’t reach Hermes", style = TsukaimaType.titleSmall, color = colors.ink1)
                Text(
                    "Hermes didn’t answer. Check the tunnel or that the box is awake. Cached sessions still open read-only.",
                    style = TsukaimaType.caption.copy(fontSize = 13.5.sp),
                    color = colors.ink2,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(if (state.refreshing) "Retrying…" else "Retry", onRetry, icon = IconName.Refresh, enabled = !state.refreshing)
                    GhostButton("Settings", actions.onSettings)
                }
            }
        }
        if (session != null) ContinueRow(session, "Open cached copy") { actions.onOpenCached(session.id) }
        Column {
            SectionLabel(lastKnownLabel)
            Column(Modifier.alpha(0.55f)) { state.facts().take(3).forEach { FactRow(it) } }
        }
    }
}

@Composable
private fun ContinueRow(session: SessionDto, subtitle: String, onClick: () -> Unit) {
    TsukaimaRow(
        title = session.title?.takeIf { it.isNotBlank() } ?: session.preview?.takeIf { it.isNotBlank() } ?: "Untitled chat",
        subtitle = subtitle,
        leading = IconName.History,
        trailing = { TsukaimaIcon(IconName.ChevronRight, contentDescription = null, size = 22.dp, tint = MaterialTheme.tsukaima.ink3) },
        onClick = onClick,
    )
}

@Composable
private fun FactRow(fact: Fact) = TsukaimaRow(fact.title, subtitle = fact.subtitle, leading = fact.icon, danger = fact.danger)

// ---- previews ----------------------------------------------------------------------------

private val previewStatus = StatusDto(
    version = "0.22.2",
    gatewayRunning = true,
    gatewayState = "running",
    gatewayPlatforms = mapOf("telegram" to JsonPrimitive("connected"), "discord" to JsonPrimitive("connected")),
    activeSessions = 3,
    activeAgents = 1,
    disk = DiskDto(totalMb = 100_000.0, freeMb = 90_000.0, usedPercent = 10.0),
)
private val previewStats = SystemStatsDto(
    hostname = "hermes.local",
    memory = JsonObject(mapOf("total" to JsonPrimitive(8L shl 30), "available" to JsonPrimitive(7L shl 30))),
)
private val previewSession = SessionDto(id = "s1", title = "Plan the matcha order")
private val previewPending = PairingDto(
    pending = listOf(
        JsonObject(mapOf("platform" to JsonPrimitive("telegram"), "user_id" to JsonPrimitive("4F2-91K"), "user_name" to JsonPrimitive("@mario"), "request_id" to JsonPrimitive("r1"))),
    ),
)

private fun online(pairing: PairingDto? = previewPending, running: Boolean = true) = StatusUiState(
    hermes = HermesState(
        status = if (running) previewStatus else previewStatus.copy(gatewayRunning = false, gatewayState = "stopped", gatewayPlatforms = emptyMap()),
        lastSeenAt = 0L,
        connection = ConnectionState.Connected,
    ),
    stats = previewStats,
    pairing = pairing,
    lastSession = previewSession,
)

private val offline = online(pairing = null).let { it.copy(hermes = it.hermes.copy(reachable = false)) }

@Composable
private fun Preview(dark: Boolean, state: StatusUiState) = TsukaimaTheme(darkTheme = dark) {
    StatusContent(state, "LAST KNOWN STATUS · 14 MIN AGO", showMenu = true, actions = StatusActions())
}

@Preview(name = "Status online light", heightDp = 900)
@Composable
private fun OnlineLightPreview() = Preview(false, online())

@Preview(name = "Status online dark", heightDp = 900)
@Composable
private fun OnlineDarkPreview() = Preview(true, online())

@Preview(name = "Status stopped light", heightDp = 800)
@Composable
private fun StoppedLightPreview() = Preview(false, online(pairing = null, running = false))

@Preview(name = "Status offline light", heightDp = 800)
@Composable
private fun OfflineLightPreview() = Preview(false, offline)

@Preview(name = "Status offline dark", heightDp = 800)
@Composable
private fun OfflineDarkPreview() = Preview(true, offline)
