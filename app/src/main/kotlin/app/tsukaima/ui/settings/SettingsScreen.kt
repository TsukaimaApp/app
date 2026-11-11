package app.tsukaima.ui.settings

import app.tsukaima.ui.theme.TsukaimaShapes
import app.tsukaima.diagnostics.Diagnostics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.activity.compose.BackHandler
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.serialization.json.JsonObject
import app.tsukaima.BuildConfig
import app.tsukaima.data.api.PairingDto
import app.tsukaima.data.api.ProfileDto
import app.tsukaima.data.api.SkillDto
import app.tsukaima.data.api.ToolsetDto
import app.tsukaima.data.prefs.NotificationPrefs
import app.tsukaima.data.prefs.ThemeMode
import app.tsukaima.data.prefs.VoiceProcessing
import app.tsukaima.diagnostics.CrashRecord
import app.tsukaima.diagnostics.shareReport
import app.tsukaima.domain.voice.VoiceLanguages
import app.tsukaima.ui.chat.components.PairingCard
import app.tsukaima.ui.components.ButtonSize
import app.tsukaima.ui.components.Chip
import app.tsukaima.ui.components.ConfirmDialog
import app.tsukaima.ui.components.GhostButton
import app.tsukaima.ui.components.IconButton44
import app.tsukaima.ui.components.IconName
import app.tsukaima.ui.components.MenuItem
import app.tsukaima.ui.components.MenuSheet
import app.tsukaima.ui.components.PrimaryButton
import app.tsukaima.ui.components.SecondaryButton
import app.tsukaima.ui.components.Tone
import app.tsukaima.ui.components.TsukaimaRow
import app.tsukaima.ui.components.TsukaimaSwitch
import app.tsukaima.ui.components.TsukaimaTextField
import app.tsukaima.ui.theme.TsukaimaTheme
import app.tsukaima.ui.theme.TsukaimaType
import app.tsukaima.ui.theme.tsukaima
import app.tsukaima.ui.voice.voiceLanguageItems

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
    /** Voice language picked (null = system default). */
    val onVoiceLanguage: (String?) -> Unit = {},
    /** The Language sheet opens: (re)load its options. */
    val onVoiceLanguagesOpen: () -> Unit = {},
    /** Voice processing picked: on this phone or on Hermes. */
    val onVoiceProcessing: (VoiceProcessing) -> Unit = {},
    val onOfflineSend: (Int) -> Unit = {},
    /** The voice processing sheet opens: probe the server's STT and TTS providers for its label. */
    val onVoiceProcessingOpen: () -> Unit = {},
    /** The Live voice switch flipped. */
    val onLiveVoice: (Boolean) -> Unit = {},
    /** The Voice section is on screen: ask the server about GPT-Live for the switch's caption. */
    val onVoiceSectionShown: () -> Unit = {},
    /** "Use as device assistant": opens the system's assistant settings. */
    val onAssistant: () -> Unit = {},
    val onTheme: (ThemeMode) -> Unit = {},
    val onMaterialYou: (Boolean) -> Unit = {},
    val onSimulateOffline: (Boolean) -> Unit = {},
    /** "Report a problem": build the report and share it. */
    val onReportProblem: () -> Unit = {},
    /** The crash banner's "Report": share a report, then forget the crash. */
    val onReportCrash: () -> Unit = {},
    /** The crash banner's "Dismiss". */
    val onDismissCrash: () -> Unit = {},
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
    val context = LocalContext.current
    val roleManager = remember(context) { context.getSystemService(RoleManager::class.java) }
    // Re-read on every resume: the user picks the assistant in the system's settings and comes back.
    LifecycleResumeEffect(roleManager) {
        vm.setAssistantRoleHeld(roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true)
        onPauseOrDispose {}
    }
    // ROLE_ASSISTANT cannot be requested through the role dialog: the system's own picker is the way.
    val onAssistant: () -> Unit = { openAssistantSettings(context, vm::assistantSettingsUnavailable) }
    // A report is shared only by the visit that asked for it, while it is resumed (the view model outlives the screen).
    LifecycleResumeEffect(vm) {
        vm.setReportsResumed(true)
        onPauseOrDispose { vm.setReportsResumed(false) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.reports.collect { report -> if (vm.claimReport(report.id)) share(context, vm, report.text) }
        }
    }
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
            onVoiceLanguage = vm::setVoiceLanguage,
            onVoiceLanguagesOpen = vm::loadVoiceLanguages,
            onVoiceProcessing = vm::setVoiceProcessing,
            onOfflineSend = vm::setOfflineSendMinutes,
            onVoiceProcessingOpen = vm::loadVoiceProviders,
            onLiveVoice = vm::setLiveVoice,
            onVoiceSectionShown = vm::loadVoiceLiveStatus,
            onAssistant = onAssistant,
            onTheme = vm::setTheme,
            onMaterialYou = vm::setMaterialYou,
            onSimulateOffline = vm::setSimulateOffline,
            onReportProblem = { vm.reportProblem() },
            onReportCrash = { vm.reportProblem(fromCrash = true) },
            onDismissCrash = vm::dismissCrash,
            onAskSignOut = vm::askSignOut,
            onSignOut = vm::signOut,
        ),
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    /** The page open at first (a test, a deep link); null = the list. */
    initialPage: SettingsPage? = null,
) {
    val colors = MaterialTheme.tsukaima
    SignOutDialog(state, actions)
    RevokeDialog(state, actions)
    var showVoiceLanguages by rememberSaveable { mutableStateOf(false) }
    MenuSheet(
        visible = showVoiceLanguages,
        onDismiss = { showVoiceLanguages = false },
        items = voiceLanguageItems(state.voiceLanguages, state.voiceLanguage, actions.onVoiceLanguage),
    )
    var showOfflineSend by rememberSaveable { mutableStateOf(false) }
    MenuSheet(
        visible = showOfflineSend,
        onDismiss = { showOfflineSend = false },
        items = OFFLINE_SEND_CHOICES.map { minutes ->
            MenuItem(offlineSendLabel(minutes), null, checked = state.offlineSendMinutes == minutes) { actions.onOfflineSend(minutes) }
        },
    )
    var showVoiceProcessing by rememberSaveable { mutableStateOf(false) }
    MenuSheet(
        visible = showVoiceProcessing,
        onDismiss = { showVoiceProcessing = false },
        items = listOf(
            MenuItem(PHONE_PROCESSING, null, checked = state.voiceProcessing == VoiceProcessing.Phone) {
                actions.onVoiceProcessing(VoiceProcessing.Phone)
            },
            MenuItem(
                serverVoiceLabel(state.sttProvider, state.ttsProvider),
                null,
                checked = state.voiceProcessing == VoiceProcessing.Server,
            ) {
                actions.onVoiceProcessing(VoiceProcessing.Server)
            },
        ),
    )
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(colors.surfacePage),
    ) {
        // A wide screen (an unfolded phone, a tablet) can show the list beside the open page. That is the default
        // when the screen is wider than tall; upright it starts as one column. The top bar's button switches.
        val wide = maxWidth >= TWO_PANE_MIN
        val landscape = maxWidth > maxHeight
        var paneChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
        val twoPane = wide && (paneChoice ?: landscape)
        var pageName by rememberSaveable { mutableStateOf(initialPage?.name) }
        val page = pageName?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
        // Beside the list something is always open: the first page until one is picked.
        val shown = page ?: SettingsPage.Connection.takeIf { twoPane }
        BackHandler(enabled = page != null && !twoPane) { pageName = null }
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val inPage = page != null && !twoPane
                IconButton44(IconName.ArrowBack, if (inPage) "Back to Settings" else "Back", if (inPage) ({ pageName = null }) else actions.onBack)
                Text(if (inPage) page?.title.orEmpty() else "Settings", style = TsukaimaType.title, color = colors.ink1, modifier = Modifier.weight(1f))
                if (wide) {
                    IconButton44(IconName.VerticalSplit, if (twoPane) "Show one column" else "Show the list beside the page", onClick = { paneChoice = !twoPane })
                }
            }
            val showSheet: (SettingsSheet) -> Unit = { sheet ->
                when (sheet) {
                    SettingsSheet.VoiceLanguage -> showVoiceLanguages = true
                    SettingsSheet.VoiceProcessing -> showVoiceProcessing = true
                    SettingsSheet.OfflineSend -> showOfflineSend = true
                }
            }
            if (twoPane) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    SettingsHub(state, selected = shown, onOpen = { pageName = it.name }, modifier = Modifier.width(HUB_WIDTH).fillMaxHeight())
                    Box(Modifier.fillMaxHeight().width(1.dp).background(colors.hairline))
                    SettingsPageBody(shown ?: SettingsPage.Connection, state, actions, showSheet, heading = true, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                // One column never stretches across a wide screen.
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    val column = Modifier.widthIn(max = ONE_COLUMN_MAX).fillMaxHeight()
                    if (page == null) SettingsHub(state, selected = null, onOpen = { pageName = it.name }, modifier = column)
                    else SettingsPageBody(page, state, actions, showSheet, heading = false, modifier = column)
                }
            }
        }
    }
}

