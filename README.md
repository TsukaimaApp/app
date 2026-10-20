# Tsukaima (使い魔)

Native Android client (Kotlin + Jetpack Compose) for a self-hosted [Hermes Agent](https://github.com/NousResearch/hermes-agent).
Chat with your agent, browse sessions, manage cron jobs, and get actionable notifications from your phone.

## Features

- Streaming chat over the Hermes JSON-RPC WebSocket (tool calls, reasoning, approvals)
- Session list, search, rename, archive, delete
- Cron job management (create, edit, pause/resume, run now)
- Logs, models and toolsets screens
- Actionable approval notifications and gateway alerts via a foreground service
- Light/dark themes, wide-layout (tablet/foldable) pane layout

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

Enter the Hermes dashboard URL (e.g. `http://100.x.y.z:9119`) and the dashboard basic-auth username and
password. The app logs in, stores the credentials encrypted on-device, and re-logs in automatically
when the session expires.

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
