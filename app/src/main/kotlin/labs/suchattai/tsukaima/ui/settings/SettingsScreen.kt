package labs.suchattai.tsukaima.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.JsonObject
import labs.suchattai.tsukaima.data.api.PairingDto
import labs.suchattai.tsukaima.data.api.ProfileDto
import labs.suchattai.tsukaima.data.api.SkillDto
import labs.suchattai.tsukaima.data.api.ToolsetDto
import labs.suchattai.tsukaima.data.prefs.NotificationPrefs
import labs.suchattai.tsukaima.data.prefs.ThemeMode
import labs.suchattai.tsukaima.ui.chat.components.PairingCard
import labs.suchattai.tsukaima.ui.components.ButtonSize
import labs.suchattai.tsukaima.ui.components.Chip
import labs.suchattai.tsukaima.ui.components.ConfirmDialog
import labs.suchattai.tsukaima.ui.components.GhostButton
import labs.suchattai.tsukaima.ui.components.IconButton44
import labs.suchattai.tsukaima.ui.components.IconName
import labs.suchattai.tsukaima.ui.components.PrimaryButton
import labs.suchattai.tsukaima.ui.components.SecondaryButton
import labs.suchattai.tsukaima.ui.components.Tone
import labs.suchattai.tsukaima.ui.components.TsukaimaRow
import labs.suchattai.tsukaima.ui.components.TsukaimaSwitch
import labs.suchattai.tsukaima.ui.components.TsukaimaTextField
import labs.suchattai.tsukaima.ui.theme.TsukaimaTheme
import labs.suchattai.tsukaima.ui.theme.TsukaimaType
import labs.suchattai.tsukaima.ui.theme.tsukaima

/** Everything the stateless [SettingsContent] can ask for. */
class SettingsActions(
    val onBack: () -> Unit = {},
    val onLogs: () -> Unit = {},
    val onOpenDashboard: () -> Unit = {},
    val onServerUrl: (String) -> Unit = {},
    val onUsername: (String) -> Unit = {},
    val onPassword: (String) -> Unit = {},
    val onTest: () -> Unit = {},
    val onSignIn: () -> Unit = {},
    val onProfile: (String) -> Unit = {},
    val onModel: (provider: String, model: String) -> Unit = { _, _ -> },
    val onSkill: (name: String, enabled: Boolean) -> Unit = { _, _ -> },
    val onApprove: (PairingEntry) -> Unit = {},
    val onRevoke: (PairingEntry) -> Unit = {},
    val onAskRevoke: (PairingEntry?) -> Unit = {},
    val onToolset: (name: String, enabled: Boolean) -> Unit = { _, _ -> },
    val onNotifications: ((NotificationPrefs) -> NotificationPrefs) -> Unit = {},
    val onTheme: (ThemeMode) -> Unit = {},
    val onMaterialYou: (Boolean) -> Unit = {},
    val onSimulateOffline: (Boolean) -> Unit = {},
    val onAskSignOut: (Boolean) -> Unit = {},
    val onSignOut: () -> Unit = {},
)

