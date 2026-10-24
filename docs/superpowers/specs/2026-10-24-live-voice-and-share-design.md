# Live voice (GPT-Live) and "Send to Tsukaima" — design

Date: 2026-10-24. Baseline: Tsukaima 0.9.3 (`697646f`), Hermes 0.22.2. Branch `feat/live-voice-and-share`.

Two deliverables, in the user's words: *"Minor: share into Tsukaima. Major: implement the live voice mode as per
attached markdown and online docs."* The attached markdown is reproduced in §2.1; the vendor docs
(developers.openai.com, "live-delegation", "voice-webrtc", "voice-server-controls") and the Hermes desktop reference
client (`apps/desktop/src/lib/voice-live.ts`, `…/hooks/use-voice-live-conversation.ts`, read from the local Hermes
checkout at `~/.hermes/hermes-agent`) are the authority wherever this document is silent.

Every decision below that the user did not make is the controller's call and is marked **(call)**.

---

## 1. Share into Tsukaima

### 1.1 Intent

Android's share sheet gets a "Tsukaima" target. A link, selected text, a screenshot, photos or a PDF from any app
lands in the chat composer as the draft text and/or pending attachments, exactly as if the user had typed and
attached them, in the last chat or a new one. Nothing is sent automatically: the user reviews and taps Send.

### 1.2 Accepted content

| Intent | MIME | What lands in the composer |
|---|---|---|
| `ACTION_SEND` | `text/plain` (`EXTRA_TEXT`) | the text as the draft (`EXTRA_SUBJECT` ignored **(call)**) |
| `ACTION_SEND` | `image/*`, `application/pdf` (`EXTRA_STREAM`) | one pending attachment; `EXTRA_TEXT` too when present |
| `ACTION_SEND_MULTIPLE` | `image/*`, `application/pdf` | one pending attachment per stream (same cap as the picker) |

Anything else (no text and no stream, an unreadable stream) ends the share with the toast "Nothing Tsukaima can
take from that share". A stream over `ChatAttachments.MAX_BYTES` (12 MB) is skipped with the toast "Skipped
<name>: over 12 MB". Content URIs are read **inside the share activity**, while the sender's grant is valid, and
copied to `cacheDir/shared/<uuid>/<displayName>`; the main app never touches the sender's URI **(call)**. The
cache folder is pruned of entries older than 24 h on every share and on app start.

### 1.3 Flow

1. `ShareActivity` (exported, translucent theme over the sender) reads the intent, copies the streams on IO, then
   shows one small card: title "Send to Hermes", a one-line summary ("Link", first 60 chars of text, "3 images",
   "report.pdf"), and two buttons: **Continue last chat** and **New chat**. Back/outside-tap cancels and deletes
   the copies. The card is skipped when there is no last chat: the share goes to a new chat directly **(call)**.
