package app.tsukaima.assist

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import app.tsukaima.MainActivity
import app.tsukaima.TsukaimaApp
import app.tsukaima.data.prefs.Settings
import app.tsukaima.di.AppContainer
import app.tsukaima.domain.voice.VoiceMode
import app.tsukaima.domain.voice.VoicePhase
import app.tsukaima.domain.voice.VoiceState
import app.tsukaima.notifications.ApprovalNotifier
import app.tsukaima.notifications.DeepLink
import app.tsukaima.ui.chat.ChatUiState
import app.tsukaima.ui.chat.ChatViewModel
import app.tsukaima.ui.chat.ContainerChatBackend
import app.tsukaima.ui.theme.TsukaimaTheme
import app.tsukaima.ui.theme.isDarkTheme
import app.tsukaima.ui.voice.AssistOverlay

/**
 * The device-assistant sheet ([AssistOverlay]) in a translucent window over the current app, started by
 * [TsukaimaVoiceSession]. `ACTION_ASSIST` is only an entry point (any app can send it), never a source of trust. A
 * trusted launch ([AssistLaunch]: it carries the session's nonce) opens voice mode in [VoiceMode.Assist], listening, on
 * the chat the sheet used within the last half hour ([AssistSessionPolicy]), else a new one. Any other launch shows the
 * sheet at rest and creates no chat until the indicator is tapped, then a new one that listens. The microphone is
 * asked before voice starts. A second assistant gesture while the sheet is open ([onNewIntent]) listens again. Back, a
 * tap on the scrim, a swipe down and leaving it (`noHistory`) close it; "Continue in Tsukaima", the Open and Keyboard
 * icons and any approval the turn needs open the app on the chat. Never shown over the lock screen: a launch while
 * the keyguard is locked (first launch or [onNewIntent]) finishes at once, no keyguard dismissal is asked for, and
 * the sheet finishes whenever it stops (a screen lock included), except across a configuration change.
 */
class AssistActivity : ComponentActivity() {
    private lateinit var container: AppContainer

    /** Decided per launch (kept across recreation), see [AssistLaunch]; a later trusted gesture ([onNewIntent]) sets it. */
    private var trusted by mutableStateOf(false)

    /** The chat's stored id once it has one, for [openApp]. */
    private var storedSessionId: String? = null

