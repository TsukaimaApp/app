# Voice Mode + Assistant Slot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tsukaima v0.6.0: a full-screen in-app voice mode (speak → Hermes answers aloud, approvals still tapped) entered from the composer's mic, and Tsukaima as the Android assistant app (long-press home / corner swipe) showing one spoken exchange in a sheet over the current app.

**Architecture:** A pure `VoiceController` (domain, JVM-tested with fakes) turns speech-input events, speech-output progress and the chat's `ChatState` into a `VoiceState` (phase, live partial text, how much of each reply has been spoken). Android adapters wrap `SpeechRecognizer` and `TextToSpeech`. The in-app screen is an overlay inside `ChatScreen` that shares the `ChatViewModel` (the transcript *is* the session); the assistant slot is a `VoiceInteractionService` whose session launches a translucent `AssistActivity` hosting its own `ChatViewModel` + `VoiceController` on a dedicated "assistant" session.

**Tech Stack:** Kotlin 2.2, Compose BOM 2025.10, Material 3, `android.speech.SpeechRecognizer` (on-device recognizer on API 31+ when available), `android.speech.tts.TextToSpeech`, `android.service.voice.VoiceInteractionService`, `android.app.role.RoleManager`. No new dependencies.

**Spec:** `design/handoff_voice/README.md` (screens, geometry, timings, copy) + `design/handoff_voice/components/*.jsx.txt` (reference values) + `design/handoff_voice/prototype/voice-screen.jsx` (phase → status-line mapping, demo flow). App conventions: `design/HANDOFF.md`, `docs/superpowers/2026-10-20-build-rulings.md`.

## Global Constraints