2. The choice becomes `SharedContent(text, files, target)` on `AppContainer.pendingShare` (same process) and
   starts `MainActivity` with the existing deep-link extras (`EXTRA_OPEN_SCREEN = chat`, `EXTRA_SESSION_ID` = the
   last chat's stored id or absent for a new chat), flags `NEW_TASK | SINGLE_TOP | CLEAR_TOP`.
3. The chat screen that opens consumes `pendingShare` once: draft text is appended to the composer input (a
   blank line between when the input already has text) and files are attached through the existing
   `ChatViewModel.attach(List<PickedFile>)` with a `FilePickedFile` over the cached copy. The copy is deleted when
   its upload finishes or fails for good (`ChatAttachments` drop path) **(call)**.
4. "Last chat" is `SettingsStore.lastChatId`, written by the chat whenever it binds a stored session id (new
   preference; cleared when that session is deleted) **(call)**.

Not in scope: sharing *from* a chat beyond the existing media save/open, share-to-voice, direct links to a
specific session, `text/*` other than `text/plain`.

---

## 2. Live voice (GPT-Live)

### 2.1 The server contract (the user's attached guide, verbatim where it matters)

- `GET /api/audio/voice-live/status?profile=<id>` → `{ ok, mode: "chained" | "gpt-live", available: bool, reason:
  string|null, model, voice }`. Mount the live engine only when `mode == "gpt-live" && available`; otherwise keep
  the chained mic → STT → turn → TTS loop. `reason` is safe to log, never to show verbatim.
- `POST /api/audio/voice-live/session { sdp, history? }` → `200 { ok, session: { id }, transport: { type: "webrtc",
  sdp } }`; `400` empty SDP; `503` no API key; `502` vendor rejected. Send the SDP **byte-exact** incl. trailing
  CRLF; allow ~45 s.
- WebRTC order: mic track with AGC/AEC/NS → `createDataChannel('oai-events')` **before** `createOffer` →
  `createOffer`/`setLocalDescription` → wait for ICE gathering (cap ~10 s) → POST → apply the answer. Audio comes
  back as a media track; nothing audio-related travels on the data channel. No STUN/TURN (host candidates over
  LAN/Tailscale).
- Data channel, server → client: `session.started {session.id}`, `session.input_transcript.delta` /
  `session.output_transcript.delta {delta, start_ms, end_ms}` (fragments, append; keep 2,000, trim to 1,500),
  `session.delegation.created {delegation.id}`, `error {error.message, error.code}` (ignore
  `context_injection_incomplete`), `session.closed {reason, usage.seconds}`.
- Client → server: `{"type":"session.commentary.append","event_id","delegation_id","content"}` (spoken aloud,
  paraphrased; plain prose; ≈500 tokens per append → chunk on sentence boundaries at ~1,400 chars),
  `session.thinking.append` (quiet progress, one short clause), `session.instructions.append` (session-wide
  steer), `session.input_audio.mute` / `unmute`, `session.close` (then wait for `session.closed`).
- Delegation loop: every `session.delegation.created` → submit a normal turn with the spoken text plus the recent
  transcript (last 5 min, max 80 fragments, oldest first) as `voice_context`; stream tool starts as thinking,
  the answer sentence by sentence as commentary. One `activeDelegationId`; drop late results. A voice-layer
  interruption does not cancel the backend turn — the client decides. A `voice.stop_phrases` entry (default
  `stop`) ends the conversation instead of delegating. Delegated turns are answered in speakable prose; Hermes
  adds that note itself when the turn carries `surface: "voice-live"` (`methods_prompt.py` keeps `voice_context`
  only for that surface, capped at 6,000 chars).
- Billing: $0.05 per minute of session time, idle included. The key stays on the gateway.

Verified on the local test Hermes (2026-10-24, `voice.voice_chat_mode: gpt-live` added to its config): status
answers `{"mode":"gpt-live","available":true,…,"voice":"marin"}` because its `OPENAI_API_KEY` is the melious key;
the session POST then answers **502** (vendor 401). **So `available: true` is a hint, not a promise**: a 502/503 at
session creation is an ordinary outcome the client handles without an error state (see 2.5). The 400 for a blank
SDP is also verified. Real audio is untestable without a real key; the release gate is the user's device.

### 2.2 Intent and shape

A second voice engine behind the same Mic button and the same voice screen. When the server says GPT-Live is on
and the user has not switched it off, voice mode is a full-duplex conversation with the vendor voice: the user
speaks and hears answers with no turn boundaries, while Hermes does the work. The chat transcript still shows
every delegated turn (user prompt row, tool rows, reply) because each delegation is an ordinary turn on the open
session. Everything the chained engine does today keeps working unchanged when GPT-Live is off or unavailable.

Goals: zero setup on the phone (voice and persona are server-side); the conversation survives screen-off; the
user always sees that the meter is running; a start failure degrades to the chained engine silently.
Non-goals: the Android assistant slot (`AssistActivity`) stays chained; TURN/WAN; client-side voice or persona
overrides; history seeding beyond the last few text turns; Bot chats / owner scopes.

### 2.3 Engine selection

- New setting `liveVoice: Boolean = true` ("Live voice (GPT-Live)" switch in Settings → Voice). Its caption comes
  from the status endpoint, loaded when the Voice section is shown: "On this server: GPT-Live, voice marin" /
  "Off on this server (chained voice)" / "On the server but not ready" (`available: false`; the reason is logged,
  never shown) / "Couldn't ask the server" **(call)**. The switch is shown regardless; it only matters when the
  server reports `gpt-live`.
- On the Mic tap (after the `RECORD_AUDIO` grant, as today) the chat asks `GET …/voice-live/status` with a 4 s
  budget. `mode == gpt-live && available && liveVoice` → live engine; anything else (incl. timeout, 404 on an
  older Hermes, parse failure) → chained engine, no toast. The result is cached per chat view model for 60 s
  **(call)** so repeated taps do not re-query.
- The live session start can fail (mic error, SDP/ICE timeout, POST 4xx/5xx, no `session.started` within 20 s).
  Then: toast "Live voice couldn't start — using phone voice" and open the chained engine in the same gesture
  **(call)**. A session that drops *after* it started is not retried as chained: the voice screen shows the
  reason line and rests Idle until End or Keyboard (see 2.7).

### 2.4 Architecture (where things live)

Pure Kotlin, unit-tested with fakes, no Android types:

- `domain/livevoice/LiveEvent.kt` — sealed `LiveEvent` (`Started(sessionId)`, `Transcript(speaker, text,
  startMs, endMs)`, `Delegation(id)`, `Error(message, code)`, `Closed(reason, usageSeconds)`, `Unknown(type)`) and
  `LiveEvent.parse(json: String): LiveEvent?` (null on malformed JSON).
- `domain/livevoice/LiveTranscript.kt` — bounded fragment store (2,000 → 1,500) and
  `contextWindow(): List<Fragment>` (fragments whose `endMs ≥ last.endMs − 300_000`, last 80).
- `domain/livevoice/LiveText.kt` — `chunkForCommentary(text, limit = 1_400)`,
  `delegationPrompt(fragments): DelegationPrompt(prompt, context)` and `liveHistory(items, maxMessages = 24,
  maxChars = 6_000): List<LiveHistoryMessage>`; straight ports of the desktop functions, same limits.
- `domain/livevoice/StopPhrases.kt` — `isStopCommand(utterance: String): Boolean`: whole-utterance match after
  NFKC + lowercase + punctuation→space + whitespace collapse, optional address prefixes (`hey hermes`, `hermes`,
  `ok`, `okay`, `hey`), against the desktop's English list (`stop`, `stop listening`, `stop it`, `stop please`,
  `please stop`, `stop stop`, `that is all`, `that's all`, `never mind`, `nevermind`, `end conversation`, `end the
  conversation`, `goodbye`, `good bye`, `bye`, `cancel`). Hermes' configured `voice.stop_phrases` is not readable by
  the client; English built-ins only **(call)**.
