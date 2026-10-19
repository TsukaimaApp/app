# Tsukaima Android Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Kotlin + Jetpack Compose Android app that signs in to one self-hosted Hermes Agent dashboard, chats over its JSON-RPC WebSocket, shows agent status, approves tool calls, manages cron jobs and settings, pixel-matching the design handoff.

**Architecture:** Single `app` module, manual DI (`AppContainer`), MVVM with `StateFlow` ViewModels. Data layer = `HermesApi` (REST, OkHttp + kotlinx.serialization) and `GatewayClient` (OkHttp WebSocket, JSON-RPC 2.0). A pure `ChatReducer` folds gateway events into UI state so the chat logic is unit-testable without Android. UI = design-system composables under `ui/components` and `ui/chat`, screens under `ui/<screen>`, one `AppShell` that switches between phone (overlay drawer) and ≥720dp (persistent pane).

**Tech Stack:** Kotlin 2.2, AGP 8.13, Gradle 8.14.3 (installed at `~/gradle/current`), JDK 21, Compose BOM 2025.10, Material 3 + `material3-adaptive`, Navigation Compose, kotlinx.serialization, OkHttp 5 (+ `mockwebserver3` for tests), DataStore Preferences, `security-crypto` (EncryptedSharedPreferences), coroutines + Turbine for tests, JUnit 4. No Hilt, no Retrofit, no markdown library.

**Spec:** `design/HANDOFF.md` (screens, tokens, copy, motion) + `design/readme.md` (brand, decisions) + `docs/protocol/README.md` (verified Hermes HTTP/WS protocol) + `docs/protocol/gateway-contract.openrpc.json`. Reference React components live in `design/components/**` (`*.jsx` = look, `*.prompt.md` = rules) — port them, do not reinvent.

## Global Constraints

- `applicationId` / package `labs.suchattai.tsukaima`; `minSdk 29`, `targetSdk 36`, `compileSdk 36`. SDK at `$HOME/android-sdk` (set `sdk.dir` in `local.properties`, never commit it).
- Build and test with `~/gradle/current/bin/gradle` (not a downloaded wrapper distribution — the wrapper is generated from it in Task 1). Every task ends with `./gradlew :app:testDebugUnitTest` green and `./gradlew :app:assembleDebug` green. There is no emulator on this machine: UI is verified by compiling, previews, and unit tests on ViewModels/reducers.
- 1 px in the design = 1 dp; font sizes are sp; line-heights are unitless multipliers (body 15.5sp × 1.55 = 24sp). Off-grid values (15.5sp, 12.5sp, 14dp input radius, 28dp composer, 296dp drawer, 7dp dot) are intentional — never snap them.
- Colours, type roles, spacing, radii, shadows exactly as `design/tokens/*.css` (copied into `ui/theme`). No pure white, no gradients, no emoji, sentence case, no exclamation marks. Serif (Source Serif 4) only for wordmark, screen titles and status lines; never body text.
- Accent `#7C6FCF` appears only on: send button, selected states, presence dot, Approve button. Red `#B3564E` only for "Gateway stopped" / "Can't reach Hermes" / destructive actions. Delete is red ink, never filled.
- Motion: quick 150 ms, base 200 ms, screen fade 220 ms, easing `cubic-bezier(.25,.1,.25,1)`; dot pulse 2 s opacity 1→.35; skeleton pulse 1.6 s; caret blink 1 s; long-press 480 ms; toast 1.6 s. No bounce, no scale-on-press. Press = 8 % ink wash, hover 4 %, focus = 2dp accent ring.
- All hit targets ≥ 44dp. Page gutter 20dp. No tab bar; drawer is the only navigation; Status is home.
- Auth = username + password (user decision 2026-10-20). Password and tokens live only in `EncryptedSharedPreferences`; never log them. Server URL is stored without trailing slash; `http://` allowed (tailnet).
- Test against the local Hermes only: `http://100.69.91.116:9119`, creds in `.local/hermes-test-creds.env` (gitignored). Never point tests at `http://hermes:9119`.
- Commit after every task with a conventional message; attribution trailer per the session's system reminder.

## Review Focus

1. Server URL typed with a trailing slash, a path, or no scheme (`hermes:9119`, `http://hermes:9119/`) — login must still work and `/api/...` must not become `//api/...`. Test in Task 4 (`SettingsStoreTest.normalizesServerUrl`).
2. Access token expires mid-session (12 h): a 401 on any REST call must trigger one silent re-login with the stored password and a retry, not a logout loop. Test in Task 5 (`AuthInterceptorTest.reloginsOnceOn401`).
3. WebSocket drops while a reply streams: the chat must show the partial text, flip status to "Can't reach Hermes", reconnect with backoff and resume the session without duplicating messages. Tests in Task 7 (`GatewayClientTest.reconnectsAfterClose`) and Task 9 (`ChatReducerTest.closeDuringStreamKeepsPartialText`).
4. Approval request arrives while the app is backgrounded, then the user answers from the notification: exactly one `srq-…` reply is sent, the card disappears, and a second tap does nothing. Test in Task 9 (`ChatReducerTest.approvalAnsweredOnce`) and Task 20.
5. A model reply containing fenced code with a language tag and unterminated fence, or nested backticks — the markdown renderer must never crash or swallow text. Test in Task 10 (`MarkdownTest.unterminatedFenceRendersAsCode`).

---

### Task 1: Gradle project skeleton

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/labs/suchattai/tsukaima/MainActivity.kt`, `app/src/main/kotlin/labs/suchattai/tsukaima/TsukaimaApp.kt`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`, `app/src/test/kotlin/labs/suchattai/tsukaima/SmokeTest.kt`, `.github/workflows/android.yml`
- Modify: `.gitignore` (add `local.properties`, `.gradle/`, `build/`, `*.apk`, `.kotlin/`, `.idea/`)

**Interfaces:**
- Produces: `TsukaimaApp : Application` exposing `val container: AppContainer` (class created in Task 3; stub it here as an empty class in `di/AppContainer.kt`).
- Produces: version catalog aliases used by every later task: `libs.okhttp`, `libs.okhttp.mockwebserver3`, `libs.kotlinx.serialization.json`, `libs.kotlinx.coroutines.android`, `libs.kotlinx.coroutines.test`, `libs.turbine`, `libs.androidx.datastore.preferences`, `libs.androidx.security.crypto`, `libs.androidx.navigation.compose`, `libs.androidx.lifecycle.viewmodel.compose`, `libs.androidx.material3`, `libs.androidx.material3.adaptive`, `libs.androidx.material.icons.extended`, `libs.junit`.

- [ ] **Step 1: Write the failing test** — `SmokeTest.kt`:

```kotlin
class SmokeTest { @Test fun jsonRoundTrip() { assertEquals("""{"a":1}""", Json.encodeToString(mapOf("a" to 1))) } }
```

