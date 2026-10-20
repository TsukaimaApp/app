# Tsukaima (使い魔)

Native Android client (Kotlin + Jetpack Compose) for a self-hosted [Hermes Agent](https://github.com/NousResearch/hermes-agent).
Chat with your agent, browse sessions, manage cron jobs, and get actionable notifications from your phone.

Version **0.4.0** (versionCode 5). Changes per release: [`CHANGELOG.md`](CHANGELOG.md).

## Features

- Streaming chat over the Hermes JSON-RPC WebSocket: replies, reasoning, tool calls, mid-turn commentary
- **Stop** a running turn from the composer (or the chat's overflow menu)
- Tool approvals in chat: Deny / Approve, plus "Approve for…" this session or always when the server allows it
- Answer the agent's **clarify** questions (choices, multi-select, free text) in chat
- **Masked prompts**: a `sudo` password, a skill's secret or a password-manager unlock, typed into a hidden
  field and never stored or logged
- **Attachments**: send files and images with a message (up to 12 MB each)
- **File deliveries**: the agent's `MEDIA:` lines become file cards with image previews; tap to save to
  Downloads and open
- Resume a running chat with the reply streamed so far; replay missed events after a reconnect
- Sessions drawer: filter, rename, archive, delete; working-directory and model pickers
- Cron job management (create, edit, pause/resume, run now)
- Status home: gateway state, platforms, start/stop/restart
- Settings sections: Hermes instance (sign in / **sign out**), profile, model, skills, toolsets, pairing,
  diagnostics (logs, usage), theme
- Actionable approval notifications, question and password prompts, gateway alerts via a foreground service
- HTTPS fronts (Tailscale Serve or any TLS reverse proxy) as well as plain `http://` on a tailnet
- Light/dark themes, Material You option, wide (tablet/foldable) pane layout

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

The release build is minified (R8) and currently signed with the debug keystore so it is installable.
Replace that with a real keystore before distribution.

Server-side smoke test (uses `.local/hermes-test-creds.env`; run against a test Hermes only):

```bash
scripts/e2e-smoke.sh
```

## First run

Enter the Hermes dashboard URL (e.g. `http://100.x.y.z:9119`, or the `https://…ts.net` address Tailscale Serve
gives it) and the dashboard basic-auth username and password. The app logs in, stores the credentials encrypted
on-device, and re-logs in automatically when the session expires (see the tailnet note below for which hosts).
Settings → Sign out forgets the password and token, disconnects, clears notifications and returns to first run.

Tailnet note: plain `http://` works (the tailnet is WireGuard-encrypted), but automatic re-login, which
replays the stored password, over `http://` is allowed only for loopback, RFC 1918, Tailscale (100.64/10),
`*.ts.net` and single-label hosts. For any other host use `https://`.

## Permissions

- `INTERNET`: talk to your Hermes server.
- `POST_NOTIFICATIONS` (Android 13+): approval requests and gateway alerts.
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`: keeps the event connection alive in the background
  so notifications can arrive; shown as a persistent low-priority notification.

## Docs

- Protocol notes (auth, WebSocket RPC, REST endpoints): [`docs/protocol/README.md`](docs/protocol/README.md)
- Design handoff: [`design/`](design/) (start at `design/HANDOFF.md`)

## License

None yet; private repository.
