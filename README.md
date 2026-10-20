# Tsukaima (使い魔)

Native Android client (Kotlin + Jetpack Compose) for a self-hosted [Hermes Agent](https://github.com/NousResearch/hermes-agent).
Chat with your agent, browse sessions, manage cron jobs, and get actionable notifications from your phone.

Version **0.5.0** (versionCode 6). Changes per release: [`CHANGELOG.md`](CHANGELOG.md).

## Features

- Streaming chat over the Hermes JSON-RPC WebSocket: replies, reasoning, tool calls, mid-turn commentary
- **Stop** a running turn from the composer (or the chat's overflow menu)
- **Live model switch** for the open chat (session-scoped; mid-turn picks apply at the next turn), **reasoning
  effort**, a context meter in the status line ("42% ctx · 12.3k tok") and **Compact context**
- **Retry** a failed send from its bubble; long-press a message to **copy** its text or reasoning
- Subagent (delegation) rows, a risk note on flagged tool output, archived chats read-only with **Unarchive**
- Tool approvals in chat: Deny / Approve, plus "Approve for…" this session or always when the server allows it
- Answer the agent's **clarify** questions (choices, multi-select, free text) in chat
- **Masked prompts**: a `sudo` password, a skill's secret or a password-manager unlock, typed into a hidden
  field and never stored or logged
- **Attachments**: send files and images with a message (up to 12 MB each)
- **File deliveries**: the agent's `MEDIA:` lines become file cards with image previews; tap to save to
  Downloads and open
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
labs.suchattai.tsukaima`), which also clears the stored sign-in.

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
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC`: keeps the event connection alive in the background
  so notifications can arrive; shown as a persistent low-priority notification.

## Docs

- Protocol notes (auth, WebSocket RPC, REST endpoints): [`docs/protocol/README.md`](docs/protocol/README.md)
- Design handoff: [`design/`](design/) (start at `design/HANDOFF.md`)

## License

None yet; private repository.