- [ ] **Step 2: Create the Gradle files.** Versions in `libs.versions.toml`: agp `8.13.0`, kotlin `2.2.20`, composeBom `2025.10.00`, okhttp `5.1.0`, kotlinxSerialization `1.9.0`, coroutines `1.10.2`, lifecycle `2.9.4`, navigation `2.9.5`, datastore `1.1.7`, securityCrypto `1.1.0`, material3Adaptive `1.1.0`, materialIconsExtended `1.7.8`, activityCompose `1.11.0`, coreKtx `1.17.0`, junit `4.13.2`, turbine `1.2.1`. Before writing, confirm each exists with `curl -s https://dl.google.com/dl/android/maven2/<group path>/<artifact>/maven-metadata.xml` or `https://repo1.maven.org/maven2/...`; bump to the newest stable if a pin is missing. Plugins: `com.android.application`, `org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.compose`, `org.jetbrains.kotlin.plugin.serialization`. `gradle.properties`: `org.gradle.jvmargs=-Xmx3g`, `android.useAndroidX=true`, `kotlin.code.style=official`, `org.gradle.configuration-cache=true`. App: `jvmTarget 17`, `buildFeatures.compose = true`, `testOptions.unitTests.isReturnDefaultValues = true`.
- [ ] **Step 3: Generate the wrapper** — `~/gradle/current/bin/gradle wrapper --gradle-version 8.14.3 --distribution-type bin`; write `local.properties` with `sdk.dir=/home/claude/android-sdk`.
- [ ] **Step 4: Manifest + MainActivity.** `MainActivity : ComponentActivity` calling `setContent { Text("Tsukaima") }`; manifest declares `INTERNET`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `android:usesCleartextTraffic="true"` (tailnet http), `android:name=".TsukaimaApp"`, `windowSoftInputMode="adjustResize"`.
- [ ] **Step 5: Run** `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL, `app/build/outputs/apk/debug/app-debug.apk` exists.
- [ ] **Step 6: CI** — `.github/workflows/android.yml`: on push/PR, `actions/checkout`, `actions/setup-java` (temurin 21), `android-actions/setup-android`, `gradle/actions/setup-gradle`, run `./gradlew testDebugUnitTest assembleDebug`, upload `app-debug.apk` as artifact `tsukaima-debug`.
- [ ] **Step 7: Commit** `feat: gradle skeleton, CI`.

---

### Task 2: Theme tokens (colour, type, shape, motion)

**Files:**
- Create: `ui/theme/Color.kt`, `ui/theme/Type.kt`, `ui/theme/Shape.kt`, `ui/theme/Motion.kt`, `ui/theme/Theme.kt`, `ui/theme/Dimens.kt`
- Test: `app/src/test/kotlin/labs/suchattai/tsukaima/ui/theme/TokensTest.kt`
- Fonts already staged in `app/src/main/res/font/` (`source_serif_4.ttf`, `source_serif_4_italic.ttf`, `instrument_sans.ttf`, `ibm_plex_mono.ttf`, `noto_serif_jp_subset.ttf`).

**Interfaces:**
- Produces: `data class TsukaimaColors(surfacePage, surfaceRaised, surfaceSunken, hairline, ink1, ink2, ink3, accent, accentPressed, onAccent, accentSelected, accentWash, ok, danger, warn, codeBg, codeInk, scrim, pressWash, hoverWash: Color)`, `val LightColors`, `val DarkColors` with the hex values from HANDOFF "Design tokens".
- Produces: `object TsukaimaType` with `TextStyle` vals: `wordmark` (serif 600 28sp), `wordmarkLarge` (38sp), `title` (serif 600 22sp), `titleSmall` (serif 600 19sp), `sectionTitle` (serif 600 17sp), `statusLine` (serif italic 500 14sp), `body` (sans 400 15.5sp/1.55), `ui` (sans 500 14sp/1.4), `label` (sans 600 11sp, uppercase, letterSpacing 0.08em), `caption` (sans 400 12.5sp/1.4), `mono` (IBM Plex Mono 13sp/1.6), `kanji` (Noto Serif JP 14sp, letterSpacing 0.2em).
- Produces: `object TsukaimaShapes { row=12dp, input=14dp, card=16dp, composer=28dp, code=10dp, pill=999dp, sheetTop=20dp }`, `object Motion { quick=150, base=200, screen=220, easing=CubicBezierEasing(.25f,.1f,.25f,1f), dotPulseMs=2000, skeletonMs=1600, caretMs=1000, longPressMs=480, toastMs=1600 }`, `object Dimens { gutter=20.dp, hit=44.dp, drawerWidth=296.dp, paneWidth=300.dp, contentMax=760.dp, wideBreakpoint=720.dp, shadow=... }`.
- Produces: `@Composable fun TsukaimaTheme(darkTheme: Boolean, dynamicColor: Boolean = false, content)` providing `LocalTsukaimaColors` and a MaterialTheme whose `colorScheme` maps page→background, raised→surface, accent→primary, onAccent→onPrimary, ink1→onSurface, hairline→outlineVariant, danger→error; and `val MaterialTheme.tsukaima: TsukaimaColors` accessor.

- [ ] **Step 1: Write the failing tests** — `TokensTest`: `lightAccentIs7C6FCF` (`LightColors.accent == Color(0xFF7C6FCF)`), `darkPageIs1B1917`, `bodyIs15_5sp` (`TsukaimaType.body.fontSize == 15.5.sp && lineHeight == (15.5*1.55).sp`), `labelIsUppercaseLetterSpaced` (`letterSpacing == 0.08.em`).
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*TokensTest*'` → FAIL (unresolved).
- [ ] **Step 3: Implement** the files. Variable fonts: `Font(R.font.source_serif_4, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600)))` etc.; `FontFamily` per face. Dark values from HANDOFF (page `#1B1917`, raised `#242120`, sunken `#141210`, hairline `#38332E`, ink `#EDE8DF/#B5ACA0/#978E84`, accent `#A89CFF`, pressed `#BCB2FF`, onAccent `#1B1917`, selected `#4A4370`, wash `#322E48`, code `#141218/#CFC9E2`); scrim light `rgba(39,34,32,.28)`, dark same alpha on `#000`; pressWash light `rgba(39,34,32,.08)`, dark `rgba(237,232,223,.1)`; shadow light `0 2px 12px rgba(60,50,40,.10)`, dark `0 2px 14px rgba(0,0,0,.45)`. `dynamicColor=true` replaces accent/onAccent/selected/wash from `dynamicLightColorScheme`/`dynamicDarkColorScheme` primary tones only.
- [ ] **Step 4: Run tests** → PASS; `assembleDebug` green.
- [ ] **Step 5: Commit** `feat(theme): design tokens, fonts, motion`.

---

### Task 3: Core design-system components

**Files:**
- Create under `ui/components/`: `Buttons.kt` (`PrimaryButton`, `SecondaryButton`, `GhostButton`, `IconButton44`), `Chip.kt`, `Row.kt` (`TsukaimaRow`, `SectionLabel`), `TextField.kt` (`TsukaimaTextField`), `Switch.kt` (`TsukaimaSwitch`), `Card.kt` (`RaisedCard`), `Dialog.kt` (`ConfirmDialog`), `BottomSheetMenu.kt` (`MenuSheet`, `MenuItem`), `Skeleton.kt`, `Toast.kt` (`ToastHost`, `rememberToastState`), `Press.kt` (`pressWash()` modifier, `longPressMenu()` modifier), `Icons.kt` (`TsukaimaIcon(name: IconName, size, tint)` mapping the HANDOFF icon list to `Icons.Rounded.*`).
- Reference: `design/components/core/*.jsx` + `*.prompt.md` — port sizes, paddings, states 1:1.
- Create `di/AppContainer.kt` placeholder is already there; untouched.

**Interfaces:**
- Produces: `PrimaryButton(text, onClick, modifier, icon: IconName? = null, enabled = true, size: ButtonSize = ButtonSize.Md)` — accent fill, onAccent text, pill, 44dp tall (sm = 36dp), `ui` type; `SecondaryButton` — raised bg + hairline, ink text; `GhostButton(…, tone: Tone = Tone.Ink)` — no border, `Tone.Danger` = `#B3564E` text; `IconButton44(icon, contentDescription, onClick, filled: Boolean = false)`.
- Produces: `Chip(label, onClick, selected=false, leading: IconName?=null, trailing: IconName?=null, borderless=false)` — pill, 32dp tall, selected = `accentSelected` fill + ink text.
- Produces: `TsukaimaRow(title, subtitle: String?=null, leading: IconName?=null, trailing: @Composable ()->Unit = {}, onClick: (()->Unit)?=null, onLongClick: (()->Unit)?=null, danger=false)` — 52dp min, 12dp radius, icon 22dp ink2, title `ui`, subtitle `caption` ink3; `SectionLabel(text)` — `label` style, ink3, 20dp horizontal padding.
- Produces: `TsukaimaTextField(value, onValueChange, label: String?, placeholder: String?, helper: String?=null, mono=false, singleLine=true, minLines=1, keyboardOptions, visualTransformation)` — 14dp radius, hairline, raised bg, 2dp accent ring on focus.
- Produces: `TsukaimaSwitch(checked, onCheckedChange)` — 44×26 track, 18dp thumb, accent when on.
- Produces: `RaisedCard(modifier, elevated=false, content)` — raised bg, hairline, 16dp radius, shadow when `elevated`.
- Produces: `ConfirmDialog(title, body: String?, confirmText, onConfirm, onDismiss, danger=false, content: @Composable ()->Unit = {})` — 340dp max, 22dp pad, `titleSmall` serif; danger → confirm button `GhostButton(tone=Danger)`.
- Produces: `MenuSheet(visible, onDismiss, items: List<MenuItem>)` where `MenuItem(label, icon: IconName?, danger=false, checked=false, onClick)` — `ModalBottomSheet`, 20dp top radius, 32×4 handle.
- Produces: `SkeletonBlock(modifier, radius: Dp)` pulsing 1.6 s between sunken and raised; `ToastState.show(text)` → `ink1` bg, cream text, 12×16 pad, 12dp radius, auto-hide 1.6 s.
- Produces: `Modifier.pressWash(interactionSource)` and `Modifier.longPressMenu(onLongPress)` (480 ms via `pointerInput` + `detectTapGestures(onLongPress)`).

