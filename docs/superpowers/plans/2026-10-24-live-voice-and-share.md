# Live voice (GPT-Live) + "Send to Tsukaima" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an Android share-sheet target that drops shared text/images/PDFs into the chat composer, and a GPT-Live voice engine (WebRTC to Hermes' `/api/audio/voice-live/*`, client-delegation loop) behind the existing Mic button.

**Architecture:** Pure-Kotlin `domain/livevoice` (events, transcript, text utilities, stop phrases, `LiveTransport` seam, `LiveVoiceController`) tested with fakes; one Android `WebRtcLiveTransport` on `io.getstream:stream-webrtc-android:1.3.10`; the existing `ChatVoice`/`VoiceScreen` grow a live variant; a foreground service keeps the session alive with the screen off. Share: a translucent `ShareActivity` copies the content to cache, the chat consumes it from `AppContainer.pendingShare`.

**Tech Stack:** Kotlin 2.2, Compose (BOM 2025.10), OkHttp 5, kotlinx.serialization, coroutines; tests with JUnit4 + Robolectric 4.17 + Compose UI test; Gradle at `~/gradle/current/bin/gradle` with `ANDROID_HOME=$HOME/android-sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.

**Spec:** `docs/superpowers/specs/2026-10-24-live-voice-and-share-design.md` (the attached user guide is quoted in its §2.1).

## Global Constraints

- Never run two Gradle builds in the tree at once. Unit tests: `~/gradle/current/bin/gradle :app:testDebugUnitTest --tests '<class>'`; before a task's commit, the full `:app:testDebugUnitTest` and `:app:lintDebug` must pass (lint may keep its 19 pre-existing warnings, no new errors).
- Commits: `git -c user.name="Mariusz Suchodolski" -c user.email="mariusz@suchodol.ski" commit` with explicit `git add <paths>`; never `git add -A`; never stage `scripts/__pycache__/` or `scripts/site/node_modules/`. Trailer lines: `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01KxNw4i4F4ZnpWcS6Nh3cAd`.
- Never print `.local/*` file contents, keystore passwords, API keys or tokens. The local test Hermes is `http://100.69.91.116:9119` (creds `.local/hermes-test-creds.env`, loaded with `set -a; . …; set +a`); the user's real Hermes (`hermes:9119`) is never touched.
- Package `app.tsukaima`; minSdk 29, targetSdk 36; Kotlin style of the codebase (KDoc on public types, 120-col lines, no `!!` on parsed JSON, `Result` for repository calls).
- User-visible strings use the typographic apostrophe (’) as the codebase does ("couldn’t").
- Nothing here changes the chained voice engine's behaviour; `VoiceControllerTest` must keep passing unchanged.

## Review Focus

1. **Live session billed but invisible**: a start that half-succeeds (POST 200, no `session.started`) must end in teardown within 20 s and fall back to chained — Task 5 test `startWithoutStartedTimesOutAndClosesTransport`.
2. **A late reply for a superseded delegation spoken aloud**: Task 5 test `newerDelegationStopsFeedingTheOlderOne`.
3. **Stop phrase as part of a request** ("stop the container") must still delegate: Task 4 test `stopPhraseInsideARequestIsNotAStop`.
4. **Share with a sender grant that expires**: streams are copied inside `ShareActivity` before any navigation — Task 2 code step + `SharedFileStore` test `copyKeepsNameMimeAndSizeAndSkipsOversize`.
5. **Voice-live submit queued behind a running turn** (would never be spoken): Task 7 test `sendLiveIsNeverQueued`.

---

### Task 1: Share intake, file store and `FilePickedFile` (pure)

**Files:**
- Create: `app/src/main/kotlin/app/tsukaima/share/ShareIntake.kt`
- Create: `app/src/main/kotlin/app/tsukaima/share/SharedContent.kt`
- Create: `app/src/main/kotlin/app/tsukaima/share/SharedFileStore.kt`
- Create: `app/src/main/kotlin/app/tsukaima/ui/chat/FilePickedFile.kt`
- Test: `app/src/test/kotlin/app/tsukaima/share/ShareIntakeTest.kt`, `app/src/test/kotlin/app/tsukaima/share/SharedFileStoreTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class ShareTarget { LastChat, NewChat }
  data class SharedFile(val path: String, val name: String, val mime: String?, val size: Long)
  data class SharedContent(val text: String, val files: List<SharedFile>, val target: ShareTarget, val sessionId: String?)
  /** What an incoming share intent offers, before any I/O. */
  data class ShareRequest(val text: String, val streams: List<String>)   // streams = content URI strings
  object ShareIntake {
      const val ACTION_SEND = "android.intent.action.SEND"; const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
      /** null when nothing usable: no text and no stream, or an action/type outside the manifest filters. */
      fun parse(action: String?, type: String?, text: String?, streams: List<String>): ShareRequest?
      /** "Link" for a single http(s) URL, else the first line of text cut to 60 chars with "…", else "3 images" / "2 files" / "report.pdf". */
      fun summary(text: String, files: List<SharedFile>): String
  }
  class SharedFileStore(private val root: File /* cacheDir/shared */, private val now: () -> Long = System::currentTimeMillis) {
      /** Copies one stream into a fresh `<uuid>/<name>` under root. Returns null (nothing written) when size > ChatAttachments.MAX_BYTES or open() throws. */
      fun copy(name: String, mime: String?, size: Long?, open: () -> InputStream): SharedFile?
      /** Deletes entries older than 24 h. */ fun prune()
      fun delete(file: SharedFile)
  }
  class FilePickedFile(private val file: File, private val mime: String?) : PickedFile   // describe() = FileInfo(file.name, mime, file.length()); open() = FileInputStream
  ```

- [ ] **Step 1: Write the failing tests** (`ShareIntakeTest`):
  - `textOnlySendIsAccepted`: `parse(ACTION_SEND, "text/plain", "hi", emptyList())` → `ShareRequest("hi", [])`.
  - `imageStreamWithoutTextIsAccepted`: `parse(ACTION_SEND, "image/png", null, ["content://a/1"])` → text "" and the stream.
  - `sendMultiplePdfsIsAccepted`: `parse(ACTION_SEND_MULTIPLE, "application/pdf", null, [u1, u2])` keeps both.
  - `blankTextAndNoStreamIsRejected`: `parse(ACTION_SEND, "text/plain", "   ", [])` → null.
  - `unknownActionIsRejected`: `parse("android.intent.action.VIEW", "text/plain", "x", [])` → null.
  - `summaryNamesLinksImagesFilesAndTruncates`: `summary("https://x.y/z", [])` = "Link"; `summary("a".repeat(80), [])` ends with "…" and has 61 chars; `summary("", 3 images)` = "3 images"; `summary("", [pdf "report.pdf"])` = "report.pdf"; `summary("", [image, pdf])` = "2 files".
  `SharedFileStoreTest` (JUnit `TemporaryFolder`): `copyKeepsNameMimeAndSizeAndSkipsOversize` (a 5-byte stream → file exists, `size == 5`; `size = MAX_BYTES + 1` → null and nothing on disk); `pruneDeletesOldEntriesOnly` (a copy with `now` set 25 h earlier is deleted, a fresh one kept); `copyWithUnreadableStreamReturnsNull`.
- [ ] **Step 2: Run them, expect compilation failure** (`--tests 'app.tsukaima.share.*'`).
- [ ] **Step 3: Implement** the four files per the Interfaces block. `copy` sanitises the name (`File(name).name`, blank → "attachment") and writes under a fresh `UUID` directory with a 12 MB cap enforced while streaming too (stop and delete on overflow).
- [ ] **Step 4: Run the tests, expect PASS.**
- [ ] **Step 5: Commit** `feat(share): intake, cached copies and FilePickedFile`.

### Task 2: `ShareActivity`, manifest, deep link into the chat, consumption

**Files:**
- Create: `app/src/main/kotlin/app/tsukaima/share/ShareActivity.kt`, `app/src/main/kotlin/app/tsukaima/share/ShareCard.kt`
- Modify: `app/src/main/AndroidManifest.xml` (activity + filters), `app/src/main/res/values/themes.xml` (`Theme.Tsukaima.Share`: parent `Theme.Tsukaima`, translucent window, no background, like `Theme.Tsukaima.Assist` — read it first), `app/src/main/kotlin/app/tsukaima/di/AppContainer.kt` (`val pendingShare = MutableStateFlow<SharedContent?>(null)`, `val sharedFiles = SharedFileStore(File(appContext.cacheDir, "shared"))`), `app/src/main/kotlin/app/tsukaima/TsukaimaApp.kt` (`sharedFiles.prune()` on IO at start), `app/src/main/kotlin/app/tsukaima/data/prefs/SettingsStore.kt` (`lastChatId: String? = null`, key `last_chat_id`), `app/src/main/kotlin/app/tsukaima/ui/chat/ChatBackend.kt` (`val pendingShare: StateFlow<SharedContent?>`, `fun takeShare(content: SharedContent)` = compare-and-set to null, `fun deleteSharedFile(SharedFile)`, `suspend fun rememberLastChat(id: String?)`), `app/src/main/kotlin/app/tsukaima/ui/chat/ChatViewModel.kt`, `app/src/main/kotlin/app/tsukaima/ui/chat/ChatAttachments.kt`
- Test: `app/src/test/kotlin/app/tsukaima/ui/ShareCardUiTest.kt`, `app/src/test/kotlin/app/tsukaima/ui/chat/ChatViewModelShareTest.kt` (follow the existing `ChatViewModel*Test` rigs and `FakeChatBackend`), `SettingsStoreTest` gains the round trip of `lastChatId`.

**Interfaces:**
- Consumes: Task 1 types; `ApprovalNotifier.EXTRA_OPEN_SCREEN` / `SCREEN_CHAT`, `DeepLink.EXTRA_SESSION_ID` (MainActivity already routes them to `container.openScreen`).
- Produces: `@Composable fun ShareCard(summary: String, hasLastChat: Boolean, onLastChat: () -> Unit, onNewChat: () -> Unit, onDismiss: () -> Unit)`; `ChatViewModel.attachShared(content: SharedContent)` (internal, called from the collector).

- [ ] **Step 1: Failing tests.** `ShareCardUiTest`: "Send to Hermes", the summary, "Continue last chat" and "New chat" are displayed and call their lambdas; with `hasLastChat = false` only "New chat" exists. `ChatViewModelShareTest`: `shareForANewChatFillsInputAndAttaches` (a new VM, `pendingShare` = text "hello" + one `SharedFile` → `state.input == "hello"`, one pending attachment named like the file, `pendingShare` now null); `shareForAnotherChatIsLeftAlone` (content with `sessionId = "s-other"` on a VM bound to `"s-mine"` → untouched); `existingInputGetsABlankLineThenTheSharedText`.
- [ ] **Step 2: Run, expect failure.**
- [ ] **Step 3: Implement.** `ShareActivity` (ComponentActivity, theme `Theme.Tsukaima.Share`): `ShareIntake.parse(intent.action, intent.type, EXTRA_TEXT, EXTRA_STREAM uris)`; null → toast "Nothing Tsukaima can take from that share", finish. Copies on `Dispatchers.IO` via `SharedFileStore.copy(ContentPickedFile(resolver, uri).describe()…)`; oversize → toast "Skipped <name>: over 12 MB". Reads `settings.lastChatId` (null → skip the card, target NewChat). Card choice → `container.pendingShare.value = SharedContent(text, files, target, sessionId)`; start `MainActivity` with `EXTRA_OPEN_SCREEN = SCREEN_CHAT` (+ `EXTRA_SESSION_ID` for LastChat), flags `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_SINGLE_TOP or FLAG_ACTIVITY_CLEAR_TOP`; finish. Dismiss → delete copies, finish. `ChatViewModel.init` collects `backend.pendingShare`: consume when `(c.target == NewChat && state.isNew && storedSessionId == null) || c.sessionId == storedSessionId`; `setInput(if (input.isBlank()) text else input + "\n\n" + text)` and `attach(files.map { FilePickedFile(File(it.path), it.mime) })`; `ChatAttachments` deletes the cached copy (new optional `onDone: (PickedFile) -> Unit` hook, or by path when the `PickedFile` is a `FilePickedFile`) after upload success or final failure/removal. The chat calls `backend.rememberLastChat(storedId)` when it binds a stored id (the `storedSessionId = done.storedId` site and the resume path) and `rememberLastChat(null)` on delete of that id.
- [ ] **Step 4: Full unit tests + lint, expect PASS.**
- [ ] **Step 5: Commit** `feat(share): "Send to Tsukaima" share target`.

### Task 3: API, DTOs, `prompt.submit` surface, settings flag, smoke step

**Files:**
- Modify: `app/src/main/kotlin/app/tsukaima/data/api/ApiModels.kt`, `app/src/main/kotlin/app/tsukaima/data/api/HermesApi.kt`, `app/src/main/kotlin/app/tsukaima/data/HermesRepository.kt`, `app/src/main/kotlin/app/tsukaima/data/gateway/GatewaySession.kt`, `app/src/main/kotlin/app/tsukaima/data/prefs/SettingsStore.kt`, `scripts/e2e_smoke.py`, `docs/protocol/README.md` (§4 addendum: the two routes and the `surface`/`voice_context` params)
- Test: `app/src/test/kotlin/app/tsukaima/data/api/HermesApiVoiceLiveTest.kt` (MockWebServer, like the existing `HermesApi*Test`), `GatewaySessionTest` (existing file) gains `submitCarriesSurfaceAndVoiceContext`, `SettingsStoreTest` round trip of `liveVoice`.

**Interfaces:**
- Produces:
  ```kotlin
  @Serializable data class VoiceLiveStatusDto(val ok: Boolean = true, val mode: String = "chained", val available: Boolean = false, val reason: String? = null, val model: String = "", val voice: String = "")
  @Serializable data class VoiceLiveSessionDto(val ok: Boolean, val session: Session? = null, val transport: Transport? = null) { @Serializable data class Session(val id: String = ""); @Serializable data class Transport(val type: String = "", val sdp: String = "") }
  suspend fun HermesApi.voiceLiveStatus(): VoiceLiveStatusDto          // GET api/audio/voice-live/status, managed = true
  suspend fun HermesApi.voiceLiveSession(sdp: String, history: JsonArray?): VoiceLiveSessionDto   // POST, transcribeClient (60 s), body {"sdp": sdp, "history": history?}
  suspend fun HermesRepository.voiceLiveStatus(): Result<VoiceLiveStatusDto>; suspend fun voiceLiveSession(sdp: String, history: JsonArray?): Result<VoiceLiveSessionDto>
  GatewaySession.submit(…, queued: Boolean = false, surface: String? = null, voiceContext: String? = null)   // puts "surface" and "voice_context" when non-null
  Settings.liveVoice: Boolean = true   // key "live_voice"
  ```
- [ ] **Step 1: Failing tests.** `statusParsesTheServerShape` (the exact JSON of spec §2.1 incl. `reason: null`); `sessionPostSendsSdpByteExactAndParsesAnswer` (body JSON `sdp` equals `"v=0\r\n…\r\n"` unchanged, `history` present when given, absent when null; response parsed); `sessionErrorsKeepDetail` (503 → `ApiException` with the `detail` text; 502 likewise); `submitCarriesSurfaceAndVoiceContext` (params contain `"surface":"voice-live"` and `"voice_context"`, no `voice_turn`, no `queued`); `liveVoiceRoundTrip`.
- [ ] **Step 2: Run, expect failure.**
- [ ] **Step 3: Implement.** Smoke: add `voice_live_part(token)`: GET status must be 200 with `ok` true (a 404 → `step("voice-live status: route absent (older Hermes)")` and return); when `mode == "gpt-live"`: POST with `{"sdp":"  "}` must be 400; POST with `{"sdp":"v=0\r\n"}` must be 502 or 503; `step(...)` lines name the outcome. Call it after `speak_part` in `main()`.
- [ ] **Step 4: Tests + lint PASS; run `bash scripts/e2e-smoke.sh` (must print PASS; log the voice-live lines in the report).**
- [ ] **Step 5: Commit** `feat(api): voice-live status/session routes, voice-live submit surface, liveVoice setting`.

### Task 4: `domain/livevoice` utilities

**Files:**
- Create: `app/src/main/kotlin/app/tsukaima/domain/livevoice/LiveEvent.kt`, `LiveTranscript.kt`, `LiveText.kt`, `StopPhrases.kt`, `LiveTransport.kt`
- Test: `app/src/test/kotlin/app/tsukaima/domain/livevoice/LiveEventTest.kt`, `LiveTranscriptTest.kt`, `LiveTextTest.kt`, `StopPhrasesTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class LiveSpeaker { User, Assistant }
  data class LiveFragment(val speaker: LiveSpeaker, val text: String, val startMs: Long, val endMs: Long)
  sealed interface LiveEvent {
      data class Started(val sessionId: String?) : LiveEvent
      data class Transcript(val fragment: LiveFragment) : LiveEvent
      data class Delegation(val id: String) : LiveEvent
      data class Error(val message: String, val code: String?) : LiveEvent
      data class Closed(val reason: String, val usageSeconds: Double?) : LiveEvent
      data class Unknown(val type: String) : LiveEvent
      companion object { fun parse(raw: String): LiveEvent?  /* null on invalid JSON or no "type" */ }
  }
  class LiveTranscript(private val keep: Int = 2_000, private val trimTo: Int = 1_500) {
      fun append(f: LiveFragment); val size: Int
      fun contextWindow(windowMs: Long = 300_000, maxFragments: Int = 80): List<LiveFragment>
  }
  data class DelegationPrompt(val prompt: String, val context: String)
  @Serializable data class LiveHistoryMessage(val type: String = "message", val role: String, val content: List<Part>) { @Serializable data class Part(val type: String, val text: String) }
  object LiveText {
      fun chunkForCommentary(text: String, limit: Int = 1_400): List<String>
      fun delegationPrompt(fragments: List<LiveFragment>): DelegationPrompt   // turns merged by speaker; "User: …" / "Voice assistant: …" lines; prompt = last user turn, else context.takeLast(400)
      fun liveHistory(items: List<ChatItem>, maxMessages: Int = 24, maxChars: Int = 6_000): List<LiveHistoryMessage>   // UserMessage/AssistantMessage with non-blank text only, newest kept, each text ≤1,200 chars, whitespace collapsed
  }
  object StopPhrases { fun isStopCommand(utterance: String): Boolean }
  class LiveStartException(message: String, cause: Throwable? = null) : Exception(message, cause)
  interface LiveTransport { /* exactly the spec §2.4 block */ }
  ```
- [ ] **Step 1: Failing tests** (assert the examples of spec §2.1 verbatim): `parseStartedTranscriptDelegationErrorClosed` (one JSON each; `session.closed` with `usage.seconds: 84.2`; missing `end_ms` → 0); `parseGarbageIsNull`; `parseUnknownTypeIsUnknown`; `transcriptTrimsTo1500After2000`; `contextWindowKeepsFiveMinutesAndAtMost80`; `chunkShortTextIsOneChunk`, `chunkSplitsOnSentenceBoundariesUnderLimit`, `chunkHardSplitsAnOverlongSentence`, `chunkBlankIsEmpty`; `delegationPromptMergesTurnsAndTakesLastUserTurn` (fragments U"what", U" time", A"let me check", U"in Tokyo" → prompt "in Tokyo", context "User: what time\nVoice assistant: let me check\nUser: in Tokyo"); `delegationPromptWithoutUserTurnFallsBackToContextTail`; `liveHistoryKeepsNewest24Within6000AndMapsRoles` (user → `input_text`, assistant → `output_text`, tools skipped); `stopAloneIsAStop` ("Stop.", "hey hermes, stop", "that's all!", "отбой" → false); `stopPhraseInsideARequestIsNotAStop` ("stop the container", "how do I stop a process" → false).
- [ ] **Step 2: Run, expect failure.** - [ ] **Step 3: Implement** (ports of `voice-live.ts` / `use-voice-live-conversation.ts` / `voice-stop-word.ts`; normalisation NFKC + lowercase + `\p{P}` → space + collapse; address prefixes stripped once).
- [ ] **Step 4: PASS.** - [ ] **Step 5: Commit** `feat(livevoice): events, transcript window, commentary chunking, stop phrases`.

### Task 5: `LiveVoiceController`

**Files:**
- Create: `app/src/main/kotlin/app/tsukaima/domain/livevoice/LiveVoiceController.kt`
- Modify: `app/src/main/kotlin/app/tsukaima/domain/voice/VoiceState.kt` (add `val live: LiveInfo? = null`, `val caption: String = ""`; `data class LiveInfo(val voice: String, val seconds: Int, val ended: String? = null)`)
- Test: `app/src/test/kotlin/app/tsukaima/domain/livevoice/FakeLiveTransport.kt`, `LiveVoiceControllerTest.kt`

**Interfaces:**
- Consumes: Task 4; `ChatState`, `AgentStatus` (`running`, `waiting`, `Tool(name)`), `ChatItem.AssistantMessage(streaming, text)`, `SpeechText.plain`, `SentenceChunker` (`feed(text)` returns new complete sentences).
- Produces:
  ```kotlin
  class LiveVoiceController(
      private val transport: LiveTransport, private val scope: CoroutineScope, private val chat: StateFlow<ChatState>,
      private val voiceName: String,
      private val send: (prompt: String, context: String) -> Boolean,   // ChatViewModel.sendLive
      private val interrupt: () -> Unit,                                // ChatViewModel.interrupt
      private val onEnded: (reason: String, seconds: Int) -> Unit,      // toast + teardown in ChatVoice
      private val now: () -> Long = { System.nanoTime() / 1_000_000 },
  ) {
      val state: StateFlow<VoiceState>
      fun start(history: List<LiveHistoryMessage>)   // launches: transport.start → await Started (≤ START_TIMEOUT_MS 20_000) → timer; failure → onEnded("start_failed", 0)
      fun stop(reason: String = "close_requested")   // sends session.close, waits ≤ CLOSE_TIMEOUT_MS 15_000 for Closed, then transport.close(); idempotent
      fun setMuted(muted: Boolean); fun tapIndicator(); fun rebased(itemsBefore: List<ChatItem>)   // same contract as VoiceController.rebased (keep startIndex right)
      companion object { const val START_TIMEOUT_MS = 20_000L; const val CLOSE_TIMEOUT_MS = 15_000L; const val UTTERANCE_SETTLE_MS = 1_500L; const val SUBMIT_SETTLE_GRACE_MS = 15_000L; const val TICK_MS = 200L }
  }
  ```
  Outgoing JSON (exact): `{"type":"session.commentary.append","event_id":"say_<n>","delegation_id":<id|null>,"content":…}`, `session.thinking.append` with `think_<n>`, `session.instructions.append` with `instr_<n>`, `{"type":"session.close"}`, `{"type":"session.input_audio.mute","event_id":"mute_<n>"}` / `unmute`.
- [ ] **Step 1: Failing tests** with `FakeLiveTransport` (a `MutableSharedFlow<LiveEvent>`, recorded `sent: List<JsonObject>`, controllable `speaking`, `startResult: Result<Unit>`), `runTest` + `StandardTestDispatcher`, rig like `VoiceControllerTest`:
  - `startWithoutStartedTimesOutAndClosesTransport` (advance 20 s → `transport.closed`, `onEnded("start_failed", 0)`).
  - `startedThenDelegationSubmitsPromptAndContext` (events U"what time is it" then Delegation "d1" → `send("what time is it", "User: what time is it")`, phase Thinking, state.live.voice == "marin").
  - `replyIsSpokenSentenceBySentenceThenTailOnSettle` (status Replying + streaming item "It is noon. In Tokyo" → one `say_1` "It is noon."; item not streaming + Idle → `say_2` "In Tokyo"; delegation cleared; `revealed[id] == text.length`).
  - `toolStartIsAThinkingAppendOncePerTool` (status Tool("terminal") twice → one `think` "Hermes is working: terminal. Not done yet.").
  - `turnWithoutReplySaysSoQuietly` (turn observed running then Idle, no reply → `think` "Hermes finished that request without a spoken result.").
  - `newerDelegationStopsFeedingTheOlderOne` (d1 submitted, d2 arrives → `interrupt()` not called when idle; a reply after d2 is attributed to d2 only; nothing spoken with delegation_id d1 after d2).
  - `delegationWhileRunningInterruptsFirst` (status Replying, Delegation → `interrupt()` called before `send`).
  - `stopPhraseEndsInsteadOfDelegating` (U"stop" settles 1.5 s → `session.close` sent, no `send`; also Delegation whose prompt is "stop" → same).
  - `waitingCardIsSpokenOnce` (status Approval during d1 → one `say` "Hermes needs you to answer something on screen." + one `think`).
  - `closedEventEndsWithUsage` (Closed("closed", 84.2) → `onEnded("closed", 84)`, phase Idle, `live.ended == "closed"`).
  - `errorIsShownNotFatal` (Error("x", null) → `state.error == "Live voice: x"`, session still up; `context_injection_incomplete` ignored).
  - `muteAndTapAreForwarded` (setMuted → `transport.muted` + mute event, phase Muted; tapIndicator → `instr_1` with the nudge text).
  - `timerCountsSeconds` (advance 61 s after Started → `live.seconds == 61`).
  - `captionShowsTheVoiceUtterance` (A"Sure," A" checking." → caption "Sure, checking.").
- [ ] **Step 2: Run, expect failure.** - [ ] **Step 3: Implement** per spec §2.6 (single-threaded scope; a `tick` job at 200 ms only while a delegation is active; reply = first `AssistantMessage` at index ≥ items.size recorded at submit).
- [ ] **Step 4: PASS (and `VoiceControllerTest` unchanged).** - [ ] **Step 5: Commit** `feat(livevoice): delegation-loop controller`.

### Task 6: WebRTC transport, audio route, dependency, ABI

**Files:**
- Modify: `gradle/libs.versions.toml` (`streamWebrtc = "1.3.10"`, `stream-webrtc-android`), `app/build.gradle.kts` (`implementation(libs.stream.webrtc.android)`; `defaultConfig.ndk { abiFilters += listOf("arm64-v8a") }`), `app/src/main/AndroidManifest.xml` (`MODIFY_AUDIO_SETTINGS`)
- Create: `app/src/main/kotlin/app/tsukaima/voice/live/WebRtc.kt` (`object WebRtc { fun factory(context): PeerConnectionFactory }`, initialised once with `JavaAudioDeviceModule.builder(context).setUseHardwareAcousticEchoCanceler(true).setUseHardwareNoiseSuppressor(true).createAudioDeviceModule()`), `WebRtcLiveTransport.kt`, `LiveAudioRoute.kt`
- Test: none at the JVM level (native library); `SdpOfferTest` is not possible. The reviewer verifies against `voice-live.ts` line by line.

**Interfaces:**
- Produces: `class WebRtcLiveTransport(context: Context, private val exchange: suspend (sdp: String, history: JsonArray?) -> String) : LiveTransport`; `class LiveAudioRoute(context: Context, onFocusLost: () -> Unit) { fun acquire(); fun release() }` (spec §2.4). `start`: `LiveAudioRoute.acquire()` → factory → audio source with the four `goog*` constraints → track → `addTrack(track, listOf("tsukaima"))` → `createDataChannel("oai-events", DataChannel.Init())` → `createOffer` (constraints `OfferToReceiveAudio=true`, `OfferToReceiveVideo=false`) → `setLocalDescription` → await `IceGatheringState.COMPLETE` ≤ 10 s (continue on timeout) → `exchange(localDescription.description, history)` (the description string is passed **unmodified**) → `setRemoteDescription(ANSWER)`. Data-channel `onMessage` → `LiveEvent.parse` → channel. `onTrack` remote audio: `AudioTrack.addSink` for the speaking probe (threshold 1,536 / 32,767 peak, 100 ms windows, 4 quiet windows → false) — if `addSink` is unavailable on the remote track at runtime, `speaking` stays false. `onConnectionChange(FAILED|DISCONNECTED)` / data-channel `onStateChange(CLOSED)` → emit `LiveEvent.Closed("connection_lost", null)` once. `close()`: stop the track, dispose source/track/channel/peer, `LiveAudioRoute.release()`, complete `events`.
- [ ] **Step 1:** add the dependency + ABI filter; `gradle :app:assembleDebug` compiles. - [ ] **Step 2:** implement the three files. - [ ] **Step 3:** full unit tests + lint PASS; `aapt dump badging` on the debug APK shows `native-code: 'arm64-v8a'` only; note the debug APK size in the report. - [ ] **Step 4: Commit** `feat(livevoice): WebRTC transport on stream-webrtc-android (arm64 only)`.

### Task 7: Chat wiring — engine choice, `sendLive`, lifecycle

**Files:**
- Modify: `app/src/main/kotlin/app/tsukaima/ui/chat/ChatBackend.kt` (+ `suspend fun voiceLiveStatus(): Result<VoiceLiveStatusDto>`, `fun liveTransport(): LiveTransport`, `val liveVoiceEnabled: Boolean` from settings, `fun startLiveService()`/`stopLiveService()` no-ops until Task 9, `val liveVoiceEndRequests: Flow<Unit>`), `ContainerChatBackend`, `app/src/main/kotlin/app/tsukaima/di/AppContainer.kt` (`fun liveTransport() = WebRtcLiveTransport(appContext) { sdp, history -> repository.voiceLiveSession(sdp, history).getOrThrow().transport?.sdp ?: throw LiveStartException("no answer SDP") }`, `val liveVoiceEndRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)`), `app/src/main/kotlin/app/tsukaima/ui/chat/ChatVoice.kt` (`open(mode, onApprovalNeeded, listen, engine: VoiceEngine)`; `sealed interface VoiceEngine { data object Chained; data class Live(val voice: String) }`; a live open creates `LiveVoiceController(backend.liveTransport(), …)`, mirrors its state, `onEnded` → toast per spec §2.5 + `backend.stopLiveService()`; `close()` stops whichever controller), `app/src/main/kotlin/app/tsukaima/ui/chat/ChatViewModel.kt` (`openVoice` becomes: if `backend.liveVoiceEnabled` → `withTimeoutOrNull(4_000) { backend.voiceLiveStatus() }` cached 60 s → `Live(voice)` when `mode == "gpt-live" && available`, else `Chained`; `fun sendLive(prompt: String, context: String): Boolean` = `startTurn(live, prompt, emptyList(), fromComposer = false, surface = "voice-live", voiceContext = context)` never queued; start failure of the live engine → toast "Live voice couldn’t start — using phone voice" and `voice.open(Chained)`; collects `liveVoiceEndRequests` → `closeVoice()`), `app/src/main/kotlin/app/tsukaima/ui/chat/ChatScreen.kt` (`ON_STOP` closes voice only when `state.voice?.live == null`; a `DisposableEffect` on the screen's exit closes voice always).
- Test: `app/src/test/kotlin/app/tsukaima/ui/chat/ChatViewModelLiveVoiceTest.kt`: `liveEngineWhenServerSaysGptLiveAndAvailable`, `chainedWhenUnavailableOrChainedOrTimeoutOrSettingOff` (four cases), `sendLiveIsNeverQueued` (status Replying with `queuesSends` true → the submit params carry `surface` and no `queued`), `liveStartFailureFallsBackToChainedWithToast`, `endRequestClosesVoice`.
- [ ] Steps: failing tests → run → implement → full tests + lint → commit `feat(chat): live voice engine selection, voice-live turns, end requests`.

### Task 8: Voice screen and settings UI for live mode

**Files:**
- Modify: `app/src/main/kotlin/app/tsukaima/ui/voice/VoiceScreen.kt`, `app/src/main/kotlin/app/tsukaima/ui/voice/VoiceStatusLine.kt` (`voiceLine(phase, status, mode, transcribing, live: LiveInfo? = null)` — live lines of spec §2.7, time as `m:ss`), `app/src/main/kotlin/app/tsukaima/ui/settings/SettingsScreen.kt` + `SettingsViewModel.kt` + its `SettingsUiState` (`liveVoice: Boolean`, `liveVoiceCaption: String`; `loadVoiceLiveStatus()` on the Voice section's first composition; `setLiveVoice(Boolean)`), `app/src/main/kotlin/app/tsukaima/ui/chat/ChatUiState.kt` only if needed.
- Test: `VoiceScreenUiTest` (existing or new): `liveModeShowsVoiceChipTimerAndCaption` ("Live · marin", "Listening · 1:24", caption text; no "Voice language" button); `endedLiveShowsReason`; `SettingsScreenUiTest`: the switch and the four captions.
- [ ] Steps: failing tests → implement → full tests + lint → commit `feat(ui): live voice screen and setting`.

### Task 9: Foreground `LiveVoiceService`

**Files:**
- Create: `app/src/main/kotlin/app/tsukaima/notifications/LiveVoiceService.kt`
- Modify: `AndroidManifest.xml` (service `foregroundServiceType="microphone|mediaPlayback"`, permissions `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`), `Channels.kt` if a channel id is needed (reuse the agent channel), `AppContainer`/`ContainerChatBackend.startLiveService()/stopLiveService()`, `ChatVoice` calls them around the live controller's life.
- Behaviour: `startForeground` with type `FOREGROUND_SERVICE_TYPE_MICROPHONE or FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` (API 29–33: the type ints exist from API 29 for microphone? — use `ServiceCompat.startForeground` with the types guarded by SDK level as `GatewayService` does); notification "Live voice with Hermes" / "<m:ss> · tap to return" (updated every 10 s from `AppContainer.liveVoiceSeconds: StateFlow<Int>` written by `ChatVoice`), content intent = MainActivity (`EXTRA_OPEN_SCREEN=chat`), action **End** → `ACTION_END` on the service → `liveVoiceEndRequests.tryEmit(Unit)`; `onDestroy` also emits End (the system killed the service → the session must not run unattended).
- Test: `LiveVoiceServiceTest` (Robolectric): starting posts the notification with the End action; `ACTION_END` emits on `liveVoiceEndRequests`.
- [ ] Steps: failing test → implement → full tests + lint → commit `feat(livevoice): foreground service keeps the session alive with the screen off`.

### Task 10: Docs and facts

**Files:**
- Modify: `README.md` (features: share target, live voice; "64-bit ARM phones only"; size), `CHANGELOG.md` ("Unreleased → Added": share target, live voice; "Changed": arm64-only, APK size), `docs/protocol/README.md` (if Task 3 left anything), `site/index.html` fact card: the APK size line (measure the **release** APK is the controller's job at release time; the implementer writes "about 18 MB" and the controller corrects it), plus one feature line for live voice in the voice section and one for share, keeping `bash scripts/site/check.sh` green (no new third-party requests).
- [ ] Steps: edit → `bash scripts/site/check.sh` PASS → commit `docs: share target, live voice, arm64 note`.