- `domain/livevoice/LiveTransport.kt` — the seam the controller drives:
  ```kotlin
  interface LiveTransport {
      val events: Flow<LiveEvent>            // data-channel events, parsed; completes when the transport is torn down
      val speaking: StateFlow<Boolean>       // remote audio above threshold (speaking probe)
      suspend fun start(history: List<LiveHistoryMessage>)   // mic → offer → ICE → POST → answer; throws LiveStartException
      fun send(event: JsonObject): Boolean   // false when the channel is not open
      fun setMicMuted(muted: Boolean)        // track.enabled + mute/unmute event
      fun close()                            // idempotent teardown (no session.close; the controller sends that)
  }
  ```
- `domain/livevoice/LiveVoiceController.kt` — the engine (2.6). Same constructor shape as `VoiceController`
  (scope, `chat: StateFlow<ChatState>`, callbacks), emits `StateFlow<VoiceState>`.

Android:

- `voice/live/WebRtcLiveTransport.kt` — `LiveTransport` on `io.getstream:stream-webrtc-android:1.3.10` (Maven
  Central, Sept 2025; Google's libwebrtc). `PeerConnectionFactory` initialised once per process (`WebRtc.kt`),
  `JavaAudioDeviceModule` with hardware AEC/NS, audio source constraints `googEchoCancellation`,
  `googAutoGainControl`, `googNoiseSuppression`, `googHighpassFilter` = true; data channel `oai-events` created
  before the offer; offer with `OfferToReceiveAudio=true`; ICE gathering awaited ≤ 10 s (trickle is fine: send
  what there is); the POST goes through an injected `suspend (sdp: String, history: JsonArray?) -> String`
  (the answer SDP) so the transport knows nothing of HTTP; remote track plays through the ADM (no player code);
  an `AudioTrackSink` on the remote track feeds the speaking probe (peak |sample| > 1,536 of 32,767 in a 100 ms
  window = loud; 4 quiet windows → not speaking; same thresholds as the desktop's byte-domain probe). No ICE
  servers. Data-channel callbacks run on libwebrtc's thread: events are pushed into a `Channel(UNLIMITED)` and
  exposed as `events`.
- `voice/live/LiveAudioRoute.kt` — `AudioManager` mode `MODE_IN_COMMUNICATION`, speaker as the communication
  device (API 31+: `setCommunicationDevice(TYPE_BUILTIN_SPEAKER)` unless a Bluetooth SCO/LE or wired headset is
  available, then that; API 29–30: `isSpeakerphoneOn = true` unless `isWiredHeadsetOn`/BT SCO), audio focus
  `AUDIOFOCUS_GAIN` with `USAGE_VOICE_COMMUNICATION` (loss → the controller ends the session with reason
  `audio_focus_lost`), all restored on close **(call)**.
- `notifications/LiveVoiceService.kt` — foreground service, `foregroundServiceType="microphone|mediaPlayback"`,
  started when a live session starts (the app is in the foreground then, as Android 14 requires) and stopped when
  it ends. Notification on the existing agent channel: "Live voice with Hermes" / "<m:ss> · tap to return", one
  action **End** (a `PendingIntent` to the service → `AppContainer.liveVoiceEndRequests.tryEmit(Unit)`). The
  service keeps mic access and the WebRTC socket alive with the screen off. Permissions added:
  `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `MODIFY_AUDIO_SETTINGS`.
- `data/api` — `VoiceLiveStatusDto`, `VoiceLiveSessionDto`; `HermesApi.voiceLiveStatus()` (GET, managed=true so
  `?profile=` rides along, `NoReLogin` not set), `HermesApi.voiceLiveSession(sdp, history)` (POST on the 60 s
  `transcribeClient`; 400/502/503 keep the server `detail` on the `ApiException`); repository wrappers.
- `data/gateway/GatewaySession.submit(…, surface: String? = null, voiceContext: String? = null)` adds `surface`
  and `voice_context` to `prompt.submit`. A live delegation is submitted with `surface = "voice-live"`,
  `voice_turn = false` (Hermes keeps the session's own model; the desktop does the same), `queued = false`.
- ABI: `ndk.abiFilters = ["arm64-v8a"]` for every build **(call)** — libwebrtc is 11.5 MB on arm64 alone
  (6.6 MB more for armeabi-v7a, 25 MB more for x86/x86_64); every Android 10+ phone is 64-bit ARM. The APK grows
  from ~6 MB to ~18 MB; README and the site's fact card say so. Consumer ProGuard rules come with the AAR.

### 2.5 Session lifecycle

```
Mic tap ─► status (≤4 s) ─► live? ─► LiveVoiceService.start ─► transport.start(history) ─► session.started
   │                           │                                         │ (failure or no started ≤20 s)
   └── chained ◄───────────────┴─────────────────────────────────────────┘ toast "Live voice couldn't start — using phone voice"
End / Keyboard / stop phrase / leaving the chat ─► send session.close ─► session.closed (≤15 s) ─► teardown ─► service stop
session.closed (server) / data channel closed / ICE failed / audio focus lost ─► teardown ─► reason line, rest Idle
```

- `history` = `liveHistory(chat.items)`: text turns only (user and assistant text, no tools), newest 24 / 6,000
  chars, so the voice knows what was just discussed.
- The meter: `VoiceState.liveSeconds` counts from `session.started`; the status line shows it (2.7). On a
  graceful end the toast is "Live voice ended · 1:24" (the server's `usage.seconds` when present, else the local
  count); on `connection_lost` / vendor close the toast says "Live voice dropped · 1:24" **(call)**.
- Leaving the chat (navigating elsewhere, deleting the session) ends the session. Backgrounding or screen-off
  does **not** (the service keeps it); the existing `ON_STOP → closeVoice()` in `ChatScreen` applies to the
  chained engine only. A rotation never ends either engine (unchanged).
- Only one live session per process: a second Mic tap while one runs is a no-op (`ChatVoice.open` already
  returns while open).

### 2.6 The delegation loop (LiveVoiceController)

State: `activeDelegationId`, `spokenResponseId`, `spokenLength`, `lastToolLabel`, `turnObserved`,
`submittedAt`, `userUtterance` + settle timer, `transcript: LiveTranscript`, `handledRequests`.

- `Transcript(user)`: append to the transcript, append to `userUtterance`, show it as `VoiceState.heard`; after
  1.5 s of quiet (`UTTERANCE_SETTLE_MS`) judge the whole utterance: `isStopCommand` → end the session (reason
  `stop_phrase`, no delegation); otherwise clear `heard` on the next delegation or assistant fragment.
- `Transcript(assistant)`: append; `VoiceState.caption` = the voice's current utterance (assistant fragments
  since the last user fragment, trimmed to the last 240 chars) so small talk that never reaches Hermes is visible.
- `Delegation(id)`: `delegationPrompt(transcript.contextWindow())` → if the prompt is a stop command, end instead.
  Else: if the chat is running (`status.running`), call `onInterrupt()` (the chat's Stop) first; set
  `activeDelegationId = id`, reset the spoken cursor and tool label, `turnObserved = false`, `submittedAt = now`,
  `heard = ""`; `onSubmit(prompt, context)` (`ChatViewModel.sendLive`); a false return speaks
  "Sorry, I could not reach Hermes for that request." and clears the delegation.
- Feeding the reply (driven by chat state changes and a 200 ms tick while a delegation is active, same as the
  desktop):
  - `status.running` → `turnObserved = true`.
  - `status is AgentStatus.Tool(name)` and `name != lastToolLabel` → `think("Hermes is working: <name>. Not done
    yet.")`.
  - The pending reply = the last `AssistantMessage` appended after `submittedAt`'s item count (the controller
    remembers `items.size` at submit). Its `SpeechText.plain(text)` is fed to a `SentenceChunker`; complete
    sentences are `speak`-ed as they appear while the item streams or the status runs; when the item is no longer
    streaming and the status is not running, the tail is spoken, the delegation cleared, and `revealed[id] =
    text.length`.
  - No reply and the status is idle after `turnObserved` (or 15 s `SUBMIT_SETTLE_GRACE_MS` without it): if
    nothing was spoken, `think("Hermes finished that request without a spoken result.")`; clear the delegation.
  - A waiting request card (`status.waiting`, i.e. approval/clarify/secret/connection) during a delegation, once
    per card id: `speak("Hermes needs you to answer something on screen.")` and
    `think("Hermes is waiting for the user to answer a request in the app.")`. The card is answered by tapping it
    on the voice screen, as today.
  - A newer delegation supersedes: feeding for the old id stops at once; its tail is never spoken.
- `Error(message, code)`: `context_injection_incomplete` ignored; otherwise `VoiceState.error = "Live voice:
  <message>"` (vendor text, bounded to 200 chars) without ending the session.
- `Closed(reason, seconds)`: teardown + `onClosed(reason, seconds)`.
- `tapIndicator()`: in live mode sends `session.instructions.append("The user has finished speaking. Respond now
  to what they said.")` (the desktop's nudge); `setMuted` → `transport.setMicMuted`.
- Phase mapping: `Approval` when the chat waits; `Speaking` while `transport.speaking`; `Thinking` while a
  delegation is active; `Muted`; else `Listening`; `Idle` after the session ended (with `error`/reason line) or
  while starting (`VoiceState.transcribing = false`, `caption = "Connecting…"`) **(call)**.

### 2.7 Voice screen in live mode

`VoiceScreen` is reused with `VoiceState.live != null`:

- Top row: the voice-language button is replaced by a passive chip "Live · marin" (the status voice) **(call)**.
- Status line (`voiceLine`): "Listening · 1:24", "Speaking · 1:24", "<agent status label> · 1:24",
  "Muted · 1:24", "Connecting…" while starting, and the end reason when Idle ("Live voice ended", "Live voice
  dropped", "Audio taken by another app") — the elapsed time is always visible while the meter runs.
- Under the status line: the caption (`VoiceState.caption`, body style, ink2, centred, 3 lines max) and the
  `error` line as today.
- The transcript column shows the chat items since `startIndex` as today; assistant replies are shown in full as
  they stream (`revealed[id] = text.length` on every update: the voice paraphrases, so there is no reveal
  cursor) and `heard` is the live user bubble.
- Controls unchanged: Mute, End, Keyboard. Tapping the indicator nudges the voice to answer (2.6).
- The settings line: Settings → Voice → "Live voice (GPT-Live)" switch with the caption of 2.3.

### 2.8 Security and privacy

- The OpenAI key never reaches the client (server-side exchange). The app sends the SDP offer and a bounded
  text history to Hermes only, over the already-authenticated HTTP client (bearer, re-login rules unchanged).
- Vendor-sent strings (`error.message`, `session.closed.reason`) are bounded (200 chars) and shown with the
  "Live voice:" prefix; `status.reason` is logged at debug level only.
- Commentary and thinking carry Hermes' reply text and tool names only — never approval commands, secrets, or
  tool output.
- The foreground notification shows no transcript text.
- `RECORD_AUDIO` is requested exactly as for the chained engine; the service's `microphone` type is declared so
  Android 14+ keeps the mic usable in the background, and the service runs only while a session is open.

### 2.9 Verification plan

- Unit: every `domain/livevoice` function; the controller against `FakeLiveTransport` (scripted events) and a
  `MutableStateFlow<ChatState>` rig like `VoiceControllerTest`; `ChatViewModel` engine selection (status
  variants, timeout, setting off, start failure → chained + toast); `GatewaySession.submit` payload with surface
  and context; DTO parsing of the exact JSON shapes in 2.1; share intake and file store.
- Compose UI: `VoiceScreen` live variant (chip, status line with timer, caption), the Settings switch and
  captions, the share card.
- Smoke (`scripts/e2e_smoke.py`): `GET voice-live/status` is `ok`; when `mode == gpt-live`, `POST session` with a
  blank SDP is 400 and with a dummy SDP is 502 or 503 (never 200 on the test box); logged, not fatal on a server
  without the route.
- Device (user): a real GPT-Live session on an instance with a key: hear the voice, see delegation turns appear
  in the chat, tool progress spoken as "working", stop phrase ends it, screen-off keeps it, End from the
  notification, APK size.

### 2.10 Open risks

- `AudioTrackSink` sample access on a remote track exists in the chosen libwebrtc build (class present in the
  AAR); if the sink never fires on device, the speaking indicator stays off but the conversation works — the
  user should report it.
- Echo: hardware AEC is requested; on devices without it the vendor VAD may hear its own output through the
  speaker. Headphones are the workaround; a future "earpiece mode" is out of scope.
- Hermes' `busy_input_mode` on an unqueued submit mid-turn: the controller calls Stop first, then submits, so the
  queued/redirect rules from v0.9.2 are not exercised by live delegations.