/** The narrowest screen that shows the list beside the open page. */
private val TWO_PANE_MIN = 720.dp
private val HUB_WIDTH = 340.dp
private val ONE_COLUMN_MAX = 640.dp

/** The pickers a settings page opens as a sheet. */
enum class SettingsSheet { VoiceLanguage, VoiceProcessing, OfflineSend }

/**
 * The pages of Settings, in the list's order: what configures Hermes first ([hermes]), then what configures this
 * phone. The list shows each as one row with [settingsSummary] under its [title].
 */
enum class SettingsPage(val title: String, val icon: IconName, val hermes: Boolean) {
    Connection("Connection", IconName.Dns, true),
    Model("Model", IconName.Memory, true),
    Skills("Skills", IconName.AutoAwesome, true),
    Toolsets("Toolsets", IconName.Terminal, true),
    Pairing("Pairing", IconName.Link, true),
    Notifications("Notifications", IconName.Notifications, false),
    Voice("Voice", IconName.GraphicEq, false),
    Offline("Offline", IconName.CloudOff, false),
    Appearance("Appearance", IconName.Palette, false),
    Diagnostics("Diagnostics and support", IconName.MonitorHeart, false),
}

/** What the list says under [page]'s title: the page's current state in one line, so most visits need no tap. */
internal fun settingsSummary(page: SettingsPage, s: SettingsUiState): String = when (page) {
    SettingsPage.Connection -> {
        val host = Diagnostics.serverHost(s.storedUrl)
        when {
            host == null || !s.passwordSaved -> "Not signed in"
            else -> listOfNotNull(host, s.username.takeIf { it.isNotBlank() }?.let { "signed in as $it" }, s.profile.takeIf { it.isNotBlank() && it != "default" }?.let { "profile $it" }).joinToString(" · ")
        }
    }
    SettingsPage.Model -> s.model ?: "Not chosen"
    SettingsPage.Skills -> countOn(s.skills.size, s.skills.count { it.enabled == true })
    SettingsPage.Toolsets -> countOn(s.toolsets.size, s.toolsets.count { it.enabled == true })
    SettingsPage.Pairing -> when {
        s.pendingPairing.isNotEmpty() -> "${s.pendingPairing.size} waiting for approval"
        s.approvedPairing.isNotEmpty() -> "${s.approvedPairing.size} paired"
        else -> "No paired accounts"
    }
    SettingsPage.Notifications -> {
        val on = listOf(s.notifications.approvals, s.notifications.pairing, s.notifications.replies, s.notifications.alerts).count { it }
        when (on) {
            0 -> "All off"
            4 -> "All on"
            else -> "$on of 4 on"
        }
    }
    SettingsPage.Voice -> (if (s.voiceProcessing == VoiceProcessing.Server) "On Hermes" else PHONE_PROCESSING) + " · Live voice " + if (s.liveVoice) "on" else "off"
    SettingsPage.Offline -> if (s.offlineSendMinutes == 0) "Offline messages stay drafts" else "Send within ${offlineSendLabel(s.offlineSendMinutes).lowercase()}"
    SettingsPage.Appearance -> when (s.theme) {
        ThemeMode.Warm -> "Warm"
        ThemeMode.Dark -> "Dark"
        ThemeMode.System -> "System"
    } + if (s.materialYou) " · Material You" else ""
    SettingsPage.Diagnostics -> if (s.lastCrash != null) "The app crashed last time" else "Logs, usage, problem report"
}