    /** The sheet's chat once created, for [onNewIntent]. */
    private var chat: ChatViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (keyguardLocked()) {
            finish() // nothing on the lock screen
            return
        }
        container = (application as TsukaimaApp).container
        trusted = savedInstanceState?.getBoolean(STATE_TRUSTED)
            ?: (AssistLaunch.trust(AssistNonce.consume(intent?.getStringExtra(AssistNonce.EXTRA))) == AssistLaunch.Trusted)
        setContent {
            val settings by container.settings.collectAsStateWithLifecycle()
            TsukaimaTheme(darkTheme = isDarkTheme(settings?.theme), dynamicColor = settings?.materialYou == true) {
                // Nothing until settings load (a few ms): the session to resume depends on them.
                settings?.let { AssistContent(it) }
            }
        }
    }

    /** The assistant gesture again while the sheet is open (`singleTask`): a trusted one listens again. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (keyguardLocked()) {
            finish() // nothing on the lock screen, whatever the launch carries
            return
        }
        if (!AssistNonce.consume(intent.getStringExtra(AssistNonce.EXTRA))) return
        trusted = true // a sheet still at rest creates its chat now, trusted
        val vm = chat ?: return
        if (vm.state.value.voice?.phase == VoicePhase.Idle) vm.tapVoiceIndicator()
    }

    /** The sheet never outlives leaving it, a screen lock included (`noHistory` does too; this makes it explicit). */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    private fun keyguardLocked() = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_TRUSTED, trusted)
    }

    @Composable
    private fun AssistContent(settings: Settings) {
        LaunchedEffect(Unit) { if (!settings.onboarded) openApp() } // not signed in yet: onboarding is in the app
        if (!settings.onboarded) return
        BackHandler { finish() }
        // An untrusted launch creates no Hermes session until the user taps the indicator.
        var tapped by rememberSaveable { mutableStateOf(false) }
        if (!trusted && !tapped) {
            AssistOverlay(
                state = ChatUiState(),
                voice = VoiceState(phase = VoicePhase.Idle),
                onKeyboard = ::openApp,
                onOpenApp = ::openApp,
                onTap = { tapped = true },
                onDismiss = ::finish,
                canOpenApp = false,
            )
            return
        }
        AssistChat(settings, listen = trusted || tapped)
    }

    @Composable
    private fun AssistChat(settings: Settings, listen: Boolean) {
        val backend = remember { ContainerChatBackend(container) }
        // One chat per activity; the resumed id is read once, when the view model is created. An untrusted launch
        // never resumes the assist chat.
        val vm: ChatViewModel = viewModel(
            key = "assist-chat",
            factory = viewModelFactory {
                initializer {
                    val resume = if (trusted) AssistSessionPolicy.sessionToResume(settings, System.currentTimeMillis()) else null
                    ChatViewModel(backend, resume)
                }
            },
        )
        SideEffect { chat = vm }
        // The voice controller outlives a configuration change, so it reaches this activity only through a flag.
        val bridge: AssistBridge = viewModel { AssistBridge() }
        val state by vm.state.collectAsStateWithLifecycle()

        val stored = state.chat.storedSessionId
        var persisted by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(stored, trusted) {
            storedSessionId = stored
            // Once per chat id and activity (not again on rotation); an untrusted launch's chat is not remembered.
            if (trusted && stored != null && stored != persisted) {
                persisted = stored
                val now = System.currentTimeMillis()
                backend.updateSettings { it.copy(assistSessionId = stored, assistSessionAt = now) }
            }
        }
        LaunchedEffect(bridge) { bridge.openApp.filter { it }.collect { openApp() } }

        // The view model waits for a resumed chat's history before voice starts.
        val startVoice = { vm.openVoice(VoiceMode.Assist, onApprovalNeeded = { bridge.openApp.value = true }, listen = listen) }
        var started by rememberSaveable { mutableStateOf(false) }
        val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startVoice()
            } else {
                // The app's own toast host is not in this window and would vanish with it.
                Toast.makeText(this@AssistActivity, "Microphone access is needed for voice mode", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(this@AssistActivity, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (started) {
                // Restored after process death: the new view model has no voice yet. (A pending permission request
                // answers through `mic`.)
                if (vm.state.value.voice == null && granted) startVoice()
                return@LaunchedEffect
            }
            started = true
            if (granted) startVoice() else mic.launch(Manifest.permission.RECORD_AUDIO)
        }

        val voice = state.voice ?: return
        // Keep the screen on while the sheet listens, thinks or speaks; a resting sheet lets it sleep.
        val awake = voice.phase != VoicePhase.Idle
        DisposableEffect(awake) {
            if (awake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        AssistOverlay(
            state = state,
            voice = voice,
            onKeyboard = ::openApp,
            onOpenApp = ::openApp,
            onTap = vm::tapVoiceIndicator,
            onDismiss = ::finish,
            // Without a stored id the app would open some other chat.
            canOpenApp = stored != null,
        )
    }

    /** Opens Tsukaima on this chat (the app's composer takes over) and closes the sheet. */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(ApprovalNotifier.EXTRA_OPEN_SCREEN, ApprovalNotifier.SCREEN_CHAT)
        storedSessionId?.let { intent.putExtra(DeepLink.EXTRA_SESSION_ID, it) }
        startActivity(intent)
        finish()
    }

    private companion object {
        const val STATE_TRUSTED = "assist_trusted"
    }
}

/** Survives configuration changes with the chat: the voice controller's "open the app" request, for the live activity. */
private class AssistBridge : ViewModel() {
    val openApp = MutableStateFlow(false)
}