- Everything in `docs/superpowers/plans/2026-10-20-tsukaima-android.md` → Global Constraints still applies (tokens, 1px = 1dp, no snapping, 44dp hit targets, accent usage, serif only for status/wordmark/titles, motion values, build/test commands, local Hermes only, commit trailer).
- **Never run two Gradle builds in one working tree.** Tasks run sequentially. Full verification: `cd ~/projects/tsukaima && export ANDROID_HOME=$HOME/android-sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 && ~/gradle/current/bin/gradle --rerun-tasks testDebugUnitTest lintDebug assembleRelease`.
- Handoff values are final: `VoiceIndicator` disc = 0.62·S, bars width max(3, 0.04·S), gap max(3, 0.035·S), heights disc × (0.18 + a·0.34) for a = [.3,.65,1,.65,.3], scaleY .35→1→.35 period 1.4 s listening / 0.9 s speaking, stagger 130 ms; rings 1.5dp `accentSelected` scale .72→1.3 opacity .7→0 over 2.4 s, second offset 1.2 s; thinking dot 0.07·S opacity pulse 2 s, disc breathes .96↔1.04 over 2.4 s; idle `mic` glyph 0.2·S accent; muted `mic_off` 0.2·S ink3 on `surfaceSunken`. Flat colour only.
- Status line in voice mode: 16sp Source Serif 4 italic 500, `ink2`, 7dp dot 8dp before text, centred. Flair rotates every 2.6 s (`ThinkingFlair.HOLD_MS`).
- Controls: Mute 56dp circle `surfaceRaised` + 1dp hairline, icon 24dp `ink1`; End 64dp `ink1` fill, `close` glyph in `surfaceRaised` (= cream-0 `#FFFDF7` in light exactly; the handoff's literal cream-0 would vanish on the dark theme's light ink1 — ruling, record in Task 8); Keyboard 56dp like Mute. Gaps 28dp; row padding 12dp top / 20dp sides / 28dp bottom.
- Assist sheet: `surfaceRaised`, top radii 28dp, float shadow, padding 10/20/10, 10dp gaps; handle 32×4dp hairline; header mark 18dp + "Tsukaima" serif 600 17sp; body `VoiceIndicator` 72 + status line 14sp; footer ghost Sm button "Continue in Tsukaima"; gesture bar 108×4dp `ink1` at 40 %. Scrim rgba(39,34,32,.38) → `ink1Light.copy(alpha = .38f)` (same in dark).
- **Voice never bypasses approval.** Approvals, clarify questions and masked prompts are answered only by tapping their cards; in the assistant sheet they are not handled at all (the app is opened on the session instead).
- The handoff's open points are decided as follows (record in the rulings doc, Task 8): STT = platform `SpeechRecognizer` (on-device on API 31+ when `isOnDeviceRecognitionAvailable`, else the default recogniser), TTS = platform `TextToSpeech` (system default engine/voice), no hotword, barge-in = tapping the indicator while speaking stops speech and listens, a light haptic (`HapticFeedbackType.Confirm`) when listening starts, the assistant sheet is not shown over the lock screen.
- Copy (sentence case, no exclamation marks): "Listening…", "Speaking", "Muted", "Tap to speak", "Waiting for your approval" (the chat status's own label for amber states), "Microphone access is needed for voice mode", "Speech recognition isn’t available on this device", "That needs your approval. I’ve opened it in Tsukaima.", "Continue in Tsukaima", "Use as device assistant", "Assistant".

## Review Focus

1. The recogniser returns an empty or whitespace-only final result (a cough, silence timeout `ERROR_SPEECH_TIMEOUT` / `ERROR_NO_MATCH`): nothing is sent, listening simply restarts. Test: `VoiceControllerTest.emptyFinalResultIsNotSent` (Task 3).
2. The reply streams a fenced code block or a `MEDIA:` line: speech skips the code and the media path (never reads `rm -rf` aloud character by character), the transcript still shows them. Test: `SpeechTextTest.plainSkipsCodeBlocksAndMediaLines` (Task 2).
3. The user presses End while a reply is being spoken and a turn is still running: speech stops at once, the chat keeps streaming the turn, no `session.interrupt` is sent. Test: `VoiceControllerTest.stopDuringSpeakingLeavesTheTurnRunning` (Task 3).
4. The socket drops mid-conversation (`ChatState.status == Offline`): the mic is released (no recogniser restart loop), the line turns red, and listening resumes when the chat is back to Idle. Test: `VoiceControllerTest.offlineReleasesTheMicUntilIdle` (Task 3).
5. A second assistant invocation 31 minutes after the first: a new session is created instead of appending to the old one; within 30 minutes the same session is resumed. Test: `AssistSessionPolicyTest` (Task 7).

---

### Task 1: Composer mic ↔ send, voice icons

**Files:**
- Modify: `app/src/main/kotlin/labs/suchattai/tsukaima/ui/components/Icons.kt` (add `Mic("mic")`, `MicOff("mic_off")`, `Keyboard("keyboard")` → `Icons.Rounded.Mic` / `MicOff` / `Keyboard`, in a `/** Not in the handoff (v0.6.0): voice mode. */` group)
- Modify: `app/src/main/kotlin/labs/suchattai/tsukaima/ui/chat/components/Composer.kt`
- Modify: `app/src/main/kotlin/labs/suchattai/tsukaima/ui/chat/ChatScreen.kt` (`ChatActions.onVoice: () -> Unit = {}`; pass `onVoice = actions.onVoice` to `Composer`)
- Test: `app/src/test/kotlin/labs/suchattai/tsukaima/ui/chat/components/ComposerActionTest.kt`

**Interfaces:**
- Produces: `enum class ComposerAction { Voice, Send, Stop }` and `fun composerAction(value: String, hasAttachments: Boolean, enabled: Boolean, running: Boolean, voiceAvailable: Boolean): ComposerAction` (top-level in `Composer.kt`): `Stop` when `running && enabled`; `Voice` when `enabled && voiceAvailable && value.isBlank() && !hasAttachments`; else `Send`.
- Produces: `Composer(..., onVoice: (() -> Unit)? = null)`: when the action is `Voice` the 44dp slot is `IconButton44(IconName.Mic, "Voice", filled = true)`; `Send` keeps today's behaviour (filled only when `canSend`).

- [ ] **Step 1: Write the failing test**

```kotlin
class ComposerActionTest {
    @Test fun emptyFieldOffersVoice() = assertEquals(ComposerAction.Voice, composerAction("", false, true, false, true))
    @Test fun textSwapsToSend() = assertEquals(ComposerAction.Send, composerAction("hi", false, true, false, true))
    @Test fun attachmentsSwapToSend() = assertEquals(ComposerAction.Send, composerAction("", true, true, false, true))
    @Test fun runningIsStop() = assertEquals(ComposerAction.Stop, composerAction("", false, true, true, true))
    @Test fun noVoiceHandlerIsSend() = assertEquals(ComposerAction.Send, composerAction("", false, true, false, false))
    @Test fun disabledIsSend() = assertEquals(ComposerAction.Send, composerAction("", false, false, false, true))
}
```

- [ ] **Step 2: Run** `gradle testDebugUnitTest --tests '*ComposerActionTest*'` → FAIL (unresolved).
- [ ] **Step 3: Implement** the three icons, `composerAction`, the `onVoice` parameter (voiceAvailable = `onVoice != null`), and a preview row with the mic state.
- [ ] **Step 4: Run** the test → PASS; `gradle assembleDebug` green.
- [ ] **Step 5: Commit** `feat(composer): mic in the empty composer, voice icons`.

---

### Task 2: Speech text — plain text and sentence chunks

**Files:**
- Create: `app/src/main/kotlin/labs/suchattai/tsukaima/domain/voice/SpeechText.kt`
- Test: `app/src/test/kotlin/labs/suchattai/tsukaima/domain/voice/SpeechTextTest.kt`

**Interfaces:**
- Produces: `object SpeechText { fun plain(markdown: String): String }` — what TTS reads for a reply: fenced code blocks removed (whole block, fence to fence; an unterminated fence removes to the end), `MEDIA:` lines removed, inline code keeps its text without backticks, `**`/`*`/`_`/`~~` markers removed, `[label](url)` → `label`, bare `http(s)://` URLs removed, `#` headings and `>`/`-`/`*`/`1.` list prefixes stripped, runs of blank lines collapsed to one, result trimmed.
- Produces: `class SentenceChunker { fun feed(text: String): List<String>; fun flush(): String? }` — call `feed` with the reply's *full* text so far (idempotent on prefix: it tracks how many chars it has already emitted); returns the newly complete sentences. A sentence ends at `.`, `!`, `?`, `…` or `:` followed by whitespace, or at a `\n`; a boundary counts only once at least one character follows it (so "3.14" and a trailing "." that may continue are not cut). `flush()` returns whatever is left (trimmed, null when blank) and resets.
- Produces: `data class SpokenChunk(val text: String, val end: Int)` and `fun SentenceChunker.feedPositions(text: String): List<SpokenChunk>` where `end` is the index in the **original** text up to which the chunk reaches — the reveal cursor for the transcript. Implement `feed` on top of `feedPositions`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class SpeechTextTest {
    @Test fun plainSkipsCodeBlocksAndMediaLines() {
        val md = "Done.\n\n```bash\nrm -rf build\n```\nMEDIA:/tmp/out.png\nSee **this** [log](http://x/y) and `uname -a`."
        assertEquals("Done.\nSee this log and uname -a.", SpeechText.plain(md))
    }
    @Test fun plainStripsListAndHeadingMarkers() = assertEquals("Steps\nfirst\nsecond", SpeechText.plain("# Steps\n- first\n1. second"))
    @Test fun chunkerEmitsOnlyConfirmedSentences() {
        val c = SentenceChunker()
        assertEquals(emptyList<String>(), c.feed("It failed at 3."))
        assertEquals(listOf("It failed at 3.14 seconds."), c.feed("It failed at 3.14 seconds. The"))
        assertEquals(emptyList<String>(), c.feed("It failed at 3.14 seconds. The token"))
        assertEquals("The token", c.flush())
    }
    @Test fun chunkerPositionsPointIntoTheOriginal() {
        val c = SentenceChunker()
        val chunks = c.feedPositions("Yes. No.\nMaybe")
        assertEquals(listOf("Yes." to 4, "No." to 9), chunks.map { it.text to it.end })
    }
}
```

- [ ] **Step 2: Run** `--tests '*SpeechTextTest*'` → FAIL.
- [ ] **Step 3: Implement** `SpeechText.kt` (regexes; no markdown library — `domain/markdown/Markdown.kt` is a renderer, not reused here).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(voice): speech text plain-text and sentence chunking`.

---

### Task 3: VoiceController — the phase machine

**Files:**
- Create: `app/src/main/kotlin/labs/suchattai/tsukaima/domain/voice/Speech.kt` (interfaces + events)
- Create: `app/src/main/kotlin/labs/suchattai/tsukaima/domain/voice/VoiceState.kt`
- Create: `app/src/main/kotlin/labs/suchattai/tsukaima/domain/voice/VoiceController.kt`
- Test: `app/src/test/kotlin/labs/suchattai/tsukaima/domain/voice/VoiceControllerTest.kt` (with `FakeSpeechInput`, `FakeSpeechOutput` defined in the test file; `StandardTestDispatcher` + `runTest`, like `ChatViewModelTest`)

**Interfaces:**
- Produces (`Speech.kt`):
  ```kotlin
  sealed interface SpeechEvent { data class Partial(val text: String) : SpeechEvent; data class Final(val text: String) : SpeechEvent; data class Error(val message: String, val retry: Boolean) : SpeechEvent }
  interface SpeechInput { val available: Boolean; fun listen(): Flow<SpeechEvent> }   // one listening run; completes when the recogniser ends
  interface SpeechOutput { val available: Boolean; suspend fun speak(text: String); fun stop() }   // speak suspends until spoken; cancellation stops it
  ```
- Produces (`VoiceState.kt`):
  ```kotlin
  enum class VoicePhase { Listening, Thinking, Speaking, Approval, Muted, Idle }
  enum class VoiceMode { InApp, Assist }
  data class VoiceState(
      val phase: VoicePhase = VoicePhase.Listening,
      val muted: Boolean = false,
      val heard: String = "",                 // partial recognition shown as a live user bubble; cleared on Final
      val revealed: Map<String, Int> = emptyMap(),   // assistant item id → chars spoken so far (reveal cursor)
      val startIndex: Int = 0,               // items before this index are not shown in voice mode
      val error: String? = null,             // "Speech recognition isn’t available on this device" etc.
  )
  fun voicePhase(mode: VoiceMode, muted: Boolean, listening: Boolean, speaking: Boolean, status: AgentStatus): VoicePhase
  ```
  `voicePhase`: `status` Approval/Question/Secret → `Approval`; `speaking` → `Speaking`; `status.running` or Compacting → `Thinking`; `listening && muted` → `Muted`; `listening` → `Listening`; else `Idle` (InApp never rests in Idle except Offline/Stopped: when `status` is Offline or Stopped → `Idle`).
- Produces (`VoiceController.kt`):
  ```kotlin
  class VoiceController(
      private val input: SpeechInput, private val output: SpeechOutput,
      private val scope: CoroutineScope, private val chat: StateFlow<ChatState>,
      private val send: (String) -> Boolean,          // ChatViewModel.sendText; false = not sent (offline)
      private val mode: VoiceMode,
      private val onApprovalNeeded: () -> Unit = {},  // Assist only: fired once per request id
  ) {
      val state: StateFlow<VoiceState>
      fun start()        // startIndex = chat.value.items.size; begins listening (InApp) or listening (Assist, first exchange)
      fun stop()         // stops input + output, cancels the loop; the chat turn keeps running
      fun setMuted(muted: Boolean)
      fun tapIndicator() // Speaking → stop speech and listen (barge-in); Idle (Assist) → listen again; else no-op
  }
  ```
  Behaviour: while the phase would be Listening and the chat is writable (status not Offline/Stopped and not running), `input.listen()` is collected; `Partial` → `heard`; `Final` with non-blank text → `send(text)`, `heard = ""`, and listening pauses until the turn ends; blank Final/retryable Error → relisten; non-retryable Error → `error` set, listening off. Replies: the controller watches `chat.items` after `startIndex`; for every `AssistantMessage` it feeds the **raw** `text` to a per-item `SentenceChunker.feedPositions` and speaks `SpeechText.plain(chunk.text)` (skipping chunks whose plain text is blank), so `end` indexes the transcript text; when `streaming` turns false, `flush()`. Chunks are spoken in order through one queue coroutine; before each `output.speak`, `revealed[id] = chunk.end`. After the turn ends (status not running) and the queue drains → InApp: listen again; Assist: `Idle`. A new unanswered request card (Approval/Clarify/SecretPrompt) → phase Approval; in Assist mode speak "That needs your approval. I’ve opened it in Tsukaima." then `onApprovalNeeded()` once per id. `muted`: the listen flow is cancelled, phase Muted; unmute relistens. Offline → input released; back to Idle status → relisten (InApp).

- [ ] **Step 1: Write the failing tests** (names and the assertion each makes; a fake `ChatState` flow is driven by the test):

```kotlin
class VoiceControllerTest {
    @Test fun finalResultIsSentAndPhaseGoesThinking()   // Final("restart it") → send called with "restart it"; chat status set Thinking by test → phase Thinking
    @Test fun emptyFinalResultIsNotSent()               // Final("  ") → send not called; input.listen() collected again (listenCount == 2)
    @Test fun partialTextIsHeard()                      // Partial("rest") → state.heard == "rest"
    @Test fun streamedReplyIsSpokenBySentenceAndRevealed() // items += AssistantMessage("a1","Yes. No",streaming=true) → spoken ["Yes."], revealed["a1"]==4; then text "Yes. No." streaming=false → spoken ["Yes.","No."], revealed["a1"]==8
    @Test fun codeIsNotSpoken()                         // AssistantMessage text "Run:\n```\nrm -rf x\n```\nDone." complete → spoken == ["Run:", "Done."]
    @Test fun afterTheReplyInAppListensAgainAssistIdles() // mode InApp → phase Listening after drain; mode Assist → Idle
    @Test fun approvalCardSwitchesToApprovalPhase()     // items += Approval(answered=false), status Approval → phase Approval; InApp: nothing spoken
    @Test fun assistApprovalSpeaksAndHandsOffOnce()     // Assist: spoken contains "That needs your approval. I’ve opened it in Tsukaima."; onApprovalNeeded called once even after a second state emission
    @Test fun muteReleasesTheMic()                      // setMuted(true) → input cancelled (active == false), phase Muted; setMuted(false) → listening again
    @Test fun bargeInStopsSpeechAndListens()            // while speaking tapIndicator() → output.stop() called, phase Listening
    @Test fun stopDuringSpeakingLeavesTheTurnRunning()  // stop() → output.stop() called, no interrupt hook exists (send never called again), state loop cancelled
    @Test fun offlineReleasesTheMicUntilIdle()          // status Offline → input not active, phase Idle; status Idle → listening again
    @Test fun unavailableInputSetsError()               // input.available=false → error == "Speech recognition isn’t available on this device", no listen
}
```

- [ ] **Step 2: Run** `--tests '*VoiceControllerTest*'` → FAIL.
- [ ] **Step 3: Implement** `Speech.kt`, `VoiceState.kt`, `VoiceController.kt` (≤ 300 lines; the chunking/speaking loop in a private `Speaker` inner class if it helps). All state transitions go through one `MutableStateFlow<VoiceState>` updated on the caller's dispatcher (the chat's Main-immediate in the app).
- [ ] **Step 4: Run** → PASS. Run the whole suite once (`gradle testDebugUnitTest`) → green.
- [ ] **Step 5: Commit** `feat(voice): VoiceController phase machine with speech input/output seams`.

