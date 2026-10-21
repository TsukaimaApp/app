# Changelog

All notable changes to Tsukaima. Versions follow `versionName` in `app/build.gradle.kts`. Decisions behind each
release are in [`docs/superpowers/2026-10-20-build-rulings.md`](docs/superpowers/2026-10-20-build-rulings.md).

## Unreleased

### Fixed
- Clarify questions: choices are full-width rows whose text wraps, so a long choice is read in full (they were
  one-line chips cut with "…", which hid what was being picked). The question notification shows up to 300 characters
  instead of 120.
- Tool rows: tapping a row open unfolds its whole command or summary instead of keeping the one-line "…" cut, and a
  row that came without a summary shows its arguments above the output. Risk and interruption notes are no longer cut
  at two lines. Subagent rows unfold their goal and detail the same way.

## 0.9.2 — 2026-10-21 (versionCode 22)

### Fixed
- Stop and queued sends: Stop no longer leaves a stale "stopping" state after a turn that had already ended (also
  after a reconnect that lost the stopped turn's end), and a Stop that races a turn's end is much less likely to mark
  a queued send "Not sent" when it actually ran (a narrow race while the server drains its queue remains). A message
  sent between turns, while an earlier queued one waits, is queued too ("Send when done" matches what happens).
- A queued send that Stop purged keeps its "Not sent" bubble (with Retry) across a reconnect or history reload instead
  of vanishing or showing twice.
- Long runs of `_` in a reply no longer freeze the app while it parses; very long text with a stray `_` parses in
  milliseconds instead of seconds.
- Dollar amounts and shell variables (`$HOME`, `a$b$c`, "costs $5 and $6") are no longer read as formulas.
- A backslash-escaped `]` inside link text (`[a\]b](url)`) now makes a link; an empty `$$ $$` block reads as the text it
  shows.
- Formulas: they no longer block the screen while they draw (a cached one shows in its first frame), an inline formula
  too wide for the line scales down to fit or, if still too wide, shows clipped with a "…" button that opens it in
  full, and TalkBack reads the formula in words ("x to the power 2", "a over b") instead of raw TeX, inline ones once,
  as part of their sentence.
- Connection card: the countdown rounds down ("2 min left", then seconds), the buttons lock while an answer is being
  sent so it goes out once, a target with a sign-in link and settings sends the settings first and then offers
  "Continue in browser", and a "Hermes suggests connecting: …" hint shows for directory requests.
- Task card: an older update arriving late (or a cleared list coming back from a stale reload) no longer overwrites a
  newer list; the transcript keeps scrolling to a card that changed in place; children of a folded done task no longer
  stay indented.
- Images: the image cache no longer loses pictures when many fail to load, one tap on a foreign image loads it
  everywhere it appears (reply and tool row), and long reply images are no longer shrunk to the tool-row size
  limit.
- A failed edit or regenerate no longer drops side notes and background-task results that arrived in the meantime.
- Report a problem: a report that finishes after you left the screen no longer pops the share sheet on a later visit;
  it says "Report ready — tap Report a problem again".
- Read-aloud with a server voice pauses less between sentences: the next sentence is synthesised while the current
  one plays (the first sentence, and one that is slow to synthesise, still wait).

## 0.9.1 — 2026-10-21 (versionCode 21)

### Changed
- The app id is now `app.tsukaima` (domain tsukaima.app), replacing `labs.suchattai.tsukaima`. Android treats this as
  a new app: uninstall the old Tsukaima, install this one, sign in again, pick it as the device assistant again if you
  used that, and point Obtainium at the new id. Nothing on the Hermes side changes; the signing key is the same, so
  later updates install over this one.

## 0.9.0 — 2026-10-21 (versionCode 20)

### Added
- Connection requests: when Hermes needs a connector, MCP server, plugin or skill connected, a "Hermes wants to
  connect" card shows each target with Connect / Skip, plus Skip all and Continue and the time left. Connect opens
  the sign-in link in the browser (Hermes is told to check again when you come back) or asks for the target's
  settings (secret fields masked). A notification asks for attention when the chat is not on screen, and a pending
  request comes back after a reconnect.
- Task checklists: the agent's todo list shows as a "Tasks" card that updates in place (pending, in progress, done,
  cancelled; subtasks indented; done items fold away in long lists) and is restored when you reopen the chat.
