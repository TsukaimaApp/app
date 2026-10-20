package labs.suchattai.tsukaima.assist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import labs.suchattai.tsukaima.MainActivity
import labs.suchattai.tsukaima.TsukaimaApp
import labs.suchattai.tsukaima.data.prefs.Settings
import labs.suchattai.tsukaima.di.AppContainer
import labs.suchattai.tsukaima.domain.voice.VoiceMode
import labs.suchattai.tsukaima.notifications.ApprovalNotifier
import labs.suchattai.tsukaima.notifications.DeepLink
import labs.suchattai.tsukaima.ui.chat.ChatViewModel
import labs.suchattai.tsukaima.ui.chat.ContainerChatBackend
import labs.suchattai.tsukaima.ui.theme.TsukaimaTheme
import labs.suchattai.tsukaima.ui.theme.isDarkTheme
import labs.suchattai.tsukaima.ui.voice.AssistOverlay

/**
 * The device-assistant sheet ([AssistOverlay]) in a translucent window over the current app, started by
 * [TsukaimaVoiceSession] (or `ACTION_ASSIST`). A trusted launch ([AssistLaunch]: the session's) opens voice mode in
 * [VoiceMode.Assist], listening, on the chat the sheet used within the last half hour ([AssistSessionPolicy]), else a
 * new one; any other launch gets a new chat at rest until the indicator is tapped. The microphone is asked first. Back, a tap on
 * the scrim, a swipe down and leaving it (`noHistory`) close it; "Continue in Tsukaima", the Open and Keyboard icons
 * and any approval the turn needs open the app on the chat. Not shown over the lock screen.
 */
class AssistActivity : ComponentActivity() {
    private lateinit var container: AppContainer

    /** Decided once per launch (kept across recreation): see [AssistLaunch]. */
    private var trusted = false

    /** The chat's stored id once it has one, for [openApp]. */
    private var storedSessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_TRUSTED, trusted)
    }

    @Composable
    private fun AssistContent(settings: Settings) {
        LaunchedEffect(Unit) { if (!settings.onboarded) openApp() } // not signed in yet: onboarding is in the app
        if (!settings.onboarded) return
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
        // The voice controller outlives a configuration change, so it reaches this activity only through a flag.
        val bridge: AssistBridge = viewModel { AssistBridge() }
        val state by vm.state.collectAsStateWithLifecycle()

        val stored = state.chat.storedSessionId
        var persisted by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(stored) {
            storedSessionId = stored
            // Once per chat id and activity (not again on rotation); an untrusted launch's chat is not remembered.
            if (trusted && stored != null && stored != persisted) {
                persisted = stored
                val now = System.currentTimeMillis()
                backend.updateSettings { it.copy(assistSessionId = stored, assistSessionAt = now) }
            }
        }
        LaunchedEffect(bridge) { bridge.openApp.filter { it }.collect { openApp() } }

        // The view model waits for a resumed chat's history before voice starts; untrusted launches wait for a tap.
        val startVoice = { vm.openVoice(VoiceMode.Assist, onApprovalNeeded = { bridge.openApp.value = true }, listen = trusted) }
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
            if (started) return@LaunchedEffect
            started = true
            if (ContextCompat.checkSelfPermission(this@AssistActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startVoice()
            } else {
                mic.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        BackHandler { finish() }
        val voice = state.voice ?: return
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
