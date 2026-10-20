# Completeness pass — 2026-10-20, feat/android-app @ a5a0448 (v0.3.0)

Four read-only audits (design ↔ UI, protocol ↔ data layer, hygiene/release, plan+rulings ↔ code) plus a
lint run (40 warnings, 19 of them "newer version available"). Claims marked ✔ were re-verified by hand.
Paths are under `app/src/main/kotlin/labs/suchattai/tsukaima/`.

## Tier 1 — bugs and blockers (fix now)

| # | Item | Evidence |
|---|------|----------|
| 1 | **No stop/interrupt in the UI.** `ChatViewModel.interrupt()` and `GatewaySession.interrupt` exist; nothing in `ChatScreen`/`Composer` calls them. A runaway turn can't be cancelled from the phone. ✔ | `ui/chat/ChatViewModel.kt:223`, `ui/chat/components/Composer.kt:140` |
| 2 | **`sudo` / `secret` server requests answered "method not found"** (-32601). A tool needing a sudo password or an env-var secret fails silently instead of asking. `vault.unlock_prompt` likewise. ✔ | `data/ServerRequestRouter.kt:50`; Hermes `tools/terminal_tool_sudo.py:191`, `tui_gateway/contracts/server_requests.py:208-228` |
| 3 | **Approvals only offer once/deny.** Server offers `once/session/always/deny` (and the card already receives `allow_session`/`allow_permanent`). ✔ | `ui/chat/ChatViewModel.kt:242`, `ui/chat/components/ApprovalCard.kt:185-186`, `data/gateway/GatewayModels.kt:68` |
| 4 | **Settings model picker drops the provider.** `setModel(provider, model)` saves only `model`; chat creates sessions with the stored `modelProvider` → stale provider sent with a new model. ✔ | `ui/settings/SettingsViewModel.kt:267-269`, `ui/chat/ChatViewModel.kt:111` |
| 5 | **Overflow menu silently no-ops on a brand-new chat** (`stored == null`). | `ui/chat/ChatScreen.kt:173` |
| 6 | **No sign-out.** Nothing clears credentials/token. | `ui/settings/SettingsScreen.kt:166` |
| 7 | **`!!` on parsed network JSON** (`ticket`, `active`) → NPE instead of `ApiException` on a malformed reply. ✔ | `data/api/HermesApi.kt:58,205` |
| 8 | **Event with seq 0 is dropped** (`<=` against default `lastSeq = 0`). | `domain/chat/ChatReducer.kt:118` |
| 9 | **`message.interim` ignored** — mid-turn narration between tool calls never shows. | `domain/chat/ChatReducer.kt:121-169` |
| 10 | **Resume of a running chat shows Thinking but not the already-streamed partial text** (M9). | `ui/chat/ChatReconnector.kt:121` |
| 11 | **Notification small icon is the framework `stat_notify_chat`** (OEM-dependent, unbranded). ✔ | `notifications/Channels.kt:59,72` |
| 12 | **CI runs only `testDebugUnitTest assembleDebug`** — no lint, no release/R8 build, no release artifact (plan T20). ✔ | `.github/workflows/android.yml:29` |

## Tier 2 — missing features a phone client needs (v0.4.0 candidates)

- **Live model switch / reasoning effort** for the current session (only "applies to next chat" today; no `config.set`/`slash.exec`). `ui/chat/ChatViewModel.kt:318-330`.
- **Usage/tokens**: `session.usage`, `session.info` token fields, `status.update`, `session.compress` (compaction) — nothing shown.
- **Live refresh**: `sessions.changed` / `platforms.changed` ignored; drawer and status only re-poll.
- **Archived-session read-only banner** (deferred since v0.1.0, never built).
- **Notification settings** (per-channel toggles) and the planned long-task-done / cron-failed / gateway-down notifications — only approval, clarify and pairing are confirmed.
- **Session search** has no REST/RPC call; **pairing revoke** has API but no UI; **toolset toggle** absent; **profile switch** is client-side only.
- **Retry failed sends** is toast-only; **copy message** action absent (design has copy for code/session id only).
- **Subagent/delegation events** render as a generic tool label.
- `background.complete`/`btw.complete`, `tool.output_risk`, `session.resume_progress`, `notification.show` — unhandled.

## Tier 3 — hygiene, release readiness, docs

