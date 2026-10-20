# Changelog

All notable changes to Tsukaima. Versions follow `versionName` in `app/build.gradle.kts`. Decisions behind each
release are in [`docs/superpowers/2026-10-20-build-rulings.md`](docs/superpowers/2026-10-20-build-rulings.md).

## 0.4.0 — 2026-10-20 (versionCode 5)

The Tier 1 batch of the completeness pass ([`docs/superpowers/2026-10-20-completeness-pass.md`](docs/superpowers/2026-10-20-completeness-pass.md)).

### Added
- **Stop**: while a turn runs, the composer's send button becomes a danger-filled Stop (`session.interrupt`);
  also in the chat's overflow menu. "Nothing to stop" when the server reports `not_interrupted`.
- **Masked prompts**: `sudo`, `secret` and `vault.unlock_prompt` server requests show a card with a masked field
  (show/hide), answered `{"value": …}`; Cancel answers `""`. The value is never kept in state, logged or toasted;
  the answered card shows only "Sent" / "Declined". Status line "Waiting for a password" / "Waiting for a
  secret"; a background notification ("Hermes needs a password" / "…a secret") opens the chat. v0.3.x answered
  these requests with "method not found".
- **Approval scopes**: "Approve for…" opens a sheet with "This session" (`session`) and "Always" (`always`),
  each shown only when the server offers it (`choices`, else `allow_session` / `allow_permanent`). The answered
  card names the scope. Notification actions stay Approve (once) / Deny.
- **Sign out** (Settings → Hermes instance): clears the password and token, disconnects the gateway, cancels
  notifications, stops the foreground service and returns to Onboarding (the URL and username stay prefilled).
- **Mid-turn commentary**: `message.interim` text that was not streamed shows as its own reply before the next
  tool call.
- **Resume of a running chat** shows the reply streamed so far (`session.resume` → `inflight.assistant`) and
  continues it with the live deltas.
- Branded monochrome notification icon (`ic_stat_tsukaima`) instead of the framework chat glyph.

### Fixed
- Settings' model picker saved the model without its provider, so a new chat could send a stale provider; a
  profile switch now clears both.
- The chat's overflow menu did nothing on a brand-new chat; it now offers what works (Stop, Copy session ID with
  the live id) and is disabled when nothing applies.
- A malformed `ws-ticket` or `profiles/active` reply raised an NPE; it is an `ApiException` now.
- An event with `seq` 0 on a fresh session was dropped as a replay (the watermark now starts at -1).

### Build
- CI runs `testDebugUnitTest lintDebug assembleDebug assembleRelease`, uploads the debug APK and the release
  APK (`tsukaima-release-debugsigned`, R8, debug keystore) plus the lint report, and cancels superseded runs per
  ref. The Gradle wrapper pins `distributionSha256Sum`.

## 0.3.0 — 2026-10-20 (versionCode 4)

### Added
- `MEDIA:` file deliveries: a whole `MEDIA:<path>` line in a reply becomes a file card (icon, name, image
  preview); tap downloads through `GET /api/files/download` into Downloads and opens it.

### Fixed
- Cancelled downloads close their response; quoted `Content-Disposition` filenames parse; no double status poll
  at startup.
- The e2e smoke round-trips `/api/files/download`.

## 0.2.0 — 2026-10-20 (versionCode 3)

### Added
- Clarify questions answered in chat (choice chips, multi-select, "Other…", free text; Skip cancels all), with a
  "Hermes has a question" notification in the background.
- Attachments: pick files and images; images go through `image.attach_bytes`, other files through
  `file.attach` (12 MB cap per file).

### Fixed
- HTTPS fronts (Tailscale Serve, TLS proxies): the `__Host-` prefixed session cookie is accepted.
- Uploads run one at a time; queued images are marked failed when a resume yields a new live session.

## 0.1.1 — 2026-10-20 (versionCode 2)

- Adaptive launcher icon (with a monochrome layer for themed icons) from the design mark.

## 0.1.0 — 2026-10-20 (versionCode 1)

First build: onboarding and sign-in (encrypted, origin-bound credentials, single-flight re-login), status home
with gateway start/stop/restart, streaming chat over the JSON-RPC WebSocket (reasoning, tool rows, approvals with
spoofing-resistant command display), sessions drawer (rename, archive, delete; persistent pane on wide screens),
cron list and editor, logs, settings (profile, model, skills, toolsets, pairing, usage, theme, Material You),
actionable approval and gateway notifications via a background hand-off to a foreground service, reconnect with
`session.events.since` replay, release build with R8, and an e2e smoke script against a test Hermes.