- [ ] **Step 1: Write the failing test** — `ui/components/IconsTest.kt`: `everyHandoffIconResolves` iterating `IconName.entries` and asserting `iconFor(name)` is non-null (the HANDOFF list: menu, more_vert, expand_more, add, arrow_upward, arrow_back, close, check, content_copy, terminal, psychology, error, gpp_maybe, link, settings, monitor_heart, schedule, pause_circle, play_circle, play_arrow, restart_alt, refresh, history, chevron_right, dns, stop_circle, forum, chat, memory, folder, edit, archive, unarchive, delete, radio_button_unchecked, receipt_long, bar_chart, open_in_new, cloud_off, send, tag). Where `Icons.Rounded` lacks an exact name (e.g. `gpp_maybe` → `Icons.Rounded.GppMaybe`, `monitor_heart` → `MonitorHeart`, `psychology` → `Psychology`) pick the closest extended icon and record the substitution in a KDoc on the enum.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** all component files; add a `@Preview` composable per file (light + dark) so the design can be eyeballed in Android Studio later.
- [ ] **Step 4: Run** tests + `assembleDebug` → green.
- [ ] **Step 5: Commit** `feat(ui): core design-system components`.

---

### Task 4: Settings and credential stores

**Files:**
- Create: `data/prefs/SettingsStore.kt`, `data/prefs/CredentialStore.kt`, `data/prefs/ServerUrl.kt`
- Test: `data/prefs/ServerUrlTest.kt`, `data/prefs/SettingsStoreTest.kt`

**Interfaces:**
- Produces: `object ServerUrl { fun normalize(input: String): String? }` — trims, prepends `http://` when no scheme, strips trailing slashes and any path/query, lowercases host, keeps port; returns null when the host is empty.
- Produces: `enum class ThemeMode { Warm, Dark, System }`; `data class Settings(serverUrl: String = "", username: String = "", profile: String = "default", model: String? = null, workspace: String? = null, theme: ThemeMode = ThemeMode.System, materialYou: Boolean = false, lastSessionId: String? = null, onboarded: Boolean = false)`.
- Produces: `class SettingsStore(dataStore: DataStore<Preferences>) { val settings: Flow<Settings>; suspend fun update(block: (Settings) -> Settings) }` (DataStore Preferences, file `settings`).
- Produces: `interface CredentialStore { fun password(): String?; fun accessToken(): String?; fun save(password: String?, accessToken: String?); fun clear() }` with `class EncryptedCredentialStore(context: Context) : CredentialStore` (EncryptedSharedPreferences, file `tsukaima_creds`, `MasterKey` AES256_GCM) and `class InMemoryCredentialStore : CredentialStore` for tests.

- [ ] **Step 1: Write failing tests** — `ServerUrlTest`: `addsHttpScheme` (`"hermes:9119"` → `"http://hermes:9119"`), `stripsTrailingSlashAndPath` (`"http://hermes:9119/"` and `"http://hermes:9119/login?x=1"` → `"http://hermes:9119"`), `keepsHttpsAndPort` (`"HTTPS://Hermes.Example.com:8443"` → `"https://hermes.example.com:8443"`), `rejectsEmptyHost` (`"http://"` → null). `SettingsStoreTest` (use `PreferenceDataStoreFactory.create` on a temp file with `TestScope`): `normalizesServerUrl` — `update { it.copy(serverUrl = ServerUrl.normalize("hermes:9119/")!!) }` then `settings.first().serverUrl == "http://hermes:9119"`; `defaultsAreSystemThemeAndDefaultProfile`.
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement** (`ServerUrl.normalize` via `okhttp3.HttpUrl.parse` after scheme fix-up; take `scheme://host[:port]` only, omitting default ports).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(data): settings + encrypted credential stores`.

---

### Task 5: REST client with automatic re-login

**Files:**
- Create: `data/api/HermesApi.kt`, `data/api/ApiModels.kt`, `data/api/AuthInterceptor.kt`, `data/api/ApiException.kt`, `data/api/JsonConfig.kt` (`val HermesJson = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }`)
- Test: `data/api/HermesApiTest.kt`, `data/api/AuthInterceptorTest.kt` (mockwebserver3)

**Interfaces:**
- Produces DTOs (all `@Serializable`, fields nullable unless listed required in `docs/protocol/README.md`): `HealthDto(ok, version, authRequired)`, `StatusDto(version, gatewayRunning, gatewayState, gatewayPlatforms: Map<String, JsonElement>, activeSessions, activeAgents, profiles, disk: DiskDto?, memory: JsonObject?, overall)`, `SystemStatsDto(hostname, os, hermesVersion, cpuCount, memory: JsonObject?)`, `SessionDto(id, title, preview, source, startedAt: Double?, endedAt: Double?, messageCount, model, archived: Boolean = false, profile: String?, profileName: String?)`, `ModelOptionsDto(providers: List<ProviderDto>, model, provider)`, `ProviderDto(slug, name, models: List<String>, isCurrent: Boolean?, authenticated: Boolean?)`, `WorkspacesDto(projects: List<JsonObject>, repos: List<JsonObject>, defaultCwd, home)`, `CronJobDto(id, name, prompt, schedule: CronScheduleDto?, scheduleDisplay, enabled, state, nextRunAt, lastRunAt, lastStatus, deliver: JsonElement?)`, `CronScheduleDto(kind, expr, display)`, `DeliveryTargetDto(id, name)`, `PairingDto(pending: List<JsonObject>, approved: List<JsonObject>)`, `SkillDto(name, description, category, enabled)`, `ToolsetDto(name, label, description, enabled, available)`, `ProfilesDto(profiles: List<ProfileDto>)`, `ProfileDto(name, isDefault, model, provider, gatewayRunning)`, `LogsDto(file, lines)`, `UsageDto(daily: List<JsonObject>, byModel: List<JsonObject>, totals: JsonObject?)`, `AuthMeDto(userId, provider, expiresAt: Long)`. Use `@SerialName` for snake_case.
- Produces: `class ApiException(val code: Int, message: String) : IOException`; `class AuthException : IOException` (401 after re-login failed, or no credentials).
- Produces: `class AuthInterceptor(creds: CredentialStore, settings: () -> Settings, login: suspend (url, user, password) -> String) : Interceptor` — adds `Authorization: Bearer` when a token exists; on 401 for a non-login path, calls `login` once (mutex, single-flight), stores the new token, retries the request once; second 401 → throws `AuthException`.
- Produces: `class HermesApi(client: OkHttpClient, baseUrl: () -> String)` with suspend functions (all via `withContext(Dispatchers.IO)`): `health()`, `status()`, `systemStats()`, `authProviders(): List<String>`, `login(username, password): String` (POST `/auth/password-login`; returns the `hermes_session_at` cookie value parsed from `Set-Cookie`; 401 → `AuthException`), `me()`, `wsTicket(): String`, `sessions(limit = 200): List<SessionDto>`, `renameSession(id, title)`, `archiveSession(id, archived: Boolean)`, `deleteSession(id)`, `modelOptions()`, `workspaces()`, `cronJobs()`, `createCronJob(name, schedule, prompt, deliver)`, `updateCronJob(id, updates: JsonObject)`, `pauseCronJob(id)`, `resumeCronJob(id)`, `triggerCronJob(id)`, `deleteCronJob(id)`, `deliveryTargets()`, `pairing()`, `approvePairing(platform, requestId)`, `revokePairing(platform, userId)`, `skills()`, `toggleSkill(name, enabled)`, `toolsets()`, `profiles()`, `activeProfile(): String`, `logs(file, lines = 200, level: String? = null)`, `usage(days = 30)`, `gatewayStart()`, `gatewayStop()`, `gatewayRestart()`. Management calls append `?profile=` from `settings().profile` when it is not `"default"`.

- [ ] **Step 1: Write failing tests** — `HermesApiTest` with `MockWebServer`: `loginReturnsCookieToken` (enqueue 200 with `Set-Cookie: hermes_session_at=abc; Path=/; HttpOnly` → `login("u","p") == "abc"`, request body equals `{"provider":"basic","username":"u","password":"p"}`), `statusParsesGatewayState` (body from README → `gatewayState == "running"`, `disk.usedPercent == 10.4`), `cronJobsParseSchedule` (`schedule.expr == "0 9 * * *"`, `nextRunAt == "2026-10-20T09:00:00+02:00"`), `profileQueryAppendedWhenNotDefault` (settings profile `ops` → recorded path `/api/cron/jobs?profile=ops`), `non2xxThrowsApiException` (500 → `ApiException(code=500)`). `AuthInterceptorTest`: `addsBearerHeader`, `reloginsOnceOn401` (enqueue 401, then login 200, then 200 → three requests recorded, third has the new bearer), `secondFailureThrowsAuthException` (401, login 401 → `AuthException`), `concurrentRequestsShareOneRelogin` (two parallel calls, one login request recorded).
- [ ] **Step 2: Run** → FAIL.
- [ ] **Step 3: Implement.** `login` must bypass the interceptor's retry (tag the request with `NoAuth`). Wire the OkHttp client in `AppContainer` (Task 11) with `callTimeout 20s`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(api): Hermes REST client with single-flight re-login`.