- **Release signing uses the debug keystore**; no `keystore.properties`/`*.jks` ignore rules. `app/build.gradle.kts:25`.
- **`usesCleartextTraffic="true"` globally**, no network-security-config; only the password re-login replay is host-gated (`TrustedHost`). Bearer still goes to any http host. `AndroidManifest.xml:16`.
- `FOREGROUND_SERVICE_DATA_SYNC`: `onTimeout` is handled ✔; Play would need a declaration form (sideload only for now).
- **Strings**: `strings.xml` has only `app_name`; ~39 UI literals + VM toasts hardcoded. Fine for a personal app, blocks localisation.
- **Accessibility**: 36 dp targets in `Composer.kt:188` (attachment remove) and `MediaCard.kt:125`; `TsukaimaIcon` default has no contentDescription.
- **Dead code**: `GatewaySession.list` + `SessionSummary`, `PlaceholderScreen` (`TsukaimaNavHost.kt:231`) + stale KDoc at `:62`, unused `material3-adaptive` deps in `libs.versions.toml:12,24`, `@Suppress("UNUSED_PARAMETER")` in `SettingsViewModel.setModel`.
- "Simulate Hermes unreachable" (kit-only per handoff) ships in release Settings. `SettingsScreen.kt:260-266`.
- Deny button is `GhostButton(Danger)`; design says secondary with red ink. `ApprovalCard.kt:185`.
- Dark wisp asset (`design/assets/resting-wisp-dark.svg`) unused; light one tinted instead.
- Untested logic: `ChatReconnector`, `ChatAttachments` (only via VM tests), `NotificationActionReceiver` approve path, `SessionBindings`, `SessionsSource`, `ImagePreview`, `CredentialStore` proper.
- `GatewayClientTest.kt:314,327` use `Thread.sleep(200)`.
- Lint: 9 `ModifierParameter`, 4 `UseKtx`, `allowBackup` deprecation (add `dataExtractionRules`), `Configuration.screenWidthDp` ×2, vector >200 dp (`resting_wisp.xml`), `InlinedApi` (POST_NOTIFICATIONS), one non-lambda `offset`.
- **Docs stale**: `README.md` describes v0.1.0 (no clarify/attachments/MEDIA/HTTPS, no version, "models and toolsets screens" are Settings sections, no screenshots section); no CHANGELOG; `docs/protocol/README.md` §2 says clarify cancel is `{"answers":null}` (it is `{}`), calls `approval.respond` REST, omits `profile` param and the attach RPCs, §3 `/api/sessions` lacks the 100 cap and `archived`/`exclude_sources`; rulings doc still lists "clarify hint lost when backgrounded" (fixed in 0.2.0).

## Deferred minors still open (from the rulings doc, effort S unless noted)

T1 CI concurrency group / wrapper checksum · T3 focus rings on buttons/chips, `MenuSheet` shadow (M) · T4 URL tests for IPv6/ftp/userinfo, file-wide `@file:Suppress` in `CredentialStore.kt` · T5 quoted cookie value not unquoted, re-login lock held across login, no 429 cooldown · T6 `JsonRpc` catches `Exception` not `Throwable`, `"12"` accepted as seq, `{"error":null}` decodes as success · T7 `GatewayClient` 419 lines, request between `awaitConnected` and a drop fails instead of waiting (M) · T8 `mostRecent` throws on a non-primitive id · T9 reasoning can carry into the next reply when a turn ends without a final event · T10 ```` ```x``` ```` swallows the rest, 4-backtick fence never closes, O(n²) `_` scan · T11 origin/token read non-atomically, bearer sent to untrusted http hosts (M) · T12 `inline()` not remembered, Copy timer not restarted, blank tool name renders an empty row, CRLF commands show ⟨U+000D⟩ · T14 pairing approve/revoke no feedback or double-tap guard, onboarding doesn't scroll in landscape · T15 offline caption lacks host, "a and b" platform join, action row doesn't wrap, no refresh on return, restart compares a cached `gatewayUpdatedAt` · T16 archive/delete don't close the live session first, model pick before session exists only toasts (M) · T17 cron `now` only refreshes on list change, `+5 * * * *` accepted · T18 chip rows are `Row` not `FlowRow` · v0.2.0 retry after a lost `image.attach_bytes` can double-queue (M) · v0.3.0 octet-stream downloads open nothing (no extension→MIME lookup), two cards for one path share state, MediaStore extension rename unchecked, smoke leaves its upload on the server.

## Verified fine (skip re-checking)

Credentials in EncryptedSharedPreferences with in-memory fallback; `allowBackup=false`; no TrustManager/HostnameVerifier overrides; no body logging; approval/pairing/agent channels `VISIBILITY_PRIVATE` with public versions; only `MainActivity` exported; PendingIntents immutable; WS ticket single-use 30 s in query (as the SPA does); 401 single-flight re-login with latch; reconnect backoff 1/2/5/10 s with fresh ticket; `session.events.since` replay with truncation → history rebuild; foreground service hand-off; `onTimeout` handled; adaptive + monochrome launcher icon complete; manifest permissions all exercised; R8 + resource shrinking with serialization/OkHttp keep rules; `.gitignore` covers `.local/`, `*.env`, `local.properties`; smoke script refuses the real instance and never prints the password; no TODO/FIXME/println/printStackTrace/commented-out code in main; every design screen and state is implemented (onboarding, status incl. offline/cached, chat with all cards, drawer incl. archived + long-press, two-pane ≥720 dp, cron list/edit, logs, settings, dark mode, toast).