@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onLogs: () -> Unit,
    onOpenDashboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    SettingsContent(
        state = state,
        actions = SettingsActions(
            onBack = onBack,
            onLogs = onLogs,
            onOpenDashboard = onOpenDashboard,
            onServerUrl = vm::setServerUrl,
            onUsername = vm::setUsername,
            onPassword = vm::setPassword,
            onTest = vm::testConnection,
            onSignIn = vm::onSignIn,
            onProfile = vm::setProfile,
            onModel = vm::setModel,
            onSkill = vm::toggleSkill,
            onApprove = vm::approvePairing,
            onRevoke = vm::revokePairing,
            onAskRevoke = vm::askRevoke,
            onToolset = vm::toggleToolset,
            onNotifications = vm::setNotifications,
            onTheme = vm::setTheme,
            onMaterialYou = vm::setMaterialYou,
            onSimulateOffline = vm::setSimulateOffline,
            onAskSignOut = vm::askSignOut,
            onSignOut = vm::signOut,
        ),
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(state: SettingsUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    SignOutDialog(state, actions)
    RevokeDialog(state, actions)
    Column(
        modifier
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
            IconButton44(IconName.ArrowBack, "Back", actions.onBack)
            Text("Settings", style = TsukaimaType.title, color = colors.ink1)
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Section("Hermes instance") {
                TsukaimaTextField(
                    value = state.serverUrl,
                    onValueChange = actions.onServerUrl,
                    label = "Server URL",
                    placeholder = "http://host:9119",
                    mono = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                if (state.urlChangedWithoutSignIn) {
                    Caption("Sign in again to use the new server.")
                }
                TsukaimaTextField(
                    value = state.username,
                    onValueChange = actions.onUsername,
                    label = "Username",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                TsukaimaTextField(
                    value = state.password,
                    onValueChange = actions.onPassword,
                    label = "Password",
                    placeholder = if (state.passwordSaved) "Saved" else null,
                    helper = "Your dashboard login.",
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                state.signInError?.let { Text(it, style = TsukaimaType.caption, color = colors.danger) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(
                        if (state.testing) "Testing…" else "Test connection",
                        onClick = actions.onTest,
                        enabled = !state.testing,
                    )
                    PrimaryButton(
                        if (state.signingIn) "Signing in…" else "Sign in",
                        onClick = actions.onSignIn,
                        enabled = !state.signingIn,
                    )
                }
                state.testResult?.let { Caption(it) }
                if (state.passwordSaved) {
                    TsukaimaRow(
                        title = if (state.signingOut) "Signing out…" else "Sign out",
                        subtitle = "Forget the password and disconnect from this Hermes",
                        leading = IconName.Logout,
                        danger = true,
                        onClick = { if (!state.signingOut) actions.onAskSignOut(true) },
                        modifier = Modifier.bleed(12.dp),
                    )
                }
            }

            Section("Profile", hint = "Scopes sessions, model and skills. Switching here changes everything else.") {
                val names = state.profiles.map { it.name }.ifEmpty { listOf(state.profile) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    names.forEach { Chip(it, onClick = { actions.onProfile(it) }, selected = state.profile == it) }
                }
            }

            Section("Model") {
                if (state.models.isEmpty()) {
                    Caption(state.model ?: "Models load once you are signed in.")
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.models.forEach { (provider, model) ->
                            Chip(model, onClick = { actions.onModel(provider, model) }, selected = state.model == model)
                        }
                    }
                }
            }

            Section("Skills", hint = "Toggle only. Installing new skills is a desktop task.") {
                Column {
                    state.skills.forEach { skill ->
                        val on = skill.enabled == true
                        TsukaimaRow(
                            title = skill.name,
                            trailing = { TsukaimaSwitch(on, { actions.onSkill(skill.name, it) }) },
                            onClick = { actions.onSkill(skill.name, !on) },
                            modifier = Modifier.bleed(12.dp),
                        )
                    }
                }
            }

            Section("Toolsets", hint = "Applies to new sessions. Provider keys and setup stay in the dashboard.") {
                Column {
                    state.toolsets.forEach { tool ->
                        val on = tool.enabled == true
                        TsukaimaRow(
                            title = tool.label ?: tool.name,
                            trailing = { TsukaimaSwitch(on, { actions.onToolset(tool.name, it) }) },
                            onClick = { actions.onToolset(tool.name, !on) },
                            modifier = Modifier.bleed(12.dp),
                        )
                    }
                }
            }

            Section("Pairing", hint = "Who may talk to the bot.") {
                state.pendingPairing.forEach { p ->
                    PairingCard(p.platform, p.who, p.userId, onApprove = { actions.onApprove(p) })
                }
                if (state.approvedPairing.isEmpty() && state.pendingPairing.isEmpty()) Caption("No paired accounts.")
                Column {
                    state.approvedPairing.forEach { p ->
                        TsukaimaRow(
                            title = p.who,
                            subtitle = p.platform,
                            leading = platformIcon(p.platform),
                            trailing = {
                                GhostButton("Revoke", { actions.onAskRevoke(p) }, size = ButtonSize.Sm, tone = Tone.Danger)
                            },
                            modifier = Modifier.bleed(12.dp),
                        )
                    }
                }
            }

            Section("Notifications", hint = "What Tsukaima may notify you about. Android’s own settings still apply.") {
                Column(Modifier.bleed(12.dp)) {
                    val n = state.notifications
                    NotifyRow("Approvals and questions", "When Hermes waits for you", n.approvals) { on ->
                        actions.onNotifications { it.copy(approvals = on) }
                    }
                    NotifyRow("Pairing requests", "Someone asks to talk to the bot", n.pairing) { on ->
                        actions.onNotifications { it.copy(pairing = on) }
                    }
                    NotifyRow("Finished replies", "A reply completes while the app is in the background", n.replies) { on ->
                        actions.onNotifications { it.copy(replies = on) }
                    }
                    NotifyRow("Gateway and cron alerts", "The gateway stops, or a cron job fails", n.alerts) { on ->
                        actions.onNotifications { it.copy(alerts = on) }
                    }
                }
            }

            Section("Diagnostics") {
                Column(Modifier.bleed(12.dp)) {
                    TsukaimaRow(
                        title = "Logs",
                        subtitle = "gateway.log · agent.log",
                        leading = IconName.ReceiptLong,
                        trailing = { Chevron() },
                        onClick = actions.onLogs,
                    )
                    TsukaimaRow(
                        title = "Usage this month",
                        subtitle = state.usage ?: "No usage data",
                        leading = IconName.BarChart,
                    )
                    TsukaimaRow(
                        title = "Open admin dashboard",
                        subtitle = "Config, env, MCP, webhooks — in the browser",
                        leading = IconName.OpenInNew,
                        onClick = actions.onOpenDashboard,
                    )
                    TsukaimaRow(
                        title = "Simulate Hermes unreachable",
                        subtitle = "Preview the offline state",
                        leading = IconName.CloudOff,
                        trailing = { TsukaimaSwitch(state.simulateOffline, actions.onSimulateOffline) },
                        onClick = { actions.onSimulateOffline(!state.simulateOffline) },
                    )
                }
            }

            Section("Theme") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ThemeMode.Warm to "Warm", ThemeMode.Dark to "Dark", ThemeMode.System to "System").forEach { (mode, label) ->
                        Chip(label, onClick = { actions.onTheme(mode) }, selected = state.theme == mode)
                    }
                }
                Caption("System is the default: warm in light, near-black in dark.")
                TsukaimaRow(
                    title = "Material You dynamic color",
                    subtitle = "Off keeps the fixed warm palette.",
                    trailing = { TsukaimaSwitch(state.materialYou, actions.onMaterialYou) },
                    onClick = { actions.onMaterialYou(!state.materialYou) },
                    modifier = Modifier.bleed(12.dp),
                )
            }
        }
    }
}