private fun countOn(total: Int, on: Int) = if (total == 0) "Loads once you are signed in" else "$on of $total on"

/** The list of pages: two groups, a row each with its summary; [selected] is the page open beside it. */
@Composable
private fun SettingsHub(state: SettingsUiState, selected: SettingsPage?, onOpen: (SettingsPage) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.tsukaima
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 24.dp),
    ) {
        listOf("Hermes" to true, "This phone" to false).forEach { (label, hermes) ->
            Text(
                label.uppercase(), style = TsukaimaType.label, color = colors.ink3,
                modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp).semantics { heading() },
            )
            SettingsPage.entries.filter { it.hermes == hermes }.forEach { page ->
                TsukaimaRow(
                    title = page.title,
                    subtitle = settingsSummary(page, state),
                    leading = page.icon,
                    trailing = { Chevron() },
                    onClick = { onOpen(page) },
                    modifier = Modifier
                        .background(if (page == selected) colors.accentSelected else Color.Transparent, TsukaimaShapes.row)
                        .semantics { this.selected = page == selected },
                )
            }
        }
    }
}

/** One page's settings. [heading] puts the page's title above them (beside the list, where the top bar says "Settings"). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsPageBody(
    page: SettingsPage,
    state: SettingsUiState,
    actions: SettingsActions,
    showSheet: (SettingsSheet) -> Unit,
    heading: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.tsukaima
    // Each page scrolls from its own top.
    val scroll = key(page) { rememberScrollState() }
    Column(
        modifier
            .imePadding()
            .verticalScroll(scroll)
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (heading) Text(page.title, style = TsukaimaType.titleSmall, color = colors.ink1, modifier = Modifier.semantics { heading() })
        when (page) {
            SettingsPage.Connection -> {
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
            }
            SettingsPage.Model -> {
            Section(null) {
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
            }
            SettingsPage.Skills -> SkillsPage(state.skills, actions.onSkill)
            SettingsPage.Toolsets -> {
            Section(null, hint = "Applies to new sessions. Provider keys and setup stay in the dashboard.") {
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
            }
            SettingsPage.Pairing -> {
            Section(null, hint = "Who may talk to the bot.") {
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
            }
            SettingsPage.Notifications -> {
            Section(null, hint = "What Tsukaima may notify you about. Android’s own settings still apply.") {
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
            }
            SettingsPage.Voice -> {
            Section(null) {
                val onHermes = state.voiceProcessing == VoiceProcessing.Server
                TsukaimaRow(
                    title = "Voice processing",
                    subtitle = if (onHermes) SERVER_PROCESSING_NOTE else PHONE_PROCESSING,
                    leading = IconName.GraphicEq,
                    trailing = { Chevron() },
                    onClick = {
                        actions.onVoiceProcessingOpen()
                        showSheet(SettingsSheet.VoiceProcessing)
                    },
                    modifier = Modifier.bleed(12.dp),
                )
                LaunchedEffect(Unit) { actions.onVoiceSectionShown() }
                TsukaimaRow(
                    title = "Live voice (GPT-Live)",
                    subtitle = state.liveVoiceCaption.ifEmpty { null },
                    leading = IconName.GraphicEq,
                    trailing = { TsukaimaSwitch(state.liveVoice, actions.onLiveVoice) },
                    onClick = { actions.onLiveVoice(!state.liveVoice) },
                    modifier = Modifier.bleed(12.dp),
                )
                val language = VoiceLanguages.label(state.voiceLanguage, state.voiceLanguages)
                TsukaimaRow(
                    title = "Language",
                    // Hermes' relays carry no language: its own STT and TTS settings decide there.
                    subtitle = if (onHermes) "$language · phone processing only" else language,
                    leading = IconName.Translate,
                    trailing = { Chevron() },
                    onClick = {
                        actions.onVoiceLanguagesOpen()
                        showSheet(SettingsSheet.VoiceLanguage)
                    },
                    modifier = Modifier.bleed(12.dp),
                )
                TsukaimaRow(
                    title = "Use as device assistant",
                    subtitle = if (state.assistantRoleHeld) "Tsukaima is your assistant" else "Long-press home or swipe from a corner to ask Hermes",
                    leading = IconName.Mic,
                    trailing = { Chevron() },
                    onClick = actions.onAssistant,
                    modifier = Modifier.bleed(12.dp),
                )
            }
            }
            SettingsPage.Offline -> {
            Section(null) {
                TsukaimaRow(
                    title = "Send messages written offline",
                    subtitle = if (state.offlineSendMinutes == 0) "Never: they stay in the composer as a draft"
                    else "When back online within ${offlineSendLabel(state.offlineSendMinutes).lowercase()}; after that they become a draft",
                    leading = IconName.CloudOff,
                    trailing = { Chevron() },
                    onClick = { showSheet(SettingsSheet.OfflineSend) },
                    modifier = Modifier.bleed(12.dp),
                )
            }
            }
            SettingsPage.Appearance -> {
            Section(null) {
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
            SettingsPage.Diagnostics -> {
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
                    if (BuildConfig.DEBUG) { // kit-only in the design: a debugging aid, not shipped in release builds
                        TsukaimaRow(
                            title = "Simulate Hermes unreachable",
                            subtitle = "Preview the offline state",
                            leading = IconName.CloudOff,
                            trailing = { TsukaimaSwitch(state.simulateOffline, actions.onSimulateOffline) },
                            onClick = { actions.onSimulateOffline(!state.simulateOffline) },
                        )
                    }
                }
            }

            Section("Support") {
                Column(Modifier.bleed(12.dp)) {
                    state.lastCrash?.let { CrashBanner(it, actions) }
                    ReportProblemRow(state.reporting, actions.onReportProblem)
                }
            }
            }
        }
    }
}

/** Which skills the Skills page lists: all, only those switched on, or only those switched off. */
enum class SkillFilter { All, On, Off }