---

### Task 4: Android speech adapters, permission, package visibility

**Files:**
- Create: `app/src/main/kotlin/labs/suchattai/tsukaima/voice/AndroidSpeechInput.kt`
- Create: `app/src/main/kotlin/labs/suchattai/tsukaima/voice/AndroidSpeechOutput.kt`
- Create: `app/src/main/kotlin/labs/suchattai/tsukaima/voice/AudioFocus.kt`
- Modify: `app/src/main/AndroidManifest.xml` (`RECORD_AUDIO`; `<queries>` with `<intent><action android:name="android.speech.RecognitionService"/></intent>` and `<intent><action android:name="android.intent.action.TTS_SERVICE"/></intent>`)
- Modify: `app/src/main/kotlin/labs/suchattai/tsukaima/di/AppContainer.kt` (`fun speechInput(): SpeechInput = AndroidSpeechInput(appContext)`, `val speechOutput: SpeechOutput by lazy { AndroidSpeechOutput(appContext) }` — one TTS engine per process)

**Interfaces:**
- Consumes: `SpeechInput`, `SpeechOutput`, `SpeechEvent` (Task 3).
- Produces: `class AndroidSpeechInput(context: Context) : SpeechInput` — `listen()` = `callbackFlow` on Main: creates the recogniser (`createOnDeviceSpeechRecognizer` on API ≥ 31 when `isOnDeviceRecognitionAvailable`, else `createSpeechRecognizer`), `ACTION_RECOGNIZE_SPEECH` intent with `EXTRA_LANGUAGE_MODEL = LANGUAGE_MODEL_FREE_FORM`, `EXTRA_PARTIAL_RESULTS = true`, `EXTRA_MAX_RESULTS = 1`, `EXTRA_PREFER_OFFLINE = true` (API 23+); `onPartialResults` → `Partial`, `onResults` → `Final(first)` then close; `onError`: `ERROR_NO_MATCH`/`ERROR_SPEECH_TIMEOUT`/`ERROR_CLIENT`/`ERROR_RECOGNIZER_BUSY` → `Error(retry = true)`, `ERROR_INSUFFICIENT_PERMISSIONS`/`ERROR_LANGUAGE_NOT_SUPPORTED`/`ERROR_LANGUAGE_UNAVAILABLE` → `Error(retry = false)`, others retry; `awaitClose { cancel(); destroy() }`. `available = SpeechRecognizer.isRecognitionAvailable(context)` (or on-device available).
- Produces: `class AndroidSpeechOutput(context: Context) : SpeechOutput` — lazily inits `TextToSpeech`; `speak` awaits init (`CompletableDeferred`), requests transient audio focus (`AudioFocus`), `speak(text, QUEUE_ADD, params, utteranceId)` and suspends until `onDone`/`onError` of that id via `UtteranceProgressListener` (`suspendCancellableCoroutine`; cancellation → `stop()`); `available` = init succeeded (`TextToSpeech.SUCCESS`). Text over `TextToSpeech.getMaxSpeechInputLength()` is split at that length.
- Produces: `class AudioFocus(context)` — `request()` with `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` (`AudioFocusRequest` API 26+ — minSdk is 29 so no legacy path), `release()`.

