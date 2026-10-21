package app.tsukaima.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import app.tsukaima.data.ConnectionManager
import app.tsukaima.data.HermesRepository
import app.tsukaima.data.api.CleartextGuard
import app.tsukaima.data.api.HermesApi
import app.tsukaima.data.gateway.GatewayClient
import app.tsukaima.data.prefs.InMemoryCredentialStore
import app.tsukaima.data.prefs.Settings
import app.tsukaima.data.prefs.SettingsStore
import app.tsukaima.data.prefs.ThemeMode
import app.tsukaima.data.prefs.VoiceProcessing
import app.tsukaima.domain.voice.VoiceLanguage
import app.tsukaima.diagnostics.CrashRecord
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var store: SettingsStore
    private lateinit var connection: ConnectionManager
    private lateinit var repo: HermesRepository
    private val creds = InMemoryCredentialStore()
    private val okHttp = OkHttpClient()
    private val snapshot = MutableStateFlow(Settings())

    /** "METHOD path" of every request the server saw. */
    private val requests = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** path -> response; unknown paths 404. */
    private val routes = mutableMapOf<String, () -> MockResponse>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    requests += "${request.method} $path"
                    return routes[path]?.invoke() ?: MockResponse.Builder().code(404).build()
                }
            }
            start()
        }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        store = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(tempDir.root, "s.preferences_pb") }),
        )
        val api = HermesApi(okHttp, { snapshot.value.serverUrl }, { snapshot.value })
        val gateway = GatewayClient(okHttp, { snapshot.value.serverUrl }, { "tk" }, scope)
        connection = ConnectionManager(api, gateway, snapshot, creds, scope)
        repo = HermesRepository(api, store, creds, connection, loginApi = { url -> HermesApi(okHttp, { url }) })
    }

    @After
    fun tearDown() {
        // Cancel AND join before resetMain: a cancelled coroutine still resumes through Dispatchers.Main when its
        // in-flight IO returns, and doing that after resetMain throws into the next runTest in the JVM.
        runBlocking { vms.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        Dispatchers.resetMain()
        scope.cancel()
        server.close()
    }

    private fun hostPort() = "${server.hostName}:${server.port}"

    private fun json(body: String) = MockResponse.Builder().code(200).body(body).build()

    private fun loginOk() = MockResponse.Builder()
        .code(200).addHeader("Set-Cookie", "hermes_session_at=tok1; Path=/").body("{}").build()

    private val vms = mutableListOf<SettingsViewModel>()

    /**
     * Bounds a hang, not a race. The store is a real DataStore on a temp file. A write started from the test
     * thread before the ViewModel's settings collector had its first emission hung forever on the GitHub
     * runner (never locally), so tests await that first emission (as a user would, the screen is rendered
     * from it) before writing through the ViewModel; see `setModelSavesTheProvider`.
     */
    private val STORE_TIMEOUT_MS = 30_000L

    private val toasts = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun vm() = SettingsViewModel(repo, store, creds, connection, toast = { toasts += it }).also { vms += it }

    private suspend fun SettingsViewModel.await(cond: (SettingsUiState) -> Boolean) =
        withTimeout(STORE_TIMEOUT_MS) { state.first(cond) }

    /**
     * Polls the store with a fresh read each time instead of one long-lived `data` collector: a collector
     * opened from the test thread right after a ViewModel write has been seen to never get the new value on
     * the GitHub runner. On timeout the message tells whether the write itself landed.
     */
    private suspend fun awaitStore(cond: (Settings) -> Boolean): Settings {
        val deadline = System.currentTimeMillis() + STORE_TIMEOUT_MS
        var last: Settings? = null
        while (System.currentTimeMillis() < deadline) {
            last = store.settings.first()
            if (cond(last)) return last
            kotlinx.coroutines.delay(25)
        }
        throw AssertionError("store never matched; last=$last")
    }

    private val crash = CrashRecord(1L, "main", "0.8.0 (18)", "Android 15 (SDK 35)", "Google Pixel 8", "java.lang.IllegalStateException: x\n")

    @Test
    fun crashBannerShowsAndDismissForgetsTheCrash() = runBlocking<Unit> {
        val cleared = java.util.concurrent.atomic.AtomicInteger()
        val vm = SettingsViewModel(
            repo, store, creds, connection, lastCrash = { crash }, clearCrash = { cleared.incrementAndGet() },
        ).also { vms += it }
        assertEquals(crash, vm.await { it.lastCrash != null }.lastCrash)
        vm.dismissCrash()
        assertNull(vm.state.value.lastCrash)
        withTimeout(STORE_TIMEOUT_MS) { while (cleared.get() == 0) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun reportProblemEmitsTheReportAndAFailureToasts() = runBlocking<Unit> {
        var fail = false
        val cleared = java.util.concurrent.atomic.AtomicInteger()
        val vm = SettingsViewModel(
            repo, store, creds, connection,
            toast = { toasts += it },
            buildReport = { if (fail) throw IllegalStateException("x") else "REPORT" },
            lastCrash = { crash },
            clearCrash = { cleared.incrementAndGet() },
        ).also { vms += it }
        vm.await { it.lastCrash != null }

        vm.reportProblem()
        assertEquals("REPORT", withTimeout(STORE_TIMEOUT_MS) { vm.reports.first() })
        vm.await { !it.reporting }
        assertEquals(crash, vm.state.value.lastCrash) // the plain row keeps the banner

        vm.reportProblem(fromCrash = true)
        assertEquals("REPORT", withTimeout(STORE_TIMEOUT_MS) { vm.reports.first() })
        vm.await { it.lastCrash == null && !it.reporting }

        fail = true
        vm.reportProblem()
        vm.await { !it.reporting }
        withTimeout(STORE_TIMEOUT_MS) { while ("Couldn’t build the report" !in toasts) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun setVoiceLanguagePersists() = runBlocking<Unit> {
        val options = listOf(VoiceLanguage(null, "System default"), VoiceLanguage("pl-PL", "Polish (Poland)"))
        val cheap = options.take(1)
        val loads = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val vm = SettingsViewModel(
            repo, store, creds, connection,
            voiceLanguages = { includeEngine -> loads += includeEngine; if (includeEngine) options else cheap },
        ).also { vms += it }
        assertEquals(cheap, vm.await { it.voiceLanguages.isNotEmpty() }.voiceLanguages) // labels the row at once
        assertEquals(listOf(false), loads.toList()) // opening Settings must not start the TTS engine
        vm.loadVoiceLanguages() // the Language sheet opens
        assertEquals(options, vm.await { it.voiceLanguages == options }.voiceLanguages)
        assertEquals(listOf(false, true), loads.toList())
        assertNull(vm.state.value.voiceLanguage)

        vm.setVoiceLanguage("pl-PL")
        assertEquals("pl-PL", awaitStore { it.voiceLanguage == "pl-PL" }.voiceLanguage)
        vm.await { it.voiceLanguage == "pl-PL" }

        vm.setVoiceLanguage(null)
        assertNull(awaitStore { it.voiceLanguage == null }.voiceLanguage)
        vm.await { it.voiceLanguage == null }
    }

    @Test
    fun setVoiceProcessingPersists() = runBlocking<Unit> {
        store.update { it.copy(voiceLanguage = "pl-PL") }
        val vm = vm()
        vm.await { it.voiceLanguage == "pl-PL" } // seeded from the store
        assertEquals(VoiceProcessing.Phone, vm.state.value.voiceProcessing)
        vm.setVoiceProcessing(VoiceProcessing.Server)
        assertEquals(VoiceProcessing.Server, awaitStore { it.voiceProcessing == VoiceProcessing.Server }.voiceProcessing)
        vm.await { it.voiceProcessing == VoiceProcessing.Server }
        vm.setVoiceProcessing(VoiceProcessing.Phone)
        awaitStore { it.voiceProcessing == VoiceProcessing.Phone }
        vm.await { it.voiceProcessing == VoiceProcessing.Phone }
        assertEquals("pl-PL", store.settings.first().voiceLanguage) // the language is kept for phone processing
    }

    @Test
    fun voiceProviderProbeNamesBothServerProvidersOnlyWhenBothAreKnown() = runBlocking<Unit> {
        snapshot.value = Settings(serverUrl = "http://${hostPort()}")
        var body = """{"ok":true,"stt":{"mode":"relay","provider":"openai","model":"faster-whisper-large-v3"},""" +
            """"tts":{"mode":"relay","provider":"piper"}}"""
        routes["/api/audio/voice-config"] = { json(body) }
        val vm = vm()
        vm.loadVoiceProviders()
        val probed = vm.await { it.ttsProvider != null }
        assertEquals("openai", probed.sttProvider)
        assertEquals("piper", probed.ttsProvider)
        assertEquals("On Hermes (openai · piper)", serverVoiceLabel(probed.sttProvider, probed.ttsProvider))

        body = """{"ok":true,"stt":{"mode":"relay","provider":"none"},"tts":{"mode":"relay","provider":"piper"}}"""
        vm.loadVoiceProviders()
        val noStt = vm.await { it.sttProvider == null }
        assertEquals("piper", noStt.ttsProvider)
        assertEquals("On Hermes", serverVoiceLabel(noStt.sttProvider, noStt.ttsProvider))

        body = """{"ok":true,"stt":{"mode":"relay","provider":"openai"},"tts":{"mode":"relay","reason":"no client wire"}}"""
        vm.loadVoiceProviders()
        val noTts = vm.await { it.ttsProvider == null && it.sttProvider != null }
        assertEquals("On Hermes", serverVoiceLabel(noTts.sttProvider, noTts.ttsProvider))
    }

    @Test
    fun signInNormalizesUrlAndPersists() = runBlocking<Unit> {
        routes["/auth/password-login"] = ::loginOk
        val vm = vm()
        vm.setServerUrl("${hostPort()}/")
        vm.setUsername("alice")
        vm.setPassword("pw")

        val result = vm.signIn()

        assertTrue(result.toString(), result.isSuccess)
        val expected = "http://${hostPort()}"
        val s = store.settings.first()
        assertEquals(expected, s.serverUrl)
        assertEquals("alice", s.username)
        assertTrue(s.onboarded)
        assertEquals("pw", creds.password())
        assertEquals(expected, creds.origin())
        val ui = vm.await { it.serverUrl == expected && !it.signingIn }
        assertEquals("", ui.password) // never kept after a successful sign-in
        assertTrue(ui.passwordSaved)
        assertNull(ui.signInError)
    }

    @Test
    fun signInFailureKeepsOldCredentials() = runBlocking<Unit> {
        routes["/auth/password-login"] = ::loginOk
        val vm = vm()
        vm.setServerUrl(hostPort()); vm.setUsername("alice"); vm.setPassword("pw")
        assertTrue(vm.signIn().isSuccess)

        routes["/auth/password-login"] = { MockResponse.Builder().code(401).build() }
        vm.setUsername("bob"); vm.setPassword("wrong")
        val result = vm.signIn()

        assertTrue(result.isFailure)
        assertEquals("pw", creds.password())
        assertEquals("tok1", creds.accessToken())
        assertEquals("alice", store.settings.first().username)
        assertEquals("http://${hostPort()}", store.settings.first().serverUrl)
    }

    @Test
    fun testConnectionReportsVersion() = runBlocking<Unit> {
        routes["/api/health"] = { json("""{"ok":true,"version":"0.9.2"}""") }
        val vm = vm()
        vm.setServerUrl(hostPort())
        vm.testConnection()
        assertEquals("Hermes 0.9.2", vm.await { it.testResult != null && !it.testing }.testResult)
    }

    @Test
    fun plainHttpToAPublicHostIsRefusedInlineOnTestAndSignIn() = runBlocking<Unit> {
        val vm = vm()
        vm.setServerUrl("example.com:9119"); vm.setUsername("a"); vm.setPassword("b")
        vm.testConnection()
        assertEquals(CleartextGuard.MESSAGE, vm.await { it.testResult != null && !it.testing }.testResult)
        assertTrue(vm.signIn().isFailure)
        assertEquals(CleartextGuard.MESSAGE, vm.state.value.signInError)
        assertTrue("nothing was sent", requests.isEmpty())
    }

    @Test
    fun setProfilePersists() = runBlocking<Unit> {
        store.update { it.copy(username = "alice") }
        val vm = vm()
        vm.await { it.username == "alice" } // the screen has its settings before the user can tap, see [seeded]
        vm.setProfile("research")
        assertEquals("research", awaitStore { it.profile == "research" }.profile)
        assertEquals("research", vm.await { it.profile == "research" }.profile)
    }

    @Test
    fun profileWriteSurvivesCancelledLoad() = runBlocking<Unit> {
        // Main does not run until advanced, so the reload that a sign-in starts cancels setProfile's load
        // before it ever ran; the profile write must not be part of that cancellable job.
        val main = kotlinx.coroutines.test.StandardTestDispatcher()
        Dispatchers.setMain(main)
        routes["/auth/password-login"] = ::loginOk
        val vm = SettingsViewModel(repo, store, creds, connection) // not in [vms]: cleaned up below, on [main]
        try {
            vm.setServerUrl(hostPort())
            vm.setUsername("alice")
            vm.setPassword("pw")
            vm.setProfile("research")
            assertTrue(vm.signIn().isSuccess)
            val saved = withTimeout(3_000) {
                while (store.settings.first().profile != "research") {
                    main.scheduler.advanceUntilIdle()
                    kotlinx.coroutines.delay(10)
                }
                store.settings.first()
            }
            assertEquals("research", saved.profile)
        } finally {
            vm.viewModelScope.coroutineContext[Job]?.cancel()
            main.scheduler.advanceUntilIdle()
        }
    }

    @Test
    fun editedUrlWithoutSignInShowsHint() = runBlocking<Unit> {
        store.update { it.copy(serverUrl = "http://old.example:9119", username = "alice", onboarded = true) }
        val vm = vm()
        vm.await { it.serverUrl == "http://old.example:9119" }
        assertFalse(vm.state.value.urlChangedWithoutSignIn)

        vm.setServerUrl("new.example:9119")
        assertTrue(vm.state.value.urlChangedWithoutSignIn)

        vm.setServerUrl("http://old.example:9119/") // normalizes back to the stored origin
        assertFalse(vm.state.value.urlChangedWithoutSignIn)
    }

    @Test
    fun authFailureMessageShownInline() = runBlocking<Unit> {
        routes["/auth/password-login"] = { MockResponse.Builder().code(401).build() }
        val vm = vm()
        vm.setServerUrl(hostPort()); vm.setUsername("alice"); vm.setPassword("nope")

        assertTrue(vm.signIn().isFailure)

        val err = vm.await { it.signInError != null }.signInError
        assertEquals("Invalid username or password (401)", err)
        assertEquals("nope", vm.state.value.password) // kept so the user can fix a typo
    }

    @Test
    fun unreachableServerShowsCantReach() = runBlocking<Unit> {
        val vm = vm()
        val url = hostPort()
        server.close()
        vm.setServerUrl(url); vm.setUsername("a"); vm.setPassword("b")
        assertTrue(vm.signIn().isFailure)
        assertEquals("Can't reach Hermes at http://$url", vm.state.value.signInError)
    }

    @Test
    fun simulateOfflineFeedsConnectionManager() = runBlocking<Unit> {
        val vm = vm()
        vm.setSimulateOffline(true)
        assertTrue(connection.simulateOffline.value)
        assertTrue(vm.await { it.simulateOffline }.simulateOffline)
    }

    @Test
    fun usageFallsBackToEstimatedWhenActualZero() {
        val totals = JsonObject(
            mapOf(
                "total_actual_cost" to JsonPrimitive(0),
                "total_estimated_cost" to JsonPrimitive(0.42),
                "total_input" to JsonPrimitive(500),
                "total_output" to JsonPrimitive(100),
                "total_sessions" to JsonPrimitive(3),
                "total_api_calls" to JsonPrimitive(9),
            ),
        )
        assertEquals("600 tokens · 3 sessions · \$0.42", usageSummary(totals))
    }

    private suspend fun signedInStore() {
        val url = "http://${hostPort()}"
        store.update { it.copy(serverUrl = url, onboarded = true) }
        snapshot.value = Settings(serverUrl = url)
        creds.save("pw", "tok", url)
    }

    @Test
    fun setProfileReloadsRemoteSections() = runBlocking<Unit> {
        var skills = """[{"name":"old","enabled":true}]"""
        routes["/api/skills"] = { json(skills) }
        signedInStore()
        val vm = vm()
        vm.await { it.skills.map { s -> s.name } == listOf("old") }

        skills = """[{"name":"research-skill","enabled":true}]"""
        vm.setProfile("research")

        val got = vm.await { it.skills.map { s -> s.name } == listOf("research-skill") }
        assertEquals("research", got.profile)
    }

    @Test
    fun themeChangeVisibleWhileRemoteLoadHangs() = runBlocking<Unit> {
        val gate = java.util.concurrent.CountDownLatch(1)
        routes["/api/profiles"] = { gate.await(10, java.util.concurrent.TimeUnit.SECONDS); json("{}") }
        signedInStore()
        val vm = vm()
        vm.await { it.passwordSaved }
        vm.setTheme(app.tsukaima.data.prefs.ThemeMode.Dark)
        try {
            vm.await { it.theme == app.tsukaima.data.prefs.ThemeMode.Dark }
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun usageSummaryFormatsTotals() {
        val totals = JsonObject(
            mapOf(
                "total_input" to JsonPrimitive(1_000_000),
                "total_output" to JsonPrimitive(234_000),
                "total_sessions" to JsonPrimitive(318),
                "total_estimated_cost" to JsonPrimitive(4.5),
            ),
        )
        assertEquals("1.2M tokens · 318 sessions · \$4.50", usageSummary(totals))
        assertNull(usageSummary(null))
    }

    @Test
    fun setModelSavesTheProvider() = runBlocking<Unit> {
        store.update { it.copy(model = "old", modelProvider = "stale-provider") }
        val vm = vm()
        vm.await { it.model == "old" }
        vm.setModel("openrouter", "hermes-4-70b")
        val s = awaitStore { it.model == "hermes-4-70b" }
        assertEquals("openrouter", s.modelProvider)
        assertEquals("hermes-4-70b", vm.await { it.model == "hermes-4-70b" }.model)
    }

    @Test
    fun signOutClearsCredentialsDisconnectsAndReturnsToFirstRun() = runBlocking<Unit> {
        signedInStore()
        store.update { it.copy(username = "alice", lastSessionId = "20261020_010916_48c967") }
        var sideEffects = 0
        val vm = SettingsViewModel(repo, store, creds, connection, onSignedOut = { sideEffects++ }).also { vms += it }
        vm.await { it.passwordSaved }
        connection.start()
        connection.reportAuthFailure()

        vm.askSignOut(true)
        assertTrue(vm.state.value.confirmSignOut)
        vm.signOut()
        withTimeout(STORE_TIMEOUT_MS) { vm.signedOut.first() }

        assertNull(creds.password())
        assertNull(creds.accessToken())
        val s = store.settings.first()
        assertFalse("next launch starts at Onboarding", s.onboarded)
        assertNull(s.lastSessionId)
        assertEquals("the sign-in form stays prefilled", "alice", s.username)
        assertEquals("http://${hostPort()}", s.serverUrl)
        assertFalse(connection.authRequired.value)
        withTimeout(STORE_TIMEOUT_MS) {
            connection.hermes.first { it.connection == app.tsukaima.data.gateway.ConnectionState.Disconnected }
        }
        assertEquals(1, sideEffects)
        val ui = vm.state.value
        assertFalse(ui.passwordSaved)
        assertFalse(ui.confirmSignOut)
        assertFalse(ui.signingOut)
        assertEquals("alice", ui.username)
        assertTrue(ui.skills.isEmpty())
        connection.stop()
    }

    @Test
    fun signInAfterSignOutConnectsWithoutARestart() = runBlocking<Unit> {
        // The poll loop survives a sign-out, so the next sign-in reconnects at once (no lifecycle restart).
        routes["/auth/password-login"] = ::loginOk
        routes["/api/status"] = { json("""{"gateway_state":"running"}""") }
        signedInStore()
        val vm = vm()
        vm.await { it.passwordSaved }
        connection.start()
        vm.signOut()
        withTimeout(STORE_TIMEOUT_MS) { vm.signedOut.first() }
        vm.setServerUrl(hostPort()); vm.setUsername("alice"); vm.setPassword("pw")
        assertTrue(vm.signIn().isSuccess)
        assertEquals("pw", creds.password())
        assertTrue(store.settings.first().onboarded)
        // A connect attempt follows (the mock has no WebSocket: it then retries, which is all this asserts).
        withTimeout(STORE_TIMEOUT_MS) {
            connection.hermes.first { it.connection != app.tsukaima.data.gateway.ConnectionState.Disconnected }
        }
        connection.stop()
    }

    // ---- v0.5.0 --------------------------------------------------------------------------------------------

    @Test
    fun toolsetToggleIsOptimisticAndPutsTheFlag() = runBlocking<Unit> {
        routes["/api/tools/toolsets"] = { json("""[{"name":"todo","label":"Todo","enabled":true}]""") }
        routes["/api/tools/toolsets/todo"] = { json("""{"ok":true,"name":"todo","platform":"cli","enabled":false}""") }
        signedInStore()
        val vm = vm()
        vm.await { it.toolsets.singleOrNull()?.enabled == true }

        vm.toggleToolset("todo", false)
        assertEquals(false, vm.state.value.toolsets.single().enabled)
        withTimeout(STORE_TIMEOUT_MS) { while ("PUT /api/tools/toolsets/todo" !in requests) kotlinx.coroutines.delay(10) }
        kotlinx.coroutines.delay(100)
        assertEquals(false, vm.state.value.toolsets.single().enabled)
        assertTrue(toasts.isEmpty())
    }

    @Test
    fun toolsetToggleRevertsWhenRefused() = runBlocking<Unit> {
        routes["/api/tools/toolsets"] = { json("""[{"name":"todo","enabled":true}]""") }
        routes["/api/tools/toolsets/todo"] = { MockResponse.Builder().code(400).body("""{"detail":"Unknown toolset: todo"}""").build() }
        signedInStore()
        val vm = vm()
        vm.await { it.toolsets.singleOrNull()?.enabled == true }

        vm.toggleToolset("todo", false)
        withTimeout(STORE_TIMEOUT_MS) { while (toasts.isEmpty()) kotlinx.coroutines.delay(10) }
        assertEquals(true, vm.state.value.toolsets.single().enabled)
        assertEquals(listOf("Couldn’t disable todo"), toasts.toList())
    }

    @Test
    fun notificationTogglesPersist() = runBlocking<Unit> {
        store.update { it.copy(theme = ThemeMode.Dark) }
        val vm = vm()
        vm.await { it.theme == ThemeMode.Dark } // the first settings emission
        vm.setNotifications { it.copy(replies = false, alerts = false) }
        assertFalse(vm.state.value.notifications.replies)
        val s = awaitStore { !it.notifications.replies }
        assertFalse(s.notifications.alerts)
        assertTrue(s.notifications.approvals)
        assertTrue(s.notifications.pairing)
    }

    @Test
    fun revokeAsksFirstThenRevokesAndReloads() = runBlocking<Unit> {
        var approved = """[{"platform":"telegram","user_id":"42","user_name":"alice"}]"""
        routes["/api/pairing"] = { json("""{"pending":[],"approved":$approved}""") }
        routes["/api/pairing/revoke"] = {
            approved = "[]"
            json("""{"ok":true}""")
        }
        signedInStore()
        val vm = vm()
        val entry = vm.await { it.approvedPairing.isNotEmpty() }.approvedPairing.single()

        vm.askRevoke(entry)
        assertEquals(entry, vm.state.value.confirmRevoke)
        assertFalse("nothing is revoked before the confirmation", "POST /api/pairing/revoke" in requests)
        vm.askRevoke(null)
        assertNull(vm.state.value.confirmRevoke)

        vm.askRevoke(entry)
        vm.revokePairing(entry)
        vm.revokePairing(entry) // a double tap on the dialog's button
        assertNull(vm.state.value.confirmRevoke)
        vm.await { it.approvedPairing.isEmpty() }
        assertEquals(1, requests.count { it == "POST /api/pairing/revoke" })
        assertTrue("Revoked alice" in toasts)
    }
}