/**
 * [skills] for the Skills page: those matching [query] (in the name, description or category, any case) and
 * [filter], by name, under their category (the server's; a skill without one goes under its first letter).
 */
internal fun skillGroups(skills: List<SkillDto>, query: String, filter: SkillFilter): List<Pair<String, List<SkillDto>>> {
    val q = query.trim()
    return skills
        .filter { skill ->
            val on = skill.enabled == true
            (filter == SkillFilter.All || on == (filter == SkillFilter.On)) &&
                (q.isEmpty() || listOfNotNull(skill.name, skill.description, skill.category).any { it.contains(q, ignoreCase = true) })
        }
        .sortedBy { it.name.lowercase() }
        .groupBy { it.category?.takeIf(String::isNotBlank) ?: it.name.take(1).uppercase() }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        .map { it.key to it.value }
}

/** Skills with a search field and All / On / Off, grouped: a hundred switches are found, not scrolled through. */
@Composable
private fun SkillsPage(skills: List<SkillDto>, onSkill: (String, Boolean) -> Unit) {
    val colors = MaterialTheme.tsukaima
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SkillFilter.All) }
    val on = skills.count { it.enabled == true }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Caption("Toggle only. Installing new skills is a desktop task.")
        if (skills.isEmpty()) {
            Caption("Skills load once you are signed in.")
            return@Column
        }
        TsukaimaTextField(query, { query = it }, placeholder = "Search ${skills.size} skills")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("All ${skills.size}", onClick = { filter = SkillFilter.All }, selected = filter == SkillFilter.All)
            Chip("On $on", onClick = { filter = SkillFilter.On }, selected = filter == SkillFilter.On)
            Chip("Off ${skills.size - on}", onClick = { filter = SkillFilter.Off }, selected = filter == SkillFilter.Off)
        }
        val groups = remember(skills, query, filter) { skillGroups(skills, query, filter) }
        if (groups.isEmpty()) Caption("No skill matches.")
        groups.forEach { (group, members) ->
            Column {
                Text(
                    group.uppercase(), style = TsukaimaType.label, color = colors.ink3,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp).semantics { heading() },
                )
                members.forEach { skill ->
                    val enabled = skill.enabled == true
                    TsukaimaRow(
                        title = skill.name,
                        trailing = { TsukaimaSwitch(enabled, { onSkill(skill.name, it) }) },
                        onClick = { onSkill(skill.name, !enabled) },
                        modifier = Modifier.bleed(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * The system's assistant picker: voice input settings (assistant and voice input), else the default-apps list;
 * [onUnavailable] when neither opens.
 */
private fun openAssistantSettings(context: Context, onUnavailable: () -> Unit) {
    for (action in listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) {
        try {
            context.startActivity(Intent(action))
            return
        } catch (_: ActivityNotFoundException) {
            // try the next one
        }
    }
    onUnavailable()
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

/** Opens the share sheet with [report]; a failure toasts through [vm]. */
private suspend fun share(context: Context, vm: SettingsViewModel, report: String) {
    try {
        shareReport(context, report)
    } catch (_: ActivityNotFoundException) {
        vm.reportShareFailed()
    } catch (_: IllegalArgumentException) { // FileProvider refused the path
        vm.reportShareFailed()
    } catch (_: java.io.IOException) {
        vm.reportShareFailed()
    }
}

/** "Report a problem": shares a text report with logs and the last crash. */
@Composable
private fun ReportProblemRow(reporting: Boolean, onClick: () -> Unit) {
    TsukaimaRow(
        title = "Report a problem",
        subtitle = if (reporting) "Collecting logs…" else "Share logs and the last crash",
        leading = IconName.Help,
        trailing = { Chevron() },
        onClick = onClick.takeUnless { reporting },
    )
}

/** Shown while a crash from an earlier run is recorded: share it ("Report") or forget it ("Dismiss"). */
@Composable
private fun CrashBanner(crash: CrashRecord, actions: SettingsActions) {
    Column {
        TsukaimaRow(
            title = "The app crashed last time",
            subtitle = crashSummary(crash),
            leading = IconName.Warning,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            GhostButton("Dismiss", actions.onDismissCrash, size = ButtonSize.Sm)
            SecondaryButton("Report", actions.onReportCrash, size = ButtonSize.Sm)
        }
    }
}

/** "IllegalStateException · version 0.8.0 (18)": the exception's class only, never its message. */
internal fun crashSummary(crash: CrashRecord): String {
    val exception = crash.stack.lineSequence().firstOrNull().orEmpty()
        .substringBefore(':').trim().substringAfterLast('.')
        .ifEmpty { "Unknown error" }
    return if (crash.appVersion.isEmpty()) exception else "$exception · version ${crash.appVersion}"
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
private fun Section(title: String?, hint: String? = null, content: @Composable () -> Unit) {
    val colors = MaterialTheme.tsukaima
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The jsx pulls the hint up by 6dp into the 10dp gap, leaving 4dp under the title.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // A page that is one section has its title in the bar above: only the hint is left.
            if (title != null) Text(title, style = TsukaimaType.sectionTitle, color = colors.ink1)
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
    app.tsukaima.ui.components.TsukaimaIcon(IconName.ChevronRight, contentDescription = null, size = 22.dp, tint = MaterialTheme.tsukaima.ink3)

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
    lastCrash = previewCrash,
)

@Preview(name = "Settings light", widthDp = 390, heightDp = 1900)
@Composable
private fun SettingsLightPreview() = TsukaimaTheme(darkTheme = false) { SettingsContent(previewState(), SettingsActions()) }

@Preview(name = "Settings dark", widthDp = 390, heightDp = 1900)
@Composable
private fun SettingsDarkPreview() = TsukaimaTheme(darkTheme = true) {
    SettingsContent(previewState().copy(serverUrl = "http://new.example:9119", signInError = "Invalid username or password (401)"), SettingsActions())
}

private val previewCrash = CrashRecord(
    at = 1_760_000_000_000L,
    thread = "main",
    appVersion = "0.8.0 (18)",
    android = "Android 15 (SDK 35)",
    device = "Google Pixel 8",
    stack = "java.lang.IllegalStateException: preview\n\tat app.tsukaima.MainActivity.onCreate(MainActivity.kt:1)\n",
)

@Composable
private fun SupportPreviewContent() = Column(
    Modifier
        .background(MaterialTheme.tsukaima.surfacePage)
        .padding(8.dp),
) {
    CrashBanner(previewCrash, SettingsActions())
    ReportProblemRow(reporting = false) {}
    ReportProblemRow(reporting = true) {}
}

@Preview(name = "Support light", widthDp = 390)
@Composable
private fun SupportLightPreview() = TsukaimaTheme(darkTheme = false) { SupportPreviewContent() }

@Preview(name = "Support dark", widthDp = 390)
@Composable
private fun SupportDarkPreview() = TsukaimaTheme(darkTheme = true) { SupportPreviewContent() }

/** The voice processing option for the phone's own recogniser and text-to-speech engine. */
private const val PHONE_PROCESSING = "On this phone"

/** The voice processing row's subtitle while Hermes runs voice: the voice language setting does not reach it. */
private const val SERVER_PROCESSING_NOTE = "Hermes listens and speaks · server voices follow its language settings"

/** The waits offered for a message written offline, in minutes; 0 = never sent by itself. */
internal val OFFLINE_SEND_CHOICES = listOf(0, 1, 5, 10, 30, 60)

internal fun offlineSendLabel(minutes: Int): String = when {
    minutes <= 0 -> "Never"
    minutes == 1 -> "1 minute"
    minutes < 60 -> "$minutes minutes"
    minutes == 60 -> "1 hour"
    else -> "${minutes / 60} hours"
}