---

### Task 6: JSON-RPC frame codec

**Files:**
- Create: `data/gateway/JsonRpc.kt`
- Test: `data/gateway/JsonRpcTest.kt`

**Interfaces:**
- Produces: `sealed interface Frame { data class Response(id: String, result: JsonObject?, error: RpcError?); data class Event(type: String, sessionId: String, seq: Long?, payload: JsonObject); data class ServerRequest(id: String, method: String, params: JsonObject); data class Unknown(raw: String) }`, `data class RpcError(code: Int, message: String, data: JsonElement? = null)`.
- Produces: `object JsonRpc { fun decodeFrames(text: String): List<Frame>` (split on `\n`, skip blanks, never throw — malformed → `Unknown`); `fun encodeRequest(id: String, method: String, params: JsonObject?): String`; `fun encodeResult(id: String, result: JsonObject): String }`. Ids are strings `"t<n>"`.

- [ ] **Step 1: Write failing tests** — `decodesEventFrame` (the `message.delta` sample from README → `Event(type="message.delta", sessionId="d4fb0dea", seq=12, payload.text=…)`), `decodesServerRequest` (`{"jsonrpc":"2.0","id":"srq-abc","method":"approval","params":{"command":"rm -rf x"}}`), `decodesResponseWithError` (code 4000), `decodesTwoNewlineDelimitedFrames`, `malformedBecomesUnknownWithoutThrowing`, `encodeRequestOmitsNullParams` (`params` → `{}` not null), `encodeResultMatchesSpec` (`{"jsonrpc":"2.0","id":"srq-abc","result":{"choice":"once"}}`).
- [ ] **Step 2: Run** → FAIL. - [ ] **Step 3: Implement.** - [ ] **Step 4: Run** → PASS. - [ ] **Step 5: Commit** `feat(gateway): JSON-RPC codec`.

---

### Task 7: GatewayClient (WebSocket, heartbeat, reconnect)

**Files:**
- Create: `data/gateway/GatewayClient.kt`, `data/gateway/ConnectionState.kt`
- Test: `data/gateway/GatewayClientTest.kt` (mockwebserver3 `WebSocketListener` server side)

**Interfaces:**
- Consumes: `JsonRpc` (Task 6), `HermesApi.wsTicket()` (Task 5).
- Produces: `enum class ConnectionState { Disconnected, Connecting, Connected, Reconnecting }`.
- Produces: `class GatewayClient(client: OkHttpClient, baseUrl: () -> String, ticket: suspend () -> String, scope: CoroutineScope, clock: () -> Long = System::currentTimeMillis, heartbeatMs = 15_000, deadlineMs = 45_000, backoffMs = listOf(1_000L, 2_000L, 5_000L, 10_000L))` with:
  - `val state: StateFlow<ConnectionState>`; `val events: SharedFlow<Frame.Event>` (replay 0, extraBufferCapacity 1024, `DROP_OLDEST`); `val serverRequests: SharedFlow<Frame.ServerRequest>`.
  - `fun connect()` / `fun disconnect()` (idempotent). On open: send `client.capabilities {"server_requests":true}` then wait for `gateway.ready` before `state = Connected`.
  - `suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs = 60_000): JsonObject` — throws `RpcException(code, message)` on error frame, `GatewayClosedException` if the socket closes while pending.
  - `fun respond(id: String, result: JsonObject)`.
  - Heartbeat: every `heartbeatMs` send `gateway.ping`; any inbound frame resets the deadline; no inbound for `deadlineMs` → close with code 4000 and reconnect. Reconnect uses a fresh ticket each attempt and the backoff list (last value repeats); `state = Reconnecting` while trying.
  - URL: `baseUrl().replaceFirst("http", "ws") + "/api/ws?ticket=" + ticket`.

- [ ] **Step 1: Write failing tests** (server side via `MockWebServer` + `MockResponse.Builder().webSocketUpgrade(listener)`): `connectSendsCapabilitiesThenBecomesConnectedOnReady`, `requestResolvesWithMatchingId` (server replies to `t1`), `requestErrorFrameThrowsRpcException` (code 4000), `eventsAreEmittedTyped` (`message.delta` payload text `"hi"`), `serverRequestRespondSendsResultWithSameId` (server sends `srq-1 approval`, client `respond("srq-1", {"choice":"once"})`, server receives exact JSON), `reconnectsAfterClose` (server closes 1001 → state goes Reconnecting → Connected, second upgrade request carries a new `ticket` query value), `pendingRequestFailsOnClose` (`GatewayClosedException`), `pingSentAfterHeartbeatInterval` (use `heartbeatMs = 50`).
- [ ] **Step 2: Run** → FAIL. - [ ] **Step 3: Implement** with `okhttp3.WebSocket`; serialize sends through a `Mutex`; pending map `ConcurrentHashMap<String, CompletableDeferred<JsonObject>>`. - [ ] **Step 4: Run** → PASS. - [ ] **Step 5: Commit** `feat(gateway): websocket client with heartbeat and reconnect`.

---

### Task 8: Gateway session API (typed RPC wrappers)

**Files:**
- Create: `data/gateway/GatewaySession.kt`, `data/gateway/GatewayModels.kt`
- Test: `data/gateway/GatewaySessionTest.kt` (fake `GatewayClient` via an interface — extract `interface RpcTransport { suspend fun request(method, params): JsonObject; fun respond(id, result) }` that `GatewayClient` implements; tests use a `FakeTransport` recording calls)

**Interfaces:**
- Produces models: `TranscriptMessage(role, text, reasoning, name, context, args: JsonObject?, toolCallId, timestamp: Double?, rowId: Long?)`, `SessionCreated(sessionId, storedSessionId, info: JsonObject, messages: List<TranscriptMessage>)`, `SessionResumed(sessionId, storedSessionId, messages, running: Boolean, pendingApproval: JsonObject?, openRequests: List<JsonObject>)`, `SessionSummary(id, title, preview, startedAt: Double?, messageCount: Int, source)`.
- Produces: `class GatewaySession(transport: RpcTransport)` with `suspend fun create(cwd: String?, model: String?, source = "tsukaima"): SessionCreated`, `resume(storedId): SessionResumed` (params `lazy=false, defer_history=false, source="tsukaima"`), `history(sessionId): List<TranscriptMessage>`, `list(limit = 200): List<SessionSummary>`, `mostRecent(): String?`, `submit(sessionId, text)`, `interrupt(sessionId)`, `setCwd(sessionId, cwd)`, `setTitle(sessionId, title)`, `archive(storedId, archived)`, `delete(storedId)`, `close(sessionId)`, `modelOptions(sessionId): ModelOptionsDto`, `eventsSince(sessionId, lastSeen: Long): JsonObject`, `respondApproval(requestId, choice: ApprovalChoice)` (`enum ApprovalChoice { once, session, always, deny }`).

- [ ] **Step 1: Write failing tests** — `createSendsSourceAndCwd` (recorded method `session.create`, params contain `"source":"tsukaima"` and `"cwd":"/home/x"`, omits `model` when null), `resumeParsesMessages` (fixture = README history sample → 4 messages, third has `role=="tool"`, `name=="terminal"`), `respondApprovalEncodesChoice` (`respond("srq-1", {"choice":"deny"})`), `submitReturnsWithoutResultParsing`.
- [ ] **Step 2: Run** → FAIL. - [ ] **Step 3: Implement.** - [ ] **Step 4: Run** → PASS. - [ ] **Step 5: Commit** `feat(gateway): typed session RPC wrappers`.

