package app.tsukaima.di

import android.content.Context
import android.os.LocaleList
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import app.tsukaima.data.ConnectionManager
import app.tsukaima.share.SharedContent
import app.tsukaima.share.SharedFileStore
import java.io.File
import app.tsukaima.data.HermesRepository
import app.tsukaima.data.SessionBindings
import app.tsukaima.data.api.AuthException
import app.tsukaima.data.api.AuthInterceptor
import app.tsukaima.data.api.CleartextGuard
import app.tsukaima.data.api.HermesApi
import app.tsukaima.data.api.HermesJson
import app.tsukaima.data.files.FileSaver
import app.tsukaima.data.files.MediaStoreFileSaver
import app.tsukaima.data.files.viewFile
import app.tsukaima.data.files.viewUrl
import app.tsukaima.data.gateway.GatewayClient
import app.tsukaima.data.gateway.GatewaySession
import app.tsukaima.data.prefs.CredentialStore
import app.tsukaima.data.prefs.EncryptedCredentialStore
import app.tsukaima.data.prefs.LazyCredentialStore
import app.tsukaima.data.prefs.NotificationPrefs
import app.tsukaima.data.prefs.Settings
import app.tsukaima.data.prefs.SettingsStore
import app.tsukaima.data.prefs.VoiceProcessing
import app.tsukaima.data.gateway.ConnectionState
import app.tsukaima.notifications.AndroidNotificationPoster
import app.tsukaima.notifications.AnsweredRequestIds
import app.tsukaima.notifications.ApprovalAnswerer
import app.tsukaima.notifications.BackgroundController
import app.tsukaima.notifications.BackgroundPolicy
import app.tsukaima.notifications.GatewayService
import app.tsukaima.notifications.LiveVoiceService
import app.tsukaima.notifications.NotificationHub
import app.tsukaima.notifications.OpenScreen
import app.tsukaima.domain.voice.SpeechInput
import app.tsukaima.domain.voice.SpeechOutput
import app.tsukaima.domain.voice.VoiceLanguage
import app.tsukaima.domain.voice.VoiceLanguages
import app.tsukaima.ui.components.ToastState
import app.tsukaima.voice.AndroidSpeechInput
import app.tsukaima.voice.live.WebRtcLiveTransport
import app.tsukaima.domain.livevoice.LiveStartException
import app.tsukaima.domain.livevoice.LiveTransport
import app.tsukaima.voice.AndroidSpeechOutput
import app.tsukaima.voice.ServerSpeechInput
import app.tsukaima.voice.ServerSpeechOutput
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Manual dependency container: dumb wiring only, no logic worth testing. Constructing it does no I/O and
 * never touches the Keystore; the first credential access happens on a background thread (polling, the
 * interceptor, or sign-in).
 *
 * The auth cycle (the interceptor re-logs in through the API, the API's client carries the interceptor) is
 * broken with two clients: [plainClient] (no interceptor) serves login, and [httpClient] is derived from it
 * with the [AuthInterceptor] added. [loginApi] on the plain client does the re-login and sign-in.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Lives as long as the process. Background work (polling, gateway actor, forwarding) runs here. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json: Json = HermesJson

    val settingsStore = SettingsStore(appContext.settingsDataStore)

    /**
     * Synchronous snapshot for the OkHttp stack, which cannot suspend. Null until DataStore has loaded
     * (readers that need a value use defaults meanwhile; ConnectionManager waits for it).
     */
    val settings: StateFlow<Settings?> = settingsStore.settings.stateIn(appScope, SharingStarted.Eagerly, null)

    val credentials: CredentialStore = LazyCredentialStore(
        create = { EncryptedCredentialStore(appContext) },
        onFallback = { Log.e(TAG, "Encrypted credential store unavailable; credentials will not persist", it) },
    )

    private val settingsSnapshot: () -> Settings = { settings.value ?: Settings() }
    private val baseUrl: () -> String = { settingsSnapshot().serverUrl }

    /**
     * No auth interceptor: password login and the WebSocket upgrade (which authenticates with its ticket). Every
     * client derives from it, so [CleartextGuard] (plain http only to trusted hosts) covers them all.
     */
    private val cleartextGuard = CleartextGuard()
    private val plainClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(cleartextGuard)
        .addNetworkInterceptor(cleartextGuard)
        .callTimeout(20, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    /** Login client bound to an explicit origin; never to the mutable settings URL. */
    private fun loginApi(origin: String) = HermesApi(plainClient, { origin }, settingsSnapshot)

    /**
     * REST client: bearer token plus single-flight re-login on 401. The interceptor passes the origin the
     * credentials are bound to, and the re-login goes there (never to a URL that is mid-switch).
     */
    val httpClient: OkHttpClient = plainClient.newBuilder()
        .addInterceptor(
            AuthInterceptor(credentials, settingsSnapshot) { origin, username, password ->
                loginApi(origin).loginBlocking(username, password)
            },
        )
        .build()

    /**
     * Long-lived socket: no call or read timeout (the gateway has its own heartbeat deadline). Derived from
     * [plainClient], not [httpClient]: the upgrade must not carry the bearer or trigger a re-login on 401;
     * the ticket is fetched separately through [api].
     */
    private val webSocketClient: OkHttpClient = plainClient.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    val api: HermesApi = HermesApi(httpClient, baseUrl, settingsSnapshot, plainClient = plainClient)

    val gateway: GatewayClient = GatewayClient(
        client = webSocketClient,
        baseUrl = baseUrl,
        ticket = {
            try {
                api.wsTicket()
            } catch (e: AuthException) {
                connection.reportAuthFailure()
                throw e
            }
        },
        scope = appScope,
        clock = SystemClock::elapsedRealtime,
        logger = { m, t -> Log.w("Gateway", m, t) },
    )

    val gatewaySession = GatewaySession(gateway) { settings.value?.profile }

    val connection: ConnectionManager = ConnectionManager(
        api = api,
        gateway = gateway,
        settings = settings,
        creds = credentials,
        scope = appScope,
        logger = { m, t -> Log.w(TAG, m, t) },
    )

    val repository = HermesRepository(
        api = api,
        settings = settingsStore,
        creds = credentials,
        connection = connection,
        loginApi = ::loginApi,
    )

    val toastState = ToastState()

    /** Delivered (`MEDIA:`) files land in the public Downloads collection. */
    val fileSaver: FileSaver = MediaStoreFileSaver(appContext.contentResolver)

    /**
     * A fresh recogniser adapter for one voice session, per the voice processing setting now: the phone's (each run
     * owns its own platform recogniser and reads the voice language) or Hermes' transcription relay.
     */
    fun speechInput(): SpeechInput = when (settings.value?.voiceProcessing) {
        VoiceProcessing.Server -> ServerSpeechInput(appContext, transcribe = { audio, mime -> repository.transcribe(audio, mime) })
        else -> AndroidSpeechInput(appContext) { settings.value?.voiceLanguage }
    }

    /**
     * The speech output for one voice session, per the voice processing setting now: the phone's TTS engine (shared, see
     * [phoneSpeechOutput]) or a fresh player for Hermes' text-to-speech relay.
     */
    fun speechOutput(): SpeechOutput = when (settings.value?.voiceProcessing) {
        VoiceProcessing.Server -> ServerSpeechOutput(appContext, synthesize = { repository.speak(it) })
        else -> phoneSpeechOutput
    }

    /** One TTS engine per process, created on first use; speaks in the voice language when the engine has it. */
    private val phoneSpeechOutput: SpeechOutput by lazy {
        AndroidSpeechOutput(
            context = appContext,
            language = { settings.value?.voiceLanguage },
            onUnsupported = { tag ->
                toastState.show("Speech for ${VoiceLanguages.label(tag, voiceLanguages())} isn’t installed on this device")
            },
        )
    }

    /**
     * The voice language picker's options: the device locales (Unicode extensions such as `-u-fw-mon` stripped), the
     * TTS engine's languages (once it is ready) and [VoiceLanguages.FALLBACK], each named in the current locale.
     * With [includeEngine] it starts the TTS engine and may make binder calls, so call it off the main thread; without,
     * it is cheap (device locales and the fallback list only), enough to label the current setting.
     */
    fun voiceLanguages(includeEngine: Boolean = true): List<VoiceLanguage> {
        val device = LocaleList.getDefault().let { list -> (0 until list.size()).map { list[it].stripExtensions().toLanguageTag() } }
        val engine = if (includeEngine) phoneSpeechOutput.languages().toList() else emptyList()
        return VoiceLanguages.options(device, engine, ::languageName)
    }

    private fun languageName(tag: String): String {
        val here = Locale.getDefault()
        return Locale.forLanguageTag(tag).getDisplayName(here).ifBlank { tag }.replaceFirstChar { it.titlecase(here) }
    }

    /**
     * A fresh WebRTC transport for one GPT-Live session; its offer goes to Hermes (`POST …/voice-live/session`),
     * which answers with the vendor's SDP.
     */
    fun liveTransport(): LiveTransport = WebRtcLiveTransport(appContext) { sdp, history ->
        repository.voiceLiveSession(sdp, history).getOrThrow().transport?.sdp?.takeIf { it.isNotBlank() }
            ?: throw LiveStartException("no answer SDP")
    }

    /** The live voice notification's End action; the chat with the live session open closes it. */
    val liveVoiceEndRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Seconds of the live voice session running now (0 when none); the notification shows them. */
    val liveVoiceSeconds = MutableStateFlow(0)

    /** Starts [LiveVoiceService] for a live session; false when it was refused (the session must not start). */
    fun startLiveService(): Boolean = LiveVoiceService.start(appContext)

    /** Stops [LiveVoiceService]; a no-op when it is not running. */
    fun stopLiveService() = LiveVoiceService.stop(appContext)

    fun openFile(uri: String, mime: String): Boolean = viewFile(appContext, uri, mime)

    fun openUrl(url: String): Boolean = viewUrl(appContext, url)

    /**
     * Ids of server requests (approvals) already answered from any surface: chat screens and notifications.
     * [ConnectionManager.serverRequests] replays recent requests to late subscribers, so each consumer skips
     * ids in this set and adds the id when it answers.
     */
    val answeredRequestIds = AnsweredRequestIds()

    /** True while some activity is started (ProcessLifecycle STARTED); written by [background]. */
    val inForeground = MutableStateFlow(false)

    /** A notification asked to open a screen (`chat` / `settings`); the shell navigates and clears it. */
    val openScreen = MutableStateFlow<OpenScreen?>(null)

    /**
     * A share from another app ([app.tsukaima.share.ShareActivity]) waiting for the chat it targets; that chat takes
     * it with a compare-and-set to null ([app.tsukaima.ui.chat.ChatBackend.takeShare]).
     */
    val pendingShare = MutableStateFlow<SharedContent?>(null)

    /** Cached copies of shared streams (`cacheDir/shared`), pruned at app start and on every share. */
    val sharedFiles = SharedFileStore(File(appContext.cacheDir, "shared"))

    /** Live id of the chat on screen while it is resumed, else null (set by the chat screen). */
    val visibleLiveSessionId = MutableStateFlow<String?>(null)

    /** Live → stored session ids as chats bind them; notifications open the chat that asked. */
    val sessionBindings = SessionBindings()

    /** Posts and cancels notifications for the five background events; started by the application. */
    val notifications = NotificationHub(
        scope = appScope,
        serverRequests = connection.serverRequests,
        events = gateway.events,
        hermes = connection.hermes,
        answered = answeredRequestIds,
        cronJobs = { repository.cronJobs() },
        pairing = { repository.pairing() },
        poster = AndroidNotificationPoster(appContext),
        foreground = { inForeground.value },
        visibleSession = { visibleLiveSessionId.value },
        storedFor = sessionBindings::storedFor,
        closed = connection.closedRequestIds,
        logger = { m, t -> Log.w(TAG, m, t) },
        prefs = { settings.value?.notifications ?: NotificationPrefs() },
    )

    /** Answers approvals from notification buttons, by srq id only, at most once. */
    val approvalAnswerer = ApprovalAnswerer(
        transport = gateway,
        answered = answeredRequestIds,
        connected = { gateway.state.value == ConnectionState.Connected },
        cancel = notifications::cancel,
        repost = notifications::repostApproval,
    )

    /** Foreground/background hand-off of the live connection (driven by the process lifecycle). */
    val background = BackgroundController(
        scope = appScope,
        connection = connection,
        inForeground = inForeground,
        shouldHandOff = {
            BackgroundPolicy.shouldStartService(
                settings = settings.value,
                hasPassword = credentials.password() != null,
                authRequired = connection.authRequired.value,
                notificationsPermitted = NotificationManagerCompat.from(appContext).areNotificationsEnabled(),
                foreground = inForeground.value,
            )
        },
        startService = { GatewayService.start(appContext) },
        stopService = { GatewayService.stop(appContext) },
        onHandOff = notifications::onBackground,
        onQuiet = notifications::onConnectionEnded,
    )

    init {
        // Every notification kind switched off (or one back on) while in the background: stop or restart the hand-off.
        appScope.launch {
            settings.filterNotNull().map { it.notifications.anyOn }.distinctUntilChanged().drop(1)
                .collect { background.reevaluate() }
        }
    }

    /**
     * App-wide half of a sign-out, after [HermesRepository.signOut] cleared the credentials and dropped the socket:
     * no notification (or its dead buttons) survives and the foreground service goes.
     */
    fun signedOut() {
        notifications.signedOut()
        GatewayService.stop(appContext)
        visibleLiveSessionId.value = null
    }

    private companion object {
        const val TAG = "Tsukaima"
    }
}