- [ ] **Step 1: Implement** the three files and the manifest changes. No JVM tests (Android framework classes); keep every class free of logic beyond the mapping above.
- [ ] **Step 2: Run** `gradle assembleDebug lintDebug` → green, and `lint-results-debug.html` shows no new warnings beyond dependency notices (lint flags a missing `<queries>` entry for `SpeechRecognizer` — it must be present).
- [ ] **Step 3: Commit** `feat(voice): platform speech recogniser and TTS adapters`.

---
### Task 5: VoiceIndicator and the voice status line

**Files:**
- Create: `app/src/main/kotlin/labs/suchattai/tsukaima/ui/voice/VoiceIndicator.kt`
- Create: `app/src/main/kotlin/labs/suchattai/tsukaima/ui/voice/VoiceStatusLine.kt`
- Test: `app/src/test/kotlin/labs/suchattai/tsukaima/ui/voice/VoiceLineTest.kt`

**Interfaces:**
- Consumes: `VoicePhase`, `VoiceState` (Task 3), `AgentStatus`, `StatusTone`, `statusVisual` (`ui/chat/components/StatusLine.kt`), `ThinkingFlair`.
- Produces: `@Composable fun VoiceIndicator(phase: VoicePhase, size: Dp, modifier: Modifier = Modifier)` — geometry and timings from Global Constraints; `Approval` renders as `Idle` (handoff: `state={phase === 'approval' ? 'idle' : phase}`); animations via `rememberInfiniteTransition`, bars with `graphicsLayer { scaleY }`, rings with `graphicsLayer { scaleX/scaleY/alpha }`; a `contentDescription` of the phase ("Listening", "Thinking", "Speaking", "Muted", "Tap to speak") on the root with `Role.Button` only when `onTap != null` (`onTap: (() -> Unit)? = null` parameter).
- Produces: `data class VoiceLine(val label: String, val tone: StatusTone, val pulsing: Boolean)` and `fun voiceLine(phase: VoicePhase, status: AgentStatus, mode: VoiceMode): VoiceLine`: `Listening` → ("Listening…", Accent, pulsing); `Thinking` → (`status.label` when `status is AgentStatus.Thinking` — the flair — else `status.label` for Tool/Replying/Compacting, Accent, pulsing); `Speaking` → ("Speaking", Accent, pulsing); `Approval` → (`status.label`, Amber, static); `Muted` → ("Muted", Grey, static); `Idle` → Offline/Stopped keep `status.label` with Red, else ("Tap to speak", Grey, static) in Assist, ("Hermes is listening", Grey, static) in InApp.
- Produces: `@Composable fun VoiceStatusLine(line: VoiceLine, fontSize: TextUnit = 16.sp, modifier: Modifier = Modifier)` — centred `Row`, 7dp dot (reuse `StatusDot` by making it `internal` in `StatusLine.kt`, taking a `StatusVisual`), text `TsukaimaType.statusLine.copy(fontSize = fontSize)`, `ink2`; the label cross-fades 180 ms (`Crossfade(label, tween(180, easing = Motion.easing))`).