@Composable
private fun SignOutDialog(state: SettingsUiState, actions: SettingsActions) {
    if (!state.confirmSignOut) return
    ConfirmDialog(
        title = "Sign out?",
        body = "Tsukaima forgets your password, disconnects from Hermes and clears its notifications. " +
            "Your chats stay on the server.",
        confirmText = "Sign out",
        onConfirm = actions.onSignOut,
        onDismiss = { actions.onAskSignOut(false) },
        danger = true,
    )
}

@Composable
private fun NotifyRow(title: String, subtitle: String, on: Boolean, onChange: (Boolean) -> Unit) {
    TsukaimaRow(
        title = title,
        subtitle = subtitle,
        trailing = { TsukaimaSwitch(on, onChange) },
        onClick = { onChange(!on) },
    )
}

@Composable
private fun RevokeDialog(state: SettingsUiState, actions: SettingsActions) {
    val entry = state.confirmRevoke ?: return
    ConfirmDialog(
        title = "Revoke ${entry.who}?",
        body = "They can no longer talk to the bot on ${entry.platform} until they pair again.",
        confirmText = "Revoke",
        onConfirm = { actions.onRevoke(entry) },
        onDismiss = { actions.onAskRevoke(null) },
        danger = true,
    )
}

@Composable
private fun Section(title: String, hint: String? = null, content: @Composable () -> Unit) {
    val colors = MaterialTheme.tsukaima
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The jsx pulls the hint up by 6dp into the 10dp gap, leaving 4dp under the title.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = TsukaimaType.sectionTitle, color = colors.ink1)
            if (hint != null) Text(hint, style = TsukaimaType.caption, color = colors.ink3)
        }
        content()
    }
}

@Composable
private fun Caption(text: String) =
    Text(text, style = TsukaimaType.caption, color = MaterialTheme.tsukaima.ink3)

@Composable
private fun Chevron() =
    labs.suchattai.tsukaima.ui.components.TsukaimaIcon(IconName.ChevronRight, size = 22.dp, tint = MaterialTheme.tsukaima.ink3)

private fun platformIcon(platform: String): IconName = when (platform.lowercase()) {
    "telegram" -> IconName.Send
    "discord" -> IconName.Tag
    else -> IconName.Link
}

/** The jsx's `margin: 0 -12px`: the child is laid out [by] wider on each side so row padding lines up with the gutter. */
private fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + 2 * extra, maxWidth = constraints.maxWidth + 2 * extra),
    )
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

private fun previewState() = SettingsUiState(
    serverUrl = "http://100.69.91.116:9119",
    storedUrl = "http://100.69.91.116:9119",
    username = "mario",
    passwordSaved = true,
    testResult = "Hermes 0.9.2",
    profiles = listOf(ProfileDto("default"), ProfileDto("research"), ProfileDto("ops")),
    profile = "default",
    models = listOf("openrouter" to "hermes-4-405b", "openrouter" to "hermes-4-70b"),
    model = "hermes-4-405b",
    skills = listOf(SkillDto("Web search", enabled = true), SkillDto("Shell", enabled = true), SkillDto("Browser", enabled = false)),
    toolsets = listOf(ToolsetDto("filesystem", enabled = true), ToolsetDto("git", enabled = true), ToolsetDto("docker", enabled = false)),
    pairing = PairingDto(emptyList<JsonObject>(), emptyList()),
    pendingPairing = listOf(PairingEntry("Telegram", "@mario", "req-1", "4F2-91K")),
    approvedPairing = listOf(PairingEntry("Telegram", "@mario", "", "1"), PairingEntry("Discord", "mario#0420", "", "2")),
    usage = "1.2M tokens · 318 sessions · \$4.50",
)

@Preview(name = "Settings light", widthDp = 390, heightDp = 1900)
@Composable
private fun SettingsLightPreview() = TsukaimaTheme(darkTheme = false) { SettingsContent(previewState(), SettingsActions()) }

@Preview(name = "Settings dark", widthDp = 390, heightDp = 1900)
@Composable
private fun SettingsDarkPreview() = TsukaimaTheme(darkTheme = true) {
    SettingsContent(previewState().copy(serverUrl = "http://new.example:9119", signInError = "Invalid username or password (401)"), SettingsActions())
}