- Math in replies: `$…$`, `$$…$$`, `\(…\)` and `\[…\]` render as formulas; a formula that cannot be drawn
  shows as code. Math is never read aloud.
- Task lists in replies: `- [ ]` and `- [x]` items show as checkboxes.
- The image `vision_analyze` looked at shows on its tool row (tap to enlarge); images from another host load on tap,
  and a load that dropped can be retried with a tap.
- Send while a turn runs: the message is queued and runs after the current turn ("Queued" under the bubble; the
  composer shows Stop and "Send when done"). This also applies while a turn waits on an approval, question,
  password or connection.

### Changed
- Hardware Enter while a turn runs now queues the message instead of doing nothing.

### Fixed
- Backslash escapes in replies (`\*`, `\_`, `\[`, `\$`, …) show the character instead of the backslash or
  formatting.
- A command you stopped shows "Interrupted" (amber) with its partial output instead of "Failed"; a tool still running
  when you press Stop ends Interrupted too. In a reopened chat, a file edit or skill change Hermes reports as
  interrupted is marked "May have partially run — check before retrying" or "Did not complete".
- Stop no longer leaves queued messages looking sent: Hermes drops them, so they show "Not sent" with Retry (their
  images and PDFs went with them — attach those again). A failed turn or interrupting a spoken reply keeps them
  queued, and they run next.

### Security
- TeX that loads files, defines macros or changes global renderer state (`\includegraphics`, `\input`,
  `\newcommand`, `\def`, `\DeclareMathOperator`, …) or runs over 2000 characters is shown as code, never rendered.
- Inline (`data:`) images over 25 MiB are refused before decoding; image previews decode at most 2048 px on the
  long side.
- Tool images are fetched with your token only from the Hermes origin (no redirects); images from any other host load
  only when tapped.

## 0.8.1 — 2026-10-21 (versionCode 19)

### Fixed
- The on-screen keyboard's Enter key inserts a newline again; the Send button sends. A physical keyboard still
  sends on Enter and inserts a newline on Shift+Enter. (0.8.0 asked the keyboard for a "Send" action, which took
  the newline key away.)

## 0.8.0 — 2026-10-21 (versionCode 18)

### Added
- Edit and Regenerate: long-press your message to edit it, or a reply to regenerate it; Hermes rewinds the chat to
  that point and runs the turn again. Editing a message that carried attachments resends its text only;
  Regenerate is not offered for a message that sent images.
- Side questions and background tasks: the composer's overflow menu sends the text as a side question (answered
  over a snapshot of the chat, without joining it) or as a background task; the answer appears as a card in the
  chat.
- PDF attach: a picked PDF is sent as page images for the next message (first 25 pages); the chip shows the page
  count.
- Images in replies: a markdown image on its own line renders inline (tap opens it in the browser).
- Settings → Support → Report a problem: shares a text report with the app and Android version, the device, the
  server host (never your username, password or token), the last crash and the app's own recent log. After a
  crash, Settings shows "The app crashed last time" with Report / Dismiss.
- Voice processing "On Hermes" now also speaks replies with Hermes' text-to-speech (`/api/audio/speak`).
- Recordings for Hermes recognition upload as Opus (16 kB instead of 276 kB of WAV for a 5-second clip); WAV
  when the phone has no Opus encoder.
- Hermes' password-manager prompts are answered in the chat: "Enter the code" for a one-time code, and a
  "Save login" card (username or email, password) when the agent offers to store a login; either can be declined.
- Spoken turns are flagged as voice turns, so Hermes can use its voice-chat model and persona; a turn sent after
  you interrupted a spoken reply tells the agent it was interrupted.