- [ ] **Step 1: Write the failing test**

```kotlin
class VoiceLineTest {
    @Test fun listeningPulsesAccent() = assertEquals(VoiceLine("Listening…", StatusTone.Accent, true), voiceLine(VoicePhase.Listening, AgentStatus.Idle, VoiceMode.InApp))
    @Test fun thinkingShowsTheFlair() = assertEquals("Steeping matcha…", voiceLine(VoicePhase.Thinking, AgentStatus.Thinking("Steeping matcha…"), VoiceMode.InApp).label)
    @Test fun approvalIsAmberStatic() = assertEquals(VoiceLine("Waiting for your approval", StatusTone.Amber, false), voiceLine(VoicePhase.Approval, AgentStatus.Approval, VoiceMode.InApp))
    @Test fun idleAssistSaysTapToSpeak() = assertEquals("Tap to speak", voiceLine(VoicePhase.Idle, AgentStatus.Idle, VoiceMode.Assist).label)
    @Test fun offlineStaysRed() = assertEquals(StatusTone.Red, voiceLine(VoicePhase.Idle, AgentStatus.Offline, VoiceMode.InApp).tone)
}
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** both files with light/dark previews of every phase at sizes 180 and 72. **Step 4: Run** → PASS; `assembleDebug` green.
- [ ] **Step 5: Commit** `feat(voice): VoiceIndicator orb and voice status line`.

---

### Task 6: In-app voice screen wired into the chat

**Files:**
- Create: `app/src/main/kotlin/labs/suchattai/tsukaima/ui/voice/VoiceScreen.kt`
- Modify: `app/src/main/kotlin/labs/suchattai/tsukaima/ui/chat/ChatViewModel.kt` (voice controller ownership, `sendText`)
- Modify: `app/src/main/kotlin/labs/suchattai/tsukaima/ui/chat/ChatUiState.kt` (`val voice: VoiceState? = null` — non-null while voice mode is open)
- Modify: `app/src/main/kotlin/labs/suchattai/tsukaima/ui/chat/ChatBackend.kt` (`fun speechInput(): SpeechInput`, `val speechOutput: SpeechOutput` on the interface; `ContainerChatBackend` delegates to `AppContainer`)
- Modify: `app/src/main/kotlin/labs/suchattai/tsukaima/ui/chat/ChatScreen.kt` (overlay, permission request, actions)
- Modify: `app/src/test/kotlin/labs/suchattai/tsukaima/ui/chat/ChatViewModelTest.kt` (`FakeBackend` gains a `FakeSpeechInput`/`FakeSpeechOutput`; move the fakes from Task 3's test into `app/src/test/kotlin/labs/suchattai/tsukaima/domain/voice/FakeSpeech.kt` so both tests share them)
- Test: `ChatViewModelTest` additions

**Interfaces:**
- Consumes: Tasks 1, 3, 4, 5.
- Produces (`ChatViewModel`): `fun sendText(text: String): Boolean` — like `send()` but with the given text and no attachments; returns false (and toasts) when `!writable` or no live session. `fun openVoice(mode: VoiceMode = VoiceMode.InApp, onApprovalNeeded: () -> Unit = {})` — creates `VoiceController(backend.speechInput(), backend.speechOutput, scope, chatFlow, ::sendText, mode, onApprovalNeeded)`, `start()`s it, mirrors its `state` into `ChatUiState.voice` (collect job). `fun closeVoice()` — `stop()`s and clears `voice`. `fun setVoiceMuted(Boolean)`, `fun tapVoiceIndicator()`. `onCleared` stops voice.
- Produces (`ChatActions`): `onVoice` (Task 1) now = request `RECORD_AUDIO` then `vm.openVoice()`; `onVoiceClose = vm::closeVoice`, `onVoiceMute: (Boolean) -> Unit`, `onVoiceTap`, `onVoiceKeyboard: () -> Unit` (close + focus the composer: `ChatContent` holds a `FocusRequester` on the composer field; add `focusRequester: FocusRequester? = null` to `Composer`).
- Produces: `@Composable fun VoiceScreen(state: ChatUiState, voice: VoiceState, actions: ChatActions, onModel: () -> Unit)` — layout from the handoff Screen A: top row (`IconButton44(ExpandMore, "Back to chat")`, spacer, borderless `Chip(state.model ?: "Model", trailing = ExpandMore)`, spacer, 44dp spacer); scrolling centre column (`VoiceIndicator(phase, 180.dp, onTap = actions.onVoiceTap)`, `VoiceStatusLine(voiceLine(...))`, 22dp, transcript); controls row. Transcript = `state.chat.items.drop(voice.startIndex)` rendered with the chat's composables (`UserBubble`, `AssistantMessage` with `text.take(revealed[id] ?: if (streaming) 0 else text.length)` and `streaming = true` while `revealed < text.length`, `ToolRow`, `SubagentRow`, `ApprovalCard`/`ClarifyCard`/`SecretCard` with the same `actions` as `Transcript`), plus a trailing `UserBubble(voice.heard)` while `heard.isNotBlank()`; a `LazyColumn` is not needed — `Column` + `verticalScroll` and `LaunchedEffect(items, phase) { scrollState.animateScrollTo(max) }` keeps the newest item in view. `voice.error` shows as a caption under the line in `danger`.
- Produces (`ChatContent`): `if (state.voice != null) VoiceScreen(...) else <today's column>` inside `Crossfade(targetState = state.voice != null, tween(Motion.screen, easing = Motion.easing))`; `BackHandler(enabled = state.voice != null) { actions.onVoiceClose() }`; keep-screen-on while voice is open (`LocalView.current.keepScreenOn` in a `DisposableEffect`); `LocalHapticFeedback` `Confirm` when the phase becomes `Listening`. `ChatSheets` stays below both branches so the model picker opens from the voice chip (`onModel = { actions.onMenu(ChatMenu.Model) }`).
- Permission: `rememberLauncherForActivityResult(RequestPermission())` for `Manifest.permission.RECORD_AUDIO`; granted → `vm.openVoice()`, denied → `vm.voiceDenied()` (toasts "Microphone access is needed for voice mode").

