# Tsukaima (使い魔)

Native Android client (Kotlin + Jetpack Compose) for a self-hosted [Hermes Agent](https://github.com/NousResearch/hermes-agent).
Chat with your agent, browse sessions, manage cron jobs, and get actionable notifications from your phone.

Version **0.9.3** (versionCode 23). Changes per release: [`CHANGELOG.md`](CHANGELOG.md).

Runs on Android 10+ phones and tablets with a 64-bit ARM processor (arm64-v8a only: the live voice engine’s native
WebRTC library would add 7–25 MB per extra ABI, and every Android 10+ phone is 64-bit ARM). The APK is about 18 MB.

## Features

- Streaming chat over the Hermes JSON-RPC WebSocket: replies, reasoning, tool calls, mid-turn commentary
- **Stop** a running turn from the composer (or the chat's overflow menu); a message sent while a turn runs is
  **queued** for after it ("Queued"; Stop leaves it "Not sent" with Retry)
- **Live model switch** for the open chat (session-scoped; mid-turn picks apply at the next turn), **reasoning
  effort**, a context meter in the status line ("42% ctx · 12.3k tok") and **Compact context**
- **Retry** a failed send from its bubble; long-press a message to **copy** its text or reasoning
- **Edit** a sent message or **Regenerate** a reply (long-press): Hermes rewinds the chat to that point and runs
  the turn again
- **Side questions** and **background tasks** from the composer's overflow menu; answers arrive as cards in the chat
- Subagent (delegation) rows, a risk note on flagged tool output, archived chats read-only with **Unarchive**;
  stopped tools show **Interrupted**, and the image `vision_analyze` looked at shows on its tool row
- Replies render markdown with tables, task-list checkboxes and **math** (LaTeX; never spoken)
- The agent's **todo list** as a live "Tasks" checklist card
- Tool approvals in chat: Deny / Approve, plus "Approve for…" this session or always when the server allows it
- Answer the agent's **clarify** questions (choices, multi-select, free text) in chat
- **Connection requests** in chat: Connect (browser sign-in or settings fields) / Skip, Skip all, Continue
- **Masked prompts**: a `sudo` password, a skill's secret or a password-manager unlock, typed into a hidden
  field and never stored or logged; password-manager **one-time codes** and **save-login** offers answered in chat
- **Attachments**: send files and images with a message (up to 12 MB each); a **PDF** goes as page images (first
  25 pages)
- **Images in replies**: shown inline when they come from your Hermes server; images from any other host load
  only when tapped (the card names the host)
- **File deliveries**: the agent's `MEDIA:` lines become file cards with image previews; tap to save to
  Downloads and open
- **Send to Tsukaima** from any app’s share sheet: a link, text, screenshots, photos or a PDF land in the composer
  of your last chat or a new one (you pick on a small "Send to Hermes" card); nothing is sent until you tap Send
- Resume a running chat with the reply streamed so far; replay missed events after a reconnect
- Sessions drawer: server-side **search**, rename, archive, delete, live refresh; working-directory and model pickers
- Cron job management (create, edit, pause/resume, run now)
- Status home: gateway state, platforms (live refresh), start/stop/restart
- Settings sections: Hermes instance (sign in / **sign out**), profile, model, skills and **toolsets** (both
  toggleable), pairing (revoke with confirmation), **notifications** (per kind), diagnostics (logs, usage), theme
- Actionable approval notifications, question and password prompts, finished replies, gateway-down and
  cron-failed alerts via a foreground service; each kind can be switched off
- HTTPS fronts (Tailscale Serve or any TLS reverse proxy) as well as plain `http://` on a tailnet
- Light/dark themes, Material You option, wide (tablet/foldable) pane layout
- Voice mode, **live voice** (GPT-Live, when your Hermes runs it) and an assistant slot (see below)
- **Report a problem** (Settings → Support): shares a text report with versions, device, server host, the last
  crash and the app's own log (never your username, password or token)

## Voice

Tap the composer's mic (shown while the field is empty) to open voice mode. It listens continuously and sends
whatever it recognises as a final utterance, including background speech, so mute while others talk. Replies are
read aloud sentence by sentence as they stream; fenced code blocks and delivered files are shown, never spoken.
Tap the orb while it speaks to interrupt and talk. Risky actions still need a tap on Approve: voice never answers
approvals, questions or password prompts. End returns to the chat (the transcript is already in the session);
Keyboard returns with the composer focused. Voice closes when the app leaves the screen; rotation keeps it. After
2 minutes without hearing you it rests at "Tap to speak". Spoken turns reach Hermes flagged as voice turns (its
voice-chat model and persona apply), and a turn after you interrupted a reply says so.

Recognition uses the platform recogniser, on-device first (API 31+), and falls back to the default recogniser
when the language pack is missing. Speech uses the system text-to-speech engine; without one, replies are shown
only. Both follow one language setting, the system default unless you pick another in Settings → Voice → Language
or with the translate button on the voice screen.

Settings → Voice → Voice processing is one switch for both directions: "On this phone" (above) or "On Hermes".
On Hermes, the phone records one utterance at a time, detects the end of speech itself, uploads it as Opus (WAV if
the encoder is unavailable; only while the mic is listening) to the dashboard's `/api/audio/transcribe`, and Hermes
transcribes it with its configured speech-to-text. There are no live partial words; the status line shows
"Transcribing…" until the text arrives. Replies are spoken sentence by sentence through `/api/audio/speak` with
Hermes' configured text-to-speech and played on the phone. Hermes' own language settings decide both ways, so the
voice language then has no effect. Audio is held in memory for one utterance and never saved. Recognising on one
side and speaking on the other is not offered. The switch applies from the next voice session. In a loud room,
two 30-second clips in a row that transcribe empty stop listening with "Too noisy to hear you — tap to retry".

To use Tsukaima as the device assistant, open Settings → Voice → "Use as device assistant" and pick it in the
system's assistant picker. Long-press home (or the corner swipe) shows a sheet that handles one exchange per
invocation and then waits at "Tap to speak"; "Continue in Tsukaima" opens the chat. Assistant chats are reused for
30 minutes, then a new chat starts. Anything needing approval is handed to the app ("That needs your approval. I’ve
opened it in Tsukaima."). Launches of the sheet by other apps (`ACTION_ASSIST`) open idle on a new chat and never
listen until tapped.

Not supported: a hotword, and anything on the lock screen. Android 10 and 11: while Tsukaima is the assistant, its
recogniser becomes the device's default speech recogniser and forwards to the system one, so voice input from the
assistant gesture and other apps keeps working there (only for apps that hold the microphone permission
themselves); Android 12+ keeps the two apart.

### Live voice (GPT-Live)

When your Hermes runs GPT-Live (`voice.voice_chat_mode: gpt-live` and an OpenAI key on the server) and Settings →
Voice → "Live voice (GPT-Live)" is on (the default), the mic opens a live conversation instead: you and the voice
talk freely, with no turns, while Hermes does the work. The switch’s caption says what the server reports ("On this
server: GPT-Live, voice marin", "Off on this server (chained voice)", "On the server but not ready", "Couldn’t ask
the server"); with the switch off, or on a server without GPT-Live, voice mode works as above. The voice is seeded
with the chat’s recent text turns. Whenever it hands a request to Hermes, that request runs as an ordinary turn in
the open chat (with the last few minutes of the spoken exchange as context, and Hermes asked for speakable prose),
so the transcript shows the prompt, the tool rows and the reply; the voice says when Hermes is working and
paraphrases the answer. A request that needs approval or an answer is announced ("Hermes needs you to answer
something on screen") and answered with a tap, as above. Saying only "stop", "that’s all", "goodbye", "cancel" (or
a few other English phrases, optionally after "hey Hermes") ends the conversation; so do End and Keyboard.

GPT-Live is billed per minute of session time, idle time included, on the server’s OpenAI account, so the status
line always shows the running time ("Listening · 1:24") and the end toast says how long it was ("Live voice ended ·
1:24"). Unlike phone voice, it keeps going with the screen off or another app in front: a "Live voice with Hermes"
notification shows the time and has an End button. Leaving the chat ends it; another app taking the audio (a phone
call) ends it too. If a session can’t start, the app says "Live voice couldn’t start — using phone voice" and opens
phone voice instead. A session that drops later is not retried: the voice screen shows "Live voice dropped" until
End or Keyboard. The voice, its persona and the language are the server’s; the language and voice-processing
settings do not apply, and the assistant sheet always uses phone voice. Audio is routed to a connected headset, else
the loudspeaker; headphones help on phones whose echo cancelling is weak.

The OpenAI key never reaches the phone: the app sends its WebRTC offer and the seed history to Hermes, which opens
the session with OpenAI (or the endpoint set in its `voice.gpt_live` config) and returns the vendor’s answer. The
audio itself then streams between the phone and the vendor; transcripts and requests travel on the same connection,
and every request the voice hands over goes to Hermes as above.

## Screenshots

_To come: chat with an approval card, the masked sudo prompt, status home, sessions drawer, dark mode._

## Build

Requires JDK 21 and the Android SDK (`ANDROID_HOME`).

```bash
export ANDROID_HOME=$HOME/android-sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
./gradlew :app:testDebugUnitTest :app:lintDebug   # tests + lint
./gradlew :app:assembleDebug                      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease                    # app/build/outputs/apk/release/app-release.apk
```

The release build is minified (R8).

### Release signing

`assembleRelease` signs with the release key when `.local/keystore.properties` exists (`storeFile`, relative to the
repo root, `storePassword`, `keyAlias`, `keyPassword`); the keystore itself lives next to it in `.local/` (here
`.local/tsukaima-release.jks`, PKCS12, `CN=Tsukaima, O=suchattai-labs`). Neither is ever committed: `.local/`,
`*.jks`, `*.keystore` and `keystore.properties` are git-ignored, so keep a backup of the keystore elsewhere (losing
it means no further updates over an installed build). Without the properties file (CI, a fresh clone) the build
logs a warning and signs with the debug keystore; CI's `tsukaima-release-debugsigned` artifact is that build.
Check a build with `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`.

Android refuses an update signed by a different key, so the first install of a release-key build over a
debug-signed one (any build before 0.5.1, or a CI artifact) needs an uninstall first (`adb uninstall
app.tsukaima`), which also clears the stored sign-in.

Server-side smoke test (uses `.local/hermes-test-creds.env`; run against a test Hermes only):

```bash
scripts/e2e-smoke.sh
```

## First run

Enter the Hermes dashboard URL (e.g. `http://100.x.y.z:9119`, or the `https://…ts.net` address Tailscale Serve
gives it) and the dashboard basic-auth username and password. The app logs in, stores the credentials encrypted
on-device, and re-logs in automatically when the session expires (see the tailnet note below for which hosts).
Settings → Sign out forgets the password and token, disconnects, clears notifications and returns to first run.

### Cleartext policy

Plain `http://` works for hosts that are local or private: loopback, RFC 1918, Tailscale (100.64/10), `*.ts.net`
and single-label names (`http://hermes:9119`); the tailnet is WireGuard-encrypted. Any other host must use
`https://`: the app refuses plain http to it before anything is sent (no bearer token, no password), with "Plain
http is only allowed for LAN, tailnet and single-label hosts — use https", shown inline on Test connection and
Sign in. The rule is enforced in code (`CleartextGuard` on every HTTP request and redirect hop, and before the
WebSocket connects) because Android's network security config can allowlist domains but not address ranges; that
config (`res/xml/network_security_config.xml`) keeps cleartext permitted and trusts only the system CAs, so a
user-installed CA cannot intercept https (a reverse proxy with a private CA needs a publicly trusted certificate,
e.g. Tailscale Serve's).

## Permissions

- `INTERNET`: talk to your Hermes server.
- `POST_NOTIFICATIONS` (Android 13+): approval requests and gateway alerts.
- `RECORD_AUDIO`: voice mode and the assistant sheet; asked on the first mic tap.
- `MODIFY_AUDIO_SETTINGS`: live voice switches the phone to call audio (communication mode, headset or
  loudspeaker) for the session and restores it afterwards.
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`: keeps the event connection alive in the background
  so notifications can arrive; shown as a persistent low-priority notification.
- `FOREGROUND_SERVICE_MICROPHONE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK`: keep the mic and the audio of a live voice
  session going with the screen off; the service runs only while a session is open, with its own notification.
- `ACCESS_NETWORK_STATE`: merged in by the WebRTC library (live voice); the app itself does not use it.

## Docs

- Protocol notes (auth, WebSocket RPC, REST endpoints): [`docs/protocol/README.md`](docs/protocol/README.md)
- Design handoff: [`design/`](design/) (start at `design/HANDOFF.md`)

## License

Apache License 2.0 — see [`LICENSE`](LICENSE). Copyright 2026 Mariusz Suchodolski.

Tsukaima is an independent client for Hermes Agent and is not affiliated with Nous Research.