---

### Task 9: ChatReducer — events → chat state

**Files:**
- Create: `domain/chat/ChatState.kt`, `domain/chat/ChatReducer.kt`, `domain/chat/AgentStatus.kt`, `domain/chat/ThinkingFlair.kt`
- Test: `domain/chat/ChatReducerTest.kt`, `domain/chat/ThinkingFlairTest.kt`

**Interfaces:**
- Produces: `sealed interface ChatItem { val id: String }` with `UserMessage(id, text)`, `AssistantMessage(id, text, streaming: Boolean, reasoning: String?)`, `ToolCall(id, name, context: String?, args: JsonObject?, status: ToolStatus /* Running, Done, Failed */, output: String?, exitCode: Int?, durationS: Double?)`, `Approval(id /* = srq id */, requestId, command, toolName, answered: Boolean)`, `Thinking(id)`.
- Produces: `sealed interface AgentStatus { object Idle; data class Thinking(flair: String); data class Tool(label: String); object Replying; object Approval; object Stopped; object Offline }` with `val label: String` (Idle = "Hermes is listening", Tool = `"Running " + label`, Replying = "Replying…", Approval = "Waiting for your approval", Stopped = "Gateway stopped", Offline = "Can't reach Hermes") and `val tone: StatusTone { Grey, Accent, Amber, Red }` (Idle Grey; Thinking/Tool/Replying Accent + pulsing; Approval Amber; Stopped/Offline Red).
- Produces: `data class ChatState(items: List<ChatItem> = emptyList(), status: AgentStatus = AgentStatus.Idle, liveSessionId: String? = null, storedSessionId: String? = null, title: String? = null, lastSeq: Long = 0)`.
- Produces: `object ChatReducer { fun reduce(state: ChatState, frame: Frame): ChatState; fun fromHistory(messages: List<TranscriptMessage>): List<ChatItem>; fun onSubmitted(state, text, localId): ChatState; fun onApprovalAnswered(state, id): ChatState; fun onConnectionLost(state): ChatState; fun onConnectionRestored(state): ChatState }`. Rules: `message.start` → append `AssistantMessage(streaming=true, text="")` and status Thinking(flair) unless a Thinking item exists; `thinking.delta`/`reasoning.delta` → status stays Thinking (rotate flair every call to `ThinkingFlair.next()`), reasoning text accumulates into the current assistant item's `reasoning`; `tool.generating` → status Tool(humanName(name)); `tool.start` → append `ToolCall(Running)`, status Tool; `tool.complete` → mark Done/Failed (`exit_code != 0` or `error != null` → Failed), output = `result.output`; `message.delta` → append text to the current streaming assistant item (create one if none), status Replying; `message.complete` → set final `text` (use payload text when non-empty), `streaming=false`, status Idle; `turn.error` → status Idle + append `AssistantMessage(text = "Something went wrong: " + message)`; `session.title` → title; `ServerRequest(method="approval")` → append `Approval`, status Approval; `request.cancel` for an Approval id → remove it; `seq` always advances `lastSeq` when larger. Tool human names: `terminal` → "shell command", `execute_code` → "code", `web_search` → "web search", `web_extract` → "web page", `delegate_task` → "subagent", else the raw name with `_` → space.
- Produces: `object ThinkingFlair { val BASE: List<String>; val WATCHLIST: List<String>; fun next(random: Random = Random.Default): String }` — the two lists copied verbatim from `design/components/chat/StatusLine.jsx` (`THINKING_FLAIR`, `THINKING_FLAIR_WATCHLIST`).

- [ ] **Step 1: Write failing tests** — `historyMapsRoles` (README history fixture → User, Assistant(reasoning only, text "") is dropped, ToolCall(name=terminal, Done), Assistant(text starts "The kernel")), `deltaStreamAccumulates` (three `message.delta` → one streaming assistant item with joined text, status Replying), `completeFinalizesText`, `toolStartCompleteFlipsStatus` (Tool("shell command") then Done with exitCode 0), `toolFailureMarksFailed` (`exit_code: 1`), `approvalAppendsCardAndStatus`, `approvalAnsweredOnce` (`onApprovalAnswered` twice → one card with `answered=true`, status back to Thinking), `requestCancelRemovesApproval`, `closeDuringStreamKeepsPartialText` (`onConnectionLost` → item keeps text, `streaming=false`, status Offline; `onConnectionRestored` → Idle), `turnErrorAppendsMessage`, `seqOnlyAdvances`. `ThinkingFlairTest`: `listsMatchDesignFile` (parse `design/components/chat/StatusLine.jsx` from the repo root in the test with a regex and compare both lists element-wise), `nextCyclesWithoutRepeatingConsecutively`.
- [ ] **Step 2: Run** → FAIL. - [ ] **Step 3: Implement** as pure functions. - [ ] **Step 4: Run** → PASS. - [ ] **Step 5: Commit** `feat(chat): event reducer and agent status`.

---

### Task 10: Minimal markdown for assistant text

**Files:**
- Create: `domain/markdown/Markdown.kt`
- Test: `domain/markdown/MarkdownTest.kt`