- [ ] **Step 1: Write the failing tests** (in `ChatViewModelTest`, using the existing `FakeBackend` extended with fake speech):

```kotlin
@Test fun openVoiceListensAndSendsWhatWasHeard()   // openVoice(); fakeInput.emit(Final("hello")); transport saw session.submit with "hello"; state.voice.phase == Thinking
@Test fun closeVoiceStopsSpeechAndClearsState()     // openVoice(); closeVoice() → fakeOutput.stopped, state.voice == null, no session.interrupt in transport.calls
@Test fun sendTextRefusedWhenOffline()              // connection → Disconnected; sendText("x") == false; toast "Not connected to Hermes"
```

- [ ] **Step 2: Run** `--tests '*ChatViewModelTest*'` → FAIL. **Step 3: Implement.** **Step 4: Run** the full suite → PASS; `assembleDebug lintDebug` green.
- [ ] **Step 5: Commit** `feat(voice): in-app voice mode from the composer mic`.

---

### Task 7: Assistant slot — service, session, sheet, settings row

**Files:**
- Create: `app/src/main/kotlin/labs/suchattai/tsukaima/assist/AssistSessionPolicy.kt`
- Create: `app/src/main/kotlin/labs/suchattai/tsukaima/assist/TsukaimaVoiceInteractionService.kt`, `TsukaimaSessionService.kt`, `TsukaimaVoiceSession.kt`, `TsukaimaRecognitionService.kt`
- Create: `app/src/main/kotlin/labs/suchattai/tsukaima/assist/AssistActivity.kt`, `app/src/main/kotlin/labs/suchattai/tsukaima/ui/voice/AssistOverlay.kt`
- Create: `app/src/main/res/xml/voice_interaction_service.xml`, `app/src/main/res/xml/recognition_service.xml`
- Modify: `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/themes.xml` + `values-night/themes.xml` (`Theme.Tsukaima.Assist`: `android:windowIsTranslucent=true`, `windowBackground=@android:color/transparent`, `windowNoTitle=true`, `windowAnimationStyle=@null`, `windowIsFloating=false`)
- Modify: `app/src/main/kotlin/labs/suchattai/tsukaima/data/prefs/SettingsStore.kt` (`Settings.assistSessionId: String?`, `assistSessionAt: Long?` persisted as `assist_session_id` / `assist_session_at`)
- Modify: `app/src/main/kotlin/labs/suchattai/tsukaima/ui/settings/SettingsScreen.kt` + `SettingsViewModel.kt` (a "Voice" section after Notifications with one row "Use as device assistant", subtitle "Long-press home or swipe from a corner to ask Hermes" / "Tsukaima is your assistant" when the role is held)
- Test: `app/src/test/kotlin/labs/suchattai/tsukaima/assist/AssistSessionPolicyTest.kt`, `app/src/test/kotlin/labs/suchattai/tsukaima/data/prefs/SettingsStoreTest.kt` (round-trip of the two new fields)

