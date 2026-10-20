package labs.suchattai.tsukaima.di

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import labs.suchattai.tsukaima.data.ConnectionManager
import labs.suchattai.tsukaima.data.HermesRepository
import labs.suchattai.tsukaima.data.api.AuthException
import labs.suchattai.tsukaima.data.api.AuthInterceptor
import labs.suchattai.tsukaima.data.api.HermesApi
import labs.suchattai.tsukaima.data.api.HermesJson
import labs.suchattai.tsukaima.data.gateway.GatewayClient
import labs.suchattai.tsukaima.data.gateway.GatewaySession
import labs.suchattai.tsukaima.data.prefs.CredentialStore
import labs.suchattai.tsukaima.data.prefs.EncryptedCredentialStore
import labs.suchattai.tsukaima.data.prefs.LazyCredentialStore
import labs.suchattai.tsukaima.data.prefs.Settings
import labs.suchattai.tsukaima.data.prefs.SettingsStore
import labs.suchattai.tsukaima.ui.components.ToastState
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Manual dependency container: dumb wiring only, no logic worth testing. Constructing it does no I/O and
 * never touches the Keystore; the first credential access happens on a background thread (polling, the
 * interceptor, or sign-in).
 *
 * The auth cycle (the interceptor re-logs in through the API, the API's client carries the interceptor) is
 * broken with two clients: [plainClient] (no interceptor) serves login, and [httpClient] is derived from it
 * with the [AuthInterceptor] added. [loginApi] on the plain client does the re-login.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Lives as long as the process. Background work (polling, gateway actor, forwarding) runs here. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json: Json = HermesJson

    val settingsStore = SettingsStore(appContext.settingsDataStore)

    /** Synchronous snapshot for the OkHttp stack, which cannot suspend. Starts as defaults until DataStore loads. */
    val settings: StateFlow<Settings> = settingsStore.settings.stateIn(appScope, SharingStarted.Eagerly, Settings())

    val credentials: CredentialStore = LazyCredentialStore(
        create = { EncryptedCredentialStore(appContext) },
        onFallback = { Log.e(TAG, "Encrypted credential store unavailable; credentials will not persist", it) },
    )

    private val baseUrl: () -> String = { settings.value.serverUrl }
    private val settingsSnapshot: () -> Settings = { settings.value }

    /** No interceptor: password login and the WebSocket upgrade (which authenticates with its ticket). */
    private val plainClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    private val loginApi = HermesApi(plainClient, baseUrl, settingsSnapshot)

    /** REST client: bearer token plus single-flight re-login on 401. */
    val httpClient: OkHttpClient = plainClient.newBuilder()
        .addInterceptor(
            AuthInterceptor(credentials, settingsSnapshot) { _, username, password ->
                loginApi.loginBlocking(username, password)
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

    val api: HermesApi = HermesApi(httpClient, baseUrl, settingsSnapshot)

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

    val gatewaySession = GatewaySession(gateway)

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
        loginApi = { url -> HermesApi(plainClient, { url }, settingsSnapshot) },
    )

    val toastState = ToastState()

    private companion object {
        const val TAG = "Tsukaima"
    }
}
