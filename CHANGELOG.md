# Changelog

All notable changes to Tsukaima. Versions follow `versionName` in `app/build.gradle.kts`. Decisions behind each
release are in [`docs/superpowers/2026-10-20-build-rulings.md`](docs/superpowers/2026-10-20-build-rulings.md).

## 0.5.0 — 2026-10-20 (versionCode 6)

The Tier 2 batch of the completeness pass ([`docs/superpowers/2026-10-20-completeness-pass.md`](docs/superpowers/2026-10-20-completeness-pass.md)).

### Added
- **Live model switch**: the chat's model sheet switches the open session (`config.set key=model`, value
  `"<model> --provider <slug> --session"`) and saves the pair for new chats once the server took it. A switch
  picked mid-turn applies at the next turn ("Model switches when this turn ends"); server advice (`warning`) is
  toasted; a model the server's guards flag (`confirm_required`) asks for confirmation first. Without a live
  session the pick still only applies to the next chat. The v0.4.0 close-and-re-create of an empty chat is gone.
- **Reasoning effort** in the chat overflow ("Reasoning · Low"): Off, Minimal … Ultra, session-scoped
  (`config.set key=reasoning`); the current value comes from `session.info.reasoning_effort`.
- **Context meter** in the chat's status line ("2% ctx · 31.5k tok") from `session.usage`, `message.complete`
  and `session.info` `usage`; **Compact context** in the overflow (`session.compress`, between turns), with
  "Compacting context…" in the status line and the server's headline as a toast.
- **Archived chats are read-only**: a banner above the disabled composer with **Unarchive**
  (`PATCH /api/sessions/{id} {archived:false}`); the overflow offers Unarchive instead of Archive.
- **Retry a failed send**: the bubble stays, marked "Not sent", with Retry (same text and attachments) and
  Dismiss (back into the composer).
- **Copy a message**: long-press a user or assistant bubble → Copy text / Copy reasoning.
- **Subagent rows** for `subagent.*` events (goal, running / finished / failed, latest progress, summary).
- `tool.output_risk` shows an amber "Output flagged: … risk" note under the tool row; `session.resume_progress`
  shows under the loading skeleton; `notification.show` notices are toasted once.
- **Drawer search** through `GET /api/sessions/search` (2+ characters, 300 ms debounce); the list returns when the
  query is cleared.
- **Live refresh**: `sessions.changed` reloads the drawer and `platforms.changed` the Status screen, debounced
  1 s, only while on screen.
- **Notification settings**: approvals and questions, pairing, finished replies, gateway and cron alerts, each
  switchable. Cron failures now also cover `delivery_failed`, `blocked_config` and `timeout`.
- **Toolsets are toggleable** in Settings (`PUT /api/tools/toolsets/{name}`), optimistic with revert.
- Pairing **Revoke** asks for confirmation and toasts a refusal.

### Changed
- The e2e smoke also GETs `/api/sessions/search` and `/api/sessions/{id}` and round-trips `config.set key=model`
  with the session's own model.

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