**Interfaces:**
- Consumes: `ChatViewModel` + `ContainerChatBackend` (Task 6), `VoiceController(mode = Assist, onApprovalNeeded)` (Task 3), `VoiceIndicator`/`VoiceStatusLine` (Task 5), `ApprovalNotifier.EXTRA_OPEN_SCREEN`/`SCREEN_CHAT`, `DeepLink.EXTRA_SESSION_ID`.
- Produces: `object AssistSessionPolicy { const val REUSE_WINDOW_MS = 30 * 60_000L; fun sessionToResume(settings: Settings, now: Long): String? }` — `assistSessionId` when `assistSessionAt != null && now - assistSessionAt < REUSE_WINDOW_MS`, else null (→ a new chat).
- Produces: `TsukaimaVoiceInteractionService : VoiceInteractionService` (empty), `TsukaimaSessionService : VoiceInteractionSessionService` (`onNewSession` → `TsukaimaVoiceSession(this)`), `TsukaimaVoiceSession : VoiceInteractionSession` (`onShow` → `startAssistantActivity(Intent(context, AssistActivity::class.java).addFlags(FLAG_ACTIVITY_NEW_TASK))` then `finish()`; `onCreateContentView` returns null), `TsukaimaRecognitionService : RecognitionService` (stub: `onStartListening` → `listener.error(SpeechRecognizer.ERROR_CLIENT)`; needed because Android 10/11 unset a voice-interaction service whose XML names no recogniser).
- Manifest: the three services (`BIND_VOICE_INTERACTION` permission + `android.voice_interaction` meta-data → `@xml/voice_interaction_service` with `sessionService`, `recognitionService`, `supportsAssist="true"`; session service `exported="true"` with `BIND_VOICE_INTERACTION`; recognition service with `android.speech.RecognitionService` intent filter and `@xml/recognition_service` meta-data), `AssistActivity` (`exported="true"`, `theme="@style/Theme.Tsukaima.Assist"`, `launchMode="singleInstance"`, `excludeFromRecents="true"`, `taskAffinity=""`, `noHistory="true"`, intent filter `android.intent.action.ASSIST` + DEFAULT as the fallback invocation path).
- Produces: `AssistActivity : ComponentActivity` — `enableEdgeToEdge`, one `ChatViewModel(ContainerChatBackend(container), storedSessionId = AssistSessionPolicy.sessionToResume(settings, now))` keyed by that id; on first composition requests `RECORD_AUDIO` (denied → toast + finish), then `vm.openVoice(VoiceMode.Assist, onApprovalNeeded = ::openApp)` (Task 6's `openVoice` gains `mode` and `onApprovalNeeded` parameters with defaults); when the chat binds a stored id, saves `assistSessionId`/`assistSessionAt = now` through the backend's `updateSettings`; `openApp()` = start `MainActivity` with `EXTRA_OPEN_SCREEN = SCREEN_CHAT` and `EXTRA_SESSION_ID = storedSessionId` (`FLAG_ACTIVITY_NEW_TASK`) then `finish()`. Back, scrim tap and a downward drag on the sheet (`swipeToDismiss` via `AnchoredDraggable` or a simple `detectVerticalDragGestures` threshold of 80dp) finish the activity. Not `setShowWhenLocked`.
- Produces: `@Composable fun AssistOverlay(state: ChatUiState, voice: VoiceState, onKeyboard, onOpenApp, onTap, onDismiss)` — handoff Screen B exactly: scrim `Box` (tap = dismiss) + bottom sheet; body shows the latest `UserMessage` after `startIndex` (or `voice.heard`), results = tool rows then `AssistantMessage` (reveal rule as Task 6, `padding = 0`); header icons `IconButton44` at 40dp… `IconButton44` is fixed 44dp — add `size: Dp = Dimens.hit` to `IconButton44` (Task 7 modifies `Buttons.kt`); mark = `R.drawable.mark` (exists), 18dp.
- Settings row: `SettingsViewModel` gets `val assistantRoleHeld: StateFlow<Boolean>` fed by the screen (`RoleManager.isRoleHeld(ROLE_ASSISTANT)` read in a `LifecycleResumeEffect`) and the row's tap launches `roleManager.createRequestRoleIntent(ROLE_ASSISTANT)` through `rememberLauncherForActivityResult(StartActivityForResult())` when `isRoleAvailable`, else `Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)` (fallback `ACTION_MANAGE_DEFAULT_APPS_SETTINGS`); `ActivityNotFoundException` → toast "Open Settings → Default apps → Digital assistant app".

- [ ] **Step 1: Write the failing tests**

```kotlin
class AssistSessionPolicyTest {
    @Test fun reusesWithinThirtyMinutes() = assertEquals("s1", AssistSessionPolicy.sessionToResume(Settings(assistSessionId = "s1", assistSessionAt = 1_000_000L), now = 1_000_000L + 29 * 60_000L))
    @Test fun startsFreshAfterThirtyMinutes() = assertNull(AssistSessionPolicy.sessionToResume(Settings(assistSessionId = "s1", assistSessionAt = 1_000_000L), now = 1_000_000L + 31 * 60_000L))
    @Test fun noSessionWithoutATimestamp() = assertNull(AssistSessionPolicy.sessionToResume(Settings(assistSessionId = "s1"), now = 5L))
}
// SettingsStoreTest: assistSessionRoundTrips — update { copy(assistSessionId = "s1", assistSessionAt = 42L) } then settings.first() has both; null removes the keys.
```

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** everything above. **Step 4: Run** full suite → PASS; `assembleDebug lintDebug` green; `aapt dump xmltree app/build/outputs/apk/debug/app-debug.apk AndroidManifest.xml | grep -A3 voice_interaction` shows the meta-data.
- [ ] **Step 5: Commit** `feat(assist): Tsukaima as the device assistant — voice sheet over the current app`.

---

### Task 8: Release 0.6.0 — docs, rulings, verification, APK

**Files:**
- Modify: `app/build.gradle.kts` (`versionCode = 13`, `versionName = "0.6.0"`), `CHANGELOG.md` (0.6.0 section: Added — in-app voice mode, assistant slot, composer mic; Changed — composer's accent circle shows mic when empty), `README.md` (a "Voice" paragraph: how to enter, what is spoken, how to set Tsukaima as the assistant, what is not supported: hotword, lock screen), `docs/superpowers/2026-10-20-build-rulings.md` (a "v0.6.0" block: every decision in Global Constraints' "open points" line, the End-glyph colour ruling, the stub recognition service, the 30-minute assist window, the overlay-not-route choice, `startAssistantActivity` over a session window), `docs/superpowers/2026-10-20-completeness-pass.md` (append "Voice (v0.6.0)": shipped + open: no hotword, no lock-screen assist, TTS reveal is per sentence not per word, STT language = device locale).

- [ ] **Step 1: Verification** — `gradle --rerun-tasks testDebugUnitTest lintDebug assembleRelease`; parse `app/build/test-results/testDebugUnitTest/*.xml` (0 failures, 0 errors; report the total); a second clean test run; `source .local/hermes-test-creds.env && scripts/e2e-smoke.sh`; `aapt dump badging app/build/outputs/apk/release/app-release.apk | head -1` shows `versionCode='13' versionName='0.6.0'`; `apksigner verify --print-certs` shows the release key SHA-256 `B4:6C:3B:F1:…:F2:35`.
- [ ] **Step 2: Commit** `chore(release): v0.6.0 — voice mode and assistant slot`, push `feat/android-app`, wait for CI green.
- [ ] **Step 3:** Deliver the APK (`SendUserFile`) + `notify` "Task Done"; board card note.