### Changed
- Settings → Voice: one "Voice processing" switch (On this phone / On Hermes) decides where both recognition and
  speech run, replacing the separate Speech recognition row; a 0.7.x choice of On Hermes is kept.

### Fixed
- In a loud room, server recognition no longer keeps uploading 30-second clips that come back empty: after two in
  a row voice stops with "Too noisy to hear you — tap to retry".
- Android 10 and 11: while Tsukaima is the assistant, its recogniser now forwards to the system one, so voice
  input from the assistant gesture and other apps keeps working.
- A finished side question no longer scrolls the chat; an empty answer reads "No answer".
- Ending voice mode while Hermes is still synthesising a sentence no longer lets it play afterwards.
- An image that failed with a server error (5xx or 429) is retried when shown again instead of staying broken.

### Security
- A reply image on the Hermes origin that answers 401 fails quietly: it never re-logins with the stored
  password and never sends the app to sign-in.
- Images from a host other than your Hermes server never load until you tap them (the card shows the host), so a
  reply cannot send data out through an image URL; images from the server itself no longer follow redirects to
  another host.
- The assistant recogniser serves only apps that hold the microphone permission themselves.
- The save-login card shows the full origin when it differs from the site name, so a lookalike label cannot pass
  for another site.

## 0.7.1 — 2026-10-20 (versionCode 17)

### Fixed
- Replies render GFM tables (scrollable when wide), headings, numbered and nested lists, block quotes, horizontal
  rules, links (tappable) and strikethrough; previously these showed as raw markdown.

## 0.7.0 — 2026-10-20 (versionCode 16)

### Added
- Settings → Voice → Speech recognition: On this phone (default) or On Hermes — the phone records one utterance
  (local end-of-speech detection), uploads it as WAV and Hermes transcribes it with its configured STT (Whisper);
  the voice language picker then applies to phone recognition and speech only. The voice status line shows
  “Transcribing…” while Hermes works on the utterance.

## 0.6.2 — 2026-10-20 (versionCode 15)

### Fixed
- Picking a voice language other than the device’s own sent recognition to the on-device recogniser (which only
  has the device language) with an offline preference, so other languages failed or were recognised as the device
  language. Explicit languages now use the default, network-capable recogniser; on-device recognition is kept for
  the device language.

## 0.6.1 — 2026-10-20 (versionCode 14)

### Added
- Voice language picker (Settings → Voice → Language, and the translate button on the voice screen). One setting
  drives both speech recognition and text-to-speech; System default unless picked. A language the text-to-speech
  engine has not installed falls back to its default voice with a notice.

## 0.6.0 — 2026-10-20 (versionCode 13)

### Added
- In-app voice mode: tap the composer's mic to talk to the agent hands-free. Replies are read aloud sentence by
  sentence as they stream; End returns to the chat with the transcript already in the session. Voice never
  answers approvals, questions or password prompts.
- Assistant slot: Tsukaima can be the device assistant (Settings → Voice → "Use as device assistant"). A
  long-press on home shows a sheet for one spoken exchange; "Continue in Tsukaima" opens the chat.