**Interfaces:**
- Produces: `sealed interface MdBlock { data class Paragraph(spans: List<MdSpan>); data class Code(lang: String?, code: String); data class Bullets(items: List<List<MdSpan>>) }`, `sealed interface MdSpan { data class Text(s); data class Bold(s); data class Italic(s); data class Code(s) }`, `object Markdown { fun parse(text: String): List<MdBlock> }`. Supports: fenced code (``` with optional lang, unterminated fence runs to end), `- `/`* ` bullets, blank-line paragraphs, `**bold**`, `*italic*`/`_italic_`, `` `code` ``. Everything else is literal text. Never throws.

- [ ] **Step 1: Write failing tests** — `fencedCodeWithLang`, `unterminatedFenceRendersAsCode`, `inlineBoldAndCode` (`"is **6.17** (`x`)"` → spans Text, Bold("6.17"), Text, Code("x"), Text), `bulletsBecomeList`, `plainTextIsOneParagraph`, `tripleBackticksInsideInlineDoNotCrash`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `feat(markdown): minimal parser`.

---

### Task 11: AppContainer, HermesRepository, ConnectionManager

**Files:**
- Create: `di/AppContainer.kt` (replace stub), `data/HermesRepository.kt`, `data/ConnectionManager.kt`
- Test: `data/ConnectionManagerTest.kt`

**Interfaces:**
- Produces: `class AppContainer(context: Context)` building: `HermesJson`, `OkHttpClient` (with `AuthInterceptor`, `callTimeout 20s`, `pingInterval 0`), `SettingsStore`, `EncryptedCredentialStore`, `HermesApi`, `GatewayClient`, `GatewaySession`, `HermesRepository`, `ConnectionManager`, `ToastState`, an app `CoroutineScope(SupervisorJob() + Dispatchers.Default)`.
- Produces: `class HermesRepository(api, settings, creds)`: `suspend fun signIn(url, username, password): Result<Unit>` (normalize url, `api.login`, persist settings + creds, `onboarded=true`), `fun signOut()`, `val isConfigured: Flow<Boolean>` (serverUrl non-empty && password present), plus pass-through suspend funcs for every `HermesApi` call wrapped in `Result`.
- Produces: `class ConnectionManager(api, gateway: GatewayClient, settings, scope, pollMs = 20_000)` exposing `val hermes: StateFlow<HermesState>` where `data class HermesState(reachable: Boolean, status: StatusDto?, lastSeenAt: Long?, connection: ConnectionState)`; `fun start()` (poll `/api/status` every `pollMs` while started, mark `reachable=false` on `IOException`; connect the gateway when configured), `fun stop()`, `suspend fun refreshNow()`. `AgentStatus` derivation helper: `fun HermesState.gatewayStatus(): AgentStatus?` → Offline when `!reachable`, Stopped when `status.gatewayState != "running"`, else null (chat status wins).

- [ ] **Step 1: Write failing tests** — `ConnectionManagerTest` with `MockWebServer`: `pollMarksUnreachableOnIoError` (server shut down → `reachable=false`, `lastSeenAt` keeps the previous value), `pollMarksStoppedGateway` (`gateway_state:"stopped"` → `gatewayStatus() is Stopped`), `refreshNowUpdatesImmediately`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `feat(data): container, repository, connection manager`.

---

### Task 12: Chat design-system components

**Files:**
- Create under `ui/chat/components/`: `StatusLine.kt`, `UserBubble.kt`, `AssistantMessage.kt`, `CodeBlock.kt`, `ToolRow.kt`, `ApprovalCard.kt`, `PairingCard.kt`, `Composer.kt`, `ChatTopBar.kt`, `ChatSkeleton.kt`, `MarkdownText.kt`
- Reference: `design/components/chat/*.jsx` + `*.prompt.md`, HANDOFF §3.

**Interfaces:**
- Produces: `StatusLine(status: AgentStatus, onClick: (() -> Unit)? = null)` — 7dp dot + `statusLine` serif italic 14sp; dot colour by tone (Grey `#746B61`, Accent, Amber `#A8823E`, Red `#B3564E`); pulses 2 s opacity 1→.35 for Thinking/Tool/Replying.
- Produces: `UserBubble(text)` — right-aligned, max 82 % width, `accentWash` fill, 16dp radius with 6dp bottom-end, 10×14dp padding, `body` text.
- Produces: `AssistantMessage(text, streaming, reasoning: String?)` — flush left, 20dp gutters, max 640dp, `MarkdownText(blocks)`; streaming shows a 2dp×1em accent caret blinking 1 s `steps(2)` after the last character; reasoning (when non-null) collapsed behind a `caption` "Reasoning" toggle.
- Produces: `CodeBlock(code, lang: String?, readOnly = false, modifier)` — `codeBg`, 10dp radius, header: lang `label` at 55 % + "Copy" `caption` with `content_copy` → `check` "Copied" for 1.5 s (uses `ClipboardManager`); `pre` in `mono` 13sp/1.6 `codeInk`, 14dp side padding, horizontal scroll.
- Produces: `ToolRow(item: ChatItem.ToolCall)` — 1dp hairline, 12dp radius, raised bg, 44dp header (icon `terminal`/`psychology` 18dp, name `ui` ink2, " · context" ink3 single line ellipsized, status icon 16dp `check`/`error` red, `expand_more` rotating 180° over 200 ms); expanded body 12dp pad `caption` output (mono for terminal).
- Produces: `ApprovalCard(item: ChatItem.Approval, onApprove, onDeny)` — raised, hairline, 16dp, shadow, 16dp pad; header `gpp_maybe` 20dp amber + serif italic "Hermes wants to run a tool"; `label` tool name; command in code well (10×12dp); buttons end-aligned 10dp gap: `GhostButton("Deny", Danger)` + `PrimaryButton("Approve", icon=check)`; when `answered` both disabled and a `caption` "Approved"/"Denied" shows.
- Produces: `PairingCard(platform, who, code, onApprove, onRevoke)` — same tier, `link` icon amber, "Pairing request from <Platform>", who `ui` ink2 start, code `mono` 20sp letterSpacing .18em end; `GhostButton("Revoke", Danger)` + `PrimaryButton("Approve")`.
- Produces: `Composer(value, onValueChange, onSend, onAttach, enabled, placeholder)` — outer padding 6/12/14dp, raised bg, hairline, 28dp radius, shadow, 6dp inner pad; `IconButton44(add)` · text field `body` 11dp vertical pad max 120dp tall (≈4 lines) · `IconButton44(arrow_upward, filled=true)`; IME action Send sends; disabled → 55 % opacity, send unfilled, placeholder "Hermes is unreachable".
- Produces: `ChatTopBar(modelLabel, onMenu, showMenu: Boolean, onModel, onMore)` — 8dp pad, `IconButton44(menu)` (hidden when `!showMenu`), borderless `Chip(modelLabel, trailing=expand_more)`, spacer, `IconButton44(more_vert)`.
- Produces: `ChatSkeleton()` — bubble 56 %×40 r16, lines 92/78/40 %, row 44 r12, sunken, 1.6 s pulse.

- [ ] **Step 1: Write the failing test** — `ui/chat/components/StatusToneTest.kt`: `idleIsGreyNotPulsing`, `thinkingIsAccentPulsing`, `approvalIsAmber`, `offlineIsRed` against a pure `fun statusVisual(status: AgentStatus, colors: TsukaimaColors): StatusVisual(color: Color, pulsing: Boolean)` that `StatusLine` uses.
- [ ] **Step 2–5:** FAIL → implement all composables with light/dark `@Preview`s → PASS + `assembleDebug` → commit `feat(ui): chat components`.

---

### Task 13: AppShell, navigation, drawer chrome

**Files:**
- Create: `ui/navigation/Routes.kt`, `ui/navigation/TsukaimaNavHost.kt`, `ui/shell/AppShell.kt`, `ui/shell/AppViewModel.kt`, `ui/drawer/SessionsDrawer.kt`, `ui/drawer/ConversationItem.kt`, `ui/drawer/DrawerViewModel.kt`
- Modify: `MainActivity.kt` (set `TsukaimaTheme` from settings, `AppShell`), `TsukaimaApp.kt`
- Test: `ui/drawer/DrawerViewModelTest.kt`

**Interfaces:**
- Produces: `sealed class Route(val path: String) { Onboarding, Status, Chat, Cron, CronEdit("cron/edit?id={id}"), Settings, Logs }`.
- Produces: `AppShell(container)` — reads `LocalConfiguration` width; `wide = width >= 720.dp`; phone: `ModalNavigationDrawer` with `SessionsDrawer` 296dp, scrim `colors.scrim`, slide 200 ms `Motion.easing`; wide: `Row { SessionsDrawer(Modifier.width(300.dp)) + VerticalDivider(hairline) + Box(Modifier.weight(1f)) { content centered, widthIn(max = 760.dp) } }`. Screen changes cross-fade 220 ms. Start destination = `Onboarding` when `!settings.onboarded` else `Status`. Hosts `ToastHost`.
- Produces: `AppViewModel(container)` exposing `settings: StateFlow<Settings>`, `hermes: StateFlow<HermesState>`, `fun openDrawer()/closeDrawer()`; starts `ConnectionManager` in `init`, stops in `onCleared`.
- Produces: `SessionsDrawer(state: DrawerUiState, onNewChat, onStatus, onCron, onOpen(storedId), onRename(id), onArchive(id, archived), onDelete(id), onSettings, onQuery(String), onShowArchived(Boolean))` — header 20dp pad: mark 24dp (`assets/mark.svg` → `res/drawable/mark.xml` vector; dark variant via tint `accent`) + wordmark 22sp + kanji 11sp; `TsukaimaTextField("Search sessions")`; rows New chat / Status / Cron; per profile `SectionLabel("<PROFILE> PROFILE")` + `ConversationItem` list with age caption (`"2m"`, `"3h"`, `"5d"`); "Archived (n)" row; long-press → `MenuSheet(Rename / Archive|Unarchive / Delete)`; archived view = back row "Archived" + archived items with profile as meta; footer top hairline + Settings row.
- Produces: `ConversationItem(title, meta, selected, onClick, onLongClick)` — 44dp, 12dp radius, selected = `accentSelected` fill + ink text.
- Produces: `DrawerViewModel(repo, settings)` with `data class DrawerUiState(sessions: List<SessionDto>, query = "", showArchived = false, selectedId: String?, loading = true)`; derived `grouped: Map<String /*profile*/, List<SessionDto>>` (filter by query on title/preview, split archived); `fun refresh()`, `rename(id, title)`, `archive(id, archived)`, `delete(id)`, each followed by `refresh()`. Also `fun ageLabel(startedAt: Double?, now: Long): String`.

- [ ] **Step 1: Write failing tests** — `DrawerViewModelTest` (fake repo): `groupsByProfileExcludingArchived`, `queryFiltersTitleAndPreviewCaseInsensitive`, `archivedCountMatches`, `ageLabelFormats` (`90s→"1m"`, `3700s→"1h"`, `2 days→"2d"`).
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `feat(shell): navigation, adaptive shell, sessions drawer`.

---

### Task 14: Onboarding + Settings (sign-in)

**Files:**
- Create: `ui/onboarding/OnboardingScreen.kt`, `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`, `res/drawable/resting_wisp.xml` (from `design/assets/resting-wisp.svg`, tint ink3), `res/drawable/mark.xml`
- Test: `ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Produces: `OnboardingScreen(onConnect)` — exactly HANDOFF §1: 36dp side padding, mark 72dp, 14dp, "Tsukaima" `wordmarkLarge` 38sp, "Your familiar is listening." serif italic 14sp ink2, caption 12.5sp ink3 max 260dp ("Connect to your Hermes instance to start talking to your agent."), 22dp, `PrimaryButton("Connect")`, 40dp, wisp 180dp wide 80 % opacity, 使い魔 vertical top-end (`kanji` style, ink3, `rotate`-free vertical stacking of the three glyphs).
- Produces: `SettingsViewModel(repo, settings, creds, api)` with `data class SettingsUiState(serverUrl, username, password, testing: Boolean, testResult: String?, profiles: List<ProfileDto>, profile, models: List<Pair<String,String>> /*provider:model*/, model: String?, skills: List<SkillDto>, toolsets: List<ToolsetDto>, pairing: PairingDto?, theme: ThemeMode, materialYou: Boolean, simulateOffline: Boolean)`; `fun testConnection()` (health → `"Hermes <version>"` or error text), `signIn(): Result<Unit>` (→ `repo.signIn`, then `loadRemote()`), `setProfile(name)`, `setModel(provider, model)`, `toggleSkill`, `toggleToolset` (toolsets are read-only in the API → show switch disabled with helper "Managed in the dashboard"), `approvePairing`, `revokePairing`, `setTheme`, `setMaterialYou`, `setSimulateOffline` (feeds `ConnectionManager.simulateOffline` flag — add that `MutableStateFlow<Boolean>` to Task 11's class).
- Produces: `SettingsScreen(vm, onBack, onLogs, onOpenDashboard)` — header back + "Settings" `title`; sections (28dp apart, `sectionTitle` 17sp + optional hint): **Hermes instance** (Server URL mono, Username, Password with `PasswordVisualTransformation`, helper "Your dashboard login.", row of `SecondaryButton("Test connection")` + `PrimaryButton("Sign in")`, result caption); **Profile** chips; **Model** chips (current provider's models); **Skills** rows with `TsukaimaSwitch`; **Toolsets** rows (disabled switches); **Pairing** (`PairingCard` for each pending + approved rows with `GhostButton("Revoke", Danger, sm)`); **Diagnostics** (Logs → chevron, Usage card row showing `totals` tokens/cost from `usage(30)`, "Open admin dashboard" `open_in_new` → `Intent.ACTION_VIEW serverUrl`, "Simulate Hermes unreachable" switch); **Theme** chips Warm / Dark / System + "Material You" switch row.

- [ ] **Step 1: Write failing tests** — `SettingsViewModelTest`: `signInNormalizesUrlAndPersists` (input `"100.69.91.116:9119/"` → stored `"http://100.69.91.116:9119"`, creds saved, `onboarded=true`), `signInFailureKeepsOldCredentials`, `testConnectionReportsVersion`, `setProfilePersists`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `feat(ui): onboarding and settings with sign-in`.

---

### Task 15: Status screen (home)

**Files:**
- Create: `ui/status/StatusScreen.kt`, `ui/status/StatusViewModel.kt`
- Test: `ui/status/StatusViewModelTest.kt`

**Interfaces:**
- Produces: `StatusViewModel(repo, connection: ConnectionManager, settings)` → `data class StatusUiState(hermes: HermesState, stats: SystemStatsDto?, pairing: PairingDto?, lastSession: SessionDto?, busy: Boolean, confirm: Confirm? /* Restart | Stop */)`; `fun refresh()`, `startGateway()`, `restartGateway()`, `stopGateway()` (each: call API, then poll `refreshNow()` every 1 s up to 10 s until `gatewayState` flips), `approvePairing`, `revokePairing`. Derived `fun facts(): List<Fact(title, subtitle, danger)>` = version/host (`"Hermes 0.22.2" / hostname`), gateway state (`"Gateway running" / platforms joined or "No platforms"`; danger when stopped), platforms, active sessions, memory/disk (`"Memory 1.2 / 8.0 GB" / "Disk 10% used"`).
- Produces: `StatusScreen(vm, onMenu, showMenu, onSettings, onNewChat, onCron, onContinue(storedId), onOpenCached)` — HANDOFF §2 exactly, including the offline variant (card "Can't reach Hermes" `titleSmall` + caption + `PrimaryButton("Retry", refresh)` + `GhostButton("Settings")`; "Open cached copy" row; facts at 55 % opacity under `SectionLabel("LAST KNOWN STATUS · <n> MIN AGO")`). Restart/Stop go through `ConfirmDialog`.

- [ ] **Step 1: Write failing tests** — `StatusViewModelTest`: `factsShowDangerWhenStopped`, `stopGatewayPollsUntilStopped` (fake api flips on 3rd status call → `busy` false, state stopped), `offlineUsesLastKnownStatus`, `lastKnownMinutesLabel` (`lastSeenAt` 14 min ago → "LAST KNOWN STATUS · 14 MIN AGO").
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `feat(ui): status home screen`.

---

### Task 16: Chat screen + ChatViewModel

**Files:**
- Create: `ui/chat/ChatScreen.kt`, `ui/chat/ChatViewModel.kt`, `ui/chat/EmptyChat.kt`
- Test: `ui/chat/ChatViewModelTest.kt`

**Interfaces:**
- Consumes: `GatewayClient.events/serverRequests/state`, `GatewaySession`, `ChatReducer`, `ConnectionManager.hermes`, `SettingsStore`.
- Produces: `ChatViewModel(container, storedSessionId: String? /* null = new */)` → `data class ChatUiState(chat: ChatState, loading: Boolean, input: String, models: ModelOptionsDto?, workspaces: WorkspacesDto?, workspace: String?, offline: Boolean, menu: ChatMenu? /* Model, Workspace, Overflow */, dialog: ChatDialog? /* Rename, DeleteConfirm */)`. Behaviour: `init` collects gateway events filtered by `liveSessionId` (or `""` globals) and server requests → `ChatReducer.reduce`; `ConnectionState` transitions → `onConnectionLost/Restored`, and on `Connected` after a loss calls `session.resume(storedId)` then `eventsSince(lastSeq)` replaying each event through the reducer (dedupe by `seq <= lastSeq`). `fun open()`: new → `create(cwd = workspace, model = settings.model)`; existing → `resume` + `fromHistory` (show `ChatSkeleton` while `loading`). `send()`: append `UserMessage` locally, `submit`, clear input; `approve(id)` / `deny(id)` → `respond(id, {"choice":"once"|"deny"})` + `onApprovalAnswered`; `interrupt()`; `setModel(provider, model)` (persist + next session uses it; for the live session call RPC `model.switch`? — not in contract list: instead `session.close` + `create(model)` only when the chat is empty, else show toast "Model applies to the next chat"); `setWorkspace(path)` → `session.cwd.set`; `rename(title)` → `session.title` + REST `renameSession`; `archive()`, `copySessionId()` (toast "Session ID copied"), `delete()`. Status shown = `hermes.gatewayStatus() ?: chat.status`.
- Produces: `ChatScreen(vm, onMenu, showMenu, onStatus, onDeleted)` — HANDOFF §3: `ChatTopBar`, `StatusLine(onClick = onStatus)`, `LazyColumn` (6dp top pad, auto-scroll to bottom on new items while the user is at the bottom), items rendered by type, `Composer` (disabled when offline), overflow `MenuSheet` (Rename → `ConfirmDialog` with `TsukaimaTextField`; Archive; Copy session ID; Delete → danger dialog), model `MenuSheet` (providers → models, `check` on current), workspace `MenuSheet`. `EmptyChat(workspace, onPickWorkspace)` — "New chat" serif italic 16sp, caption "Working directory", `Chip(folder, path, expand_more)`.

- [ ] **Step 1: Write failing tests** — `ChatViewModelTest` with fake transport + `MutableSharedFlow` events: `openNewCreatesSessionWithWorkspace`, `openExistingResumesAndShowsHistory`, `sendAppendsUserMessageAndSubmits`, `streamedReplyUpdatesState`, `approveRespondsOnceAndMarksAnswered` (second `approve(id)` sends nothing), `reconnectReplaysOnlyNewSeq` (events with seq 5,6 already seen, replay returns 5,6,7 → one new item), `offlineDisablesComposerAndShowsOfflineStatus`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `feat(ui): chat screen`.

---

### Task 17: Cron screens

**Files:**
- Create: `ui/cron/CronScreen.kt`, `ui/cron/CronEditScreen.kt`, `ui/cron/CronViewModel.kt`, `domain/cron/CronHuman.kt`
- Test: `domain/cron/CronHumanTest.kt`, `ui/cron/CronViewModelTest.kt`

**Interfaces:**
- Produces: `object CronHuman { fun describe(expr: String): String }` — "Every day at 09:00", "Every Monday at 08:30", "Every 15 minutes", "Every hour", "Weekdays at 18:00"; unknown → the raw expression. `fun nextRunLabel(iso: String?, now: Instant): String` → "in 2h", "in 3d", "tomorrow 09:00", "paused".
- Produces: `CronViewModel(repo)` → `CronUiState(jobs: List<CronJobDto>, targets: List<DeliveryTargetDto>, loading, editing: CronJobDto? /* null = closed */, isNew: Boolean, draft: CronDraft(name, prompt, schedule, deliver))`; `refresh()`, `runNow(id)` (toast "Running <name>"), `pause(id)`, `resume(id)`, `startEdit(job?)`, `updateDraft(…)`, `save()` (create or `updateCronJob(id, {name,schedule,prompt,deliver})`), `delete(id)`. `canSave = draft.name.isNotBlank() && draft.prompt.isNotBlank()`.
- Produces: `CronScreen(vm, onBack)` — header back · "Cron" `title` 22sp · `SecondaryButton("New job", add, Sm)`; rows: icon `schedule`/`pause_circle`, name, `"${CronHuman.describe(expr)} · $expr"` subtitle, next-run meta trailing; paused rows 60 % opacity; tap/long-press → `MenuSheet(Run now / Pause|Resume / Edit / Delete(danger→ConfirmDialog))`; helper caption under the list ("Jobs run on the Hermes host in its local time."). `CronEditScreen(vm, onClose)` — close · "Edit job"/"New job" · `PrimaryButton("Save", Sm, enabled = canSave)`; fields 18dp apart: Name, Prompt (`minLines = 5`), Schedule (mono, helper "Cron expression, server time. Example: 0 9 * * 1-5"), Deliver to chips from `targets` (fallback "Session only" = `local`).

- [ ] **Step 1: Write failing tests** — `CronHumanTest`: `dailyAtNine` (`"0 9 * * *"` → "Every day at 09:00"), `weekdays` (`"0 18 * * 1-5"` → "Weekdays at 18:00"), `everyFifteenMinutes` (`"*/15 * * * *"`), `unknownFallsBack`. `CronViewModelTest`: `saveDisabledUntilNameAndPrompt`, `saveNewCallsCreate`, `saveExistingCallsUpdateWithUpdatesObject`, `pauseThenRefresh`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `feat(ui): cron list and editor`.

---

### Task 18: Logs screen

**Files:**
- Create: `ui/logs/LogsScreen.kt`, `ui/logs/LogsViewModel.kt`
- Test: `ui/logs/LogsViewModelTest.kt`

**Interfaces:**
- Produces: `LogsViewModel(repo)` → `LogsUiState(file: String = "gateway", level: String? = null /* null = All, "WARNING" = Warn+ */, lines: List<String>, loading)`; `setFile`, `setLevel`, `refresh()` (`logs(file, 300, level)`), auto-refresh every 5 s while the screen is resumed.
- Produces: `LogsScreen(vm, onBack)` — header back · "Logs"; chips `gateway.log` / `agent.log` · divider · `All` / `Warn+`; `CodeBlock(readOnly = true)` filling the remaining height with 20dp margins, scrolled to the bottom on refresh.

- [ ] **Step 1: Write failing tests** — `switchingFileRefetches`, `warnLevelMapsToWARNING`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `feat(ui): logs screen`.

---

### Task 19: Notifications + foreground gateway service

**Files:**
- Create: `notifications/Channels.kt`, `notifications/ApprovalNotifier.kt`, `notifications/GatewayService.kt`, `notifications/NotificationActionReceiver.kt`
- Modify: `AndroidManifest.xml` (service `foregroundServiceType="dataSync"`, receiver), `ConnectionManager` (expose `approvals: SharedFlow<Frame.ServerRequest>` and gateway state changes as a `SharedFlow<HermesEvent>`), `MainActivity` (request `POST_NOTIFICATIONS` on first launch after onboarding)
- Test: `notifications/ApprovalNotifierTest.kt` (pure: builds `NotificationSpec` data, not Android `Notification`)

**Interfaces:**
- Produces channels: `approvals` (high, "Tool approvals"), `pairing` (high), `agent` (default, "Agent activity"), `gateway` (default, "Gateway"), `service` (low, "Connection").
- Produces: `data class NotificationSpec(channel, id: Int, title, text, actions: List<NotificationAction(label, intentAction, extras)>)`; `object ApprovalNotifier { fun forApproval(req: Frame.ServerRequest): NotificationSpec` (title "Hermes wants to run a tool", text = command, actions Approve/Deny carrying `request id`); `fun forPairing(json): NotificationSpec` (Approve/Revoke); `fun forTaskDone(title): NotificationSpec`; `fun forGateway(state: AgentStatus): NotificationSpec` (Stopped/Offline only); `fun forCronFailed(name): NotificationSpec }`.
- Produces: `GatewayService : Service` — started when the app is backgrounded with a configured server (`ProcessLifecycleOwner`), keeps `GatewayClient` connected, posts notifications for the five events; stopped when the app returns to foreground. `NotificationActionReceiver` handles `ACTION_APPROVE`/`ACTION_DENY` → `gateway.respond(id, {"choice": …})` exactly once (dedupe set of answered ids in the container) and cancels the notification; pairing actions call the REST endpoints.

- [ ] **Step 1: Write failing tests** — `approvalSpecHasApproveAndDenyActionsWithRequestId`, `gatewaySpecOnlyForStoppedOrOffline` (Idle → null), `taskDoneUsesSessionTitle`.
- [ ] **Step 2–5:** FAIL → implement → PASS → commit `feat(notifications): actionable approvals and gateway alerts`.

---

### Task 20: Wide layout polish, release build, end-to-end check against the test Hermes

**Files:**
- Modify: `AppShell.kt` (verify ≥720dp pane behaviour, hidden menu icon, centered 760dp column), `app/build.gradle.kts` (release `isMinifyEnabled = true`, `proguard-rules.pro` keep rules for kotlinx.serialization + OkHttp), `.github/workflows/android.yml` (also upload release-unsigned APK)
- Create: `scripts/e2e-smoke.sh` (bash + python: logs in to the test Hermes from `.local/hermes-test-creds.env`, creates a session over WS, submits "Reply with exactly: pong", asserts `message.complete.text` contains "pong", creates/deletes a cron job, prints PASS), `README.md` (what it is, build, sign-in, screenshots section placeholder, protocol pointer)

- [ ] **Step 1:** Run `scripts/e2e-smoke.sh` → PASS (proves the server side the app targets still behaves as the plan assumes).
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease` → all green; fix lint errors (warnings may stay).
- [ ] **Step 3:** Push `main`, confirm the GitHub Actions run is green and the `tsukaima-debug` artifact exists (`gh run list --limit 1`, `gh run download`).
- [ ] **Step 4:** Send the debug APK to the user with `SendUserFile` and `notify "Tsukaima debug APK ready" -t "Task Done" -p high -T white_check_mark`.
- [ ] **Step 5:** Commit `chore: release config, e2e smoke, README`.

---

## Self-review notes

- Spec coverage: HANDOFF screens 1–8 → Tasks 14, 15, 16, 13, 17, 14, 18, 13/20. Interactions & motion → Tasks 2, 3, 12. State model → Tasks 9, 16. Tokens → Task 2. Assets → Tasks 13, 14. "Not in the prototype (decided)": push notifications → Task 19; attach-upload (`POST /api/files/upload` into the session cwd) and the archived-session read-only banner are **deferred to a follow-up plan** (noted here so nobody assumes they are done); first-run connection test → Task 14 (`testConnection`).
- The `model.switch` RPC is not in the verified method list; Task 16 therefore applies a model choice to the next session and says so with a toast. If a later Hermes exposes a live switch, swap it in there.
- Toolset toggling has no REST write endpoint in 0.22.2 (`/api/tools/toolsets` is read-only) — Task 14 renders them disabled with a helper line rather than faking it.