- Settings → Voice row; new `RECORD_AUDIO` permission, asked on the first mic tap.
- Limits: no hotword, nothing on the lock screen. On Android 10 and 11 the assistant role also makes Tsukaima's
  stub the default speech recogniser (other apps' speech input stops); Android 12+ is unaffected. See the README.

### Changed
- The composer's accent circle shows a mic while the field is empty; Send appears once there is text or an
  attachment.

## 0.5.6 — 2026-10-20 (versionCode 12)

### Added
- Cron-run sessions (`source: "cron"`) are listed in their own "Cron runs" section of the drawer, collapsed by
  default with a count; they no longer hide among chats and are fetched as a separate list (`source=cron`), so
  the server's 100-row cap applies to chats and runs independently. Search hits from cron runs are labelled.

## 0.5.5 — 2026-10-20 (versionCode 11)

### Fixed
- The status-line meter showed Hermes' cumulative `usage.total` (input+output summed over every call, so "3M
  tok" in a long session). It now shows the context window: "15.8k / 1M ctx · 2%".

## 0.5.4 — 2026-10-20 (versionCode 10)

### Fixed
- The model picker scrolls (it listed every model of every provider in one unscrollable column) and is now a
  drill-down: provider → model → flavor. `melious/deepseek-v4.1-flash:speed` is picked as melious →
  deepseek-v4.1-flash → speed; a model without `:flavor` variants picks directly; a single provider skips its
  level. Every bottom-sheet menu scrolls when it is taller than the screen.

## 0.5.3 — 2026-10-20 (versionCode 9)

### Fixed
- Reasoning blocks no longer fold on their own. Reasoning streamed in this session shows open until you close
  it; the open/closed choice lives in the chat state (per message), so list recycling, a new bubble after a
  tool call, or a reconnect cannot collapse a block you are reading. History still loads with reasoning closed.

## 0.5.2 — 2026-10-20 (versionCode 8)

### Changed
- The "Thinking…" status flair now rotates every 2.6 s (the design's `StatusLine.jsx` interval) instead of on
  every streamed delta, which re-rolled it many times a second.

## 0.5.1 — 2026-10-20 (versionCode 7)

The Tier 3 batch (release hygiene) of the completeness pass, plus the deferred S-size minors. No new features.

### Security
- **Release signing from a local keystore**: `assembleRelease` signs with `.local/tsukaima-release.jks` when
  `.local/keystore.properties` exists, else (CI) with the debug key and a build warning. `*.jks`, `*.keystore` and
  `keystore.properties` are git-ignored. The first release-key install over a debug-signed build needs an uninstall.
- **Cleartext guard**: plain `http://` is refused, before anything is sent, for hosts outside loopback, RFC 1918,
  100.64/10, `*.ts.net` and single-label names (`CleartextGuard` on every request and redirect hop; the WebSocket
  does not connect). Settings and first run say so inline on Test connection and Sign in. Before, the bearer token
  went to any http host.
- `network_security_config.xml`: cleartext stays permitted (the guard decides per host), trust anchors are the
  system CAs only (user-installed CAs are no longer trusted).
- `data_extraction_rules.xml` / `full_backup_content.xml` exclude everything (`allowBackup` stays false).
- The bearer and the origin it is bound to are read in one consistent snapshot.

### Changed
- With every notification kind switched off, the app no longer keeps the foreground service (and the socket)
  running in the background; switching a kind back on hands off again.
- `vault.save_login` is declined with 4404 (another attached window may still answer) and `vault.code` answered
  "no code entered", instead of JSON-RPC "method not found".
- "Simulate Hermes unreachable" is in debug builds only.
- Approval card: Deny is a secondary button with danger ink, as designed. The attachment chip's remove button is
  a 44 dp target. The onboarding wisp uses the design's dark asset in dark mode, untinted at 80 %.
- Pairing Approve / Revoke toast their outcome and ignore a double tap (Settings and Status).
- The Status offline card names the host; the stop confirmation lists platforms as "A, B and C".
- The Logs filter chips wrap on narrow screens.
- Markdown: a one-line ```` ```x``` ```` is inline code (it used to swallow the rest of the message as a code
  block); a 4-backtick fence closes, and can hold a ```` ``` ```` fence; inline code spans use matching backtick runs.
- Code block "Copied" restarts its 1.5 s on a re-tap.
- Cron schedules are validated per field (`+5 * * * *` is refused), still five fields.
- JSON-RPC: a pathologically deep frame is dropped instead of failing the connection; a string `seq` is ignored; an
  `"error": null` response without a `result` is an error, not an empty success. `session.most_recent` with a
  non-string id is "none". A quoted session cookie value is unquoted.
- The e2e smoke requires a new `session.info` within 10 s of `config.set` (it used to pass vacuously).

### Removed
- Dead code: `GatewaySession.list` / `SessionSummary`, the placeholder screen, unused `material3-adaptive` entries;
  the file-wide deprecation suppression in `CredentialStore.kt` (now on the one call that needs it).
- Lint: 38 → 18 warnings, all of them dependency-version notices.

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
