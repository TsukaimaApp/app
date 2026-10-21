# Completeness pass — 2026-10-20, feat/android-app @ a5a0448 (v0.3.0)

Four read-only audits (design ↔ UI, protocol ↔ data layer, hygiene/release, plan+rulings ↔ code) plus a
lint run (40 warnings, 19 of them "newer version available"). Claims marked ✔ were re-verified by hand.
Paths are under `app/src/main/kotlin/app/tsukaima/`.

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

- ~~**Release signing uses the debug keystore**; no `keystore.properties`/`*.jks` ignore rules. `app/build.gradle.kts:25`.~~ *(closed in 0.5.1)*
- ~~**`usesCleartextTraffic="true"` globally**, no network-security-config; only the password re-login replay is host-gated (`TrustedHost`). Bearer still goes to any http host. `AndroidManifest.xml:16`.~~ *(closed in 0.5.1)* (`CleartextGuard`; config keeps cleartext permitted, system CAs only)
- `FOREGROUND_SERVICE_DATA_SYNC`: `onTimeout` is handled ✔; Play would need a declaration form (sideload only for now).
- **Strings**: `strings.xml` has only `app_name`; ~39 UI literals + VM toasts hardcoded. Fine for a personal app, blocks localisation.
- ~~**Accessibility**: 36 dp targets in `Composer.kt:188` (attachment remove) and `MediaCard.kt:125`; `TsukaimaIcon` default has no contentDescription.~~ *(closed in 0.5.1)* (MediaCard: the whole ≥44 dp card is the target; the 36 dp box is a non-interactive icon well)
- ~~**Dead code**: `GatewaySession.list` + `SessionSummary`, `PlaceholderScreen` (`TsukaimaNavHost.kt:231`) + stale KDoc at `:62`, unused `material3-adaptive` deps in `libs.versions.toml:12,24`, `@Suppress("UNUSED_PARAMETER")` in `SettingsViewModel.setModel`.~~ *(closed in 0.5.1)*
- ~~"Simulate Hermes unreachable" (kit-only per handoff) ships in release Settings. `SettingsScreen.kt:260-266`.~~ *(closed in 0.5.1)*
- ~~Deny button is `GhostButton(Danger)`; design says secondary with red ink. `ApprovalCard.kt:185`.~~ *(closed in 0.5.1)*
- ~~Dark wisp asset (`design/assets/resting-wisp-dark.svg`) unused; light one tinted instead.~~ *(closed in 0.5.1)*
- Untested logic: `ChatReconnector`, `ChatAttachments` (only via VM tests), `NotificationActionReceiver` approve path, `SessionBindings`, `SessionsSource`, `ImagePreview`, `CredentialStore` proper.
- `GatewayClientTest.kt:314,327` use `Thread.sleep(200)`.
- ~~Lint: 9 `ModifierParameter`, 4 `UseKtx`, `allowBackup` deprecation (add `dataExtractionRules`), `Configuration.screenWidthDp` ×2, vector >200 dp (`resting_wisp.xml`), `InlinedApi` (POST_NOTIFICATIONS), one non-lambda `offset`.~~ *(closed in 0.5.1)* (18 warnings left, all dependency versions)
- **Docs stale**: `README.md` describes v0.1.0 (no clarify/attachments/MEDIA/HTTPS, no version, "models and toolsets screens" are Settings sections, no screenshots section); no CHANGELOG; `docs/protocol/README.md` §2 says clarify cancel is `{"answers":null}` (it is `{}`), calls `approval.respond` REST, omits `profile` param and the attach RPCs, §3 `/api/sessions` lacks the 100 cap and `archived`/`exclude_sources`; rulings doc still lists "clarify hint lost when backgrounded" (fixed in 0.2.0).

## Deferred minors still open (from the rulings doc, effort S unless noted)

Struck through: closed in 0.5.1 (see the rulings doc, "v0.5.1 rulings").

T1 CI concurrency group / wrapper checksum · T3 focus rings on buttons/chips, `MenuSheet` shadow (M) · T4 URL tests for IPv6/ftp/userinfo, ~~file-wide `@file:Suppress` in `CredentialStore.kt`~~ · ~~T5 quoted cookie value not unquoted~~, re-login lock held across login, no 429 cooldown · ~~T6 `JsonRpc` catches `Exception` not `Throwable`, `"12"` accepted as seq, `{"error":null}` decodes as success~~ · T7 `GatewayClient` 419 lines, request between `awaitConnected` and a drop fails instead of waiting (M) · ~~T8 `mostRecent` throws on a non-primitive id~~ · T9 reasoning can carry into the next reply when a turn ends without a final event · T10 ~~```` ```x``` ```` swallows the rest, 4-backtick fence never closes~~, O(n²) `_` scan · ~~T11 origin/token read non-atomically, bearer sent to untrusted http hosts (M)~~ · ~~T12 `inline()` not remembered, Copy timer not restarted~~, blank tool name renders an empty row, CRLF commands show ⟨U+000D⟩ · ~~T14 pairing approve/revoke no feedback or double-tap guard~~, onboarding doesn't scroll in landscape · ~~T15 offline caption lacks host, "a and b" platform join~~, action row doesn't wrap, no refresh on return, restart compares a cached `gatewayUpdatedAt` · T16 archive/delete don't close the live session first, model pick before session exists only toasts (M) · T17 cron `now` only refreshes on list change, ~~`+5 * * * *` accepted~~ · ~~T18 chip rows are `Row` not `FlowRow`~~ · v0.2.0 retry after a lost `image.attach_bytes` can double-queue (M) · v0.3.0 octet-stream downloads open nothing (no extension→MIME lookup), two cards for one path share state, MediaStore extension rename unchecked, smoke leaves its upload on the server.

## Verified fine (skip re-checking)

Credentials in EncryptedSharedPreferences with in-memory fallback; `allowBackup=false`; no TrustManager/HostnameVerifier overrides; no body logging; approval/pairing/agent channels `VISIBILITY_PRIVATE` with public versions; only `MainActivity` exported; PendingIntents immutable; WS ticket single-use 30 s in query (as the SPA does); 401 single-flight re-login with latch; reconnect backoff 1/2/5/10 s with fresh ticket; `session.events.since` replay with truncation → history rebuild; foreground service hand-off; `onTimeout` handled; adaptive + monochrome launcher icon complete; manifest permissions all exercised; R8 + resource shrinking with serialization/OkHttp keep rules; `.gitignore` covers `.local/`, `*.env`, `local.properties`; smoke script refuses the real instance and never prints the password; no TODO/FIXME/println/printStackTrace/commented-out code in main; every design screen and state is implemented (onboarding, status incl. offline/cached, chat with all cards, drawer incl. archived + long-press, two-pane ≥720 dp, cron list/edit, logs, settings, dark mode, toast).

## Voice (v0.6.0)

Shipped: two voice surfaces from `design/handoff_voice/` — an in-app voice overlay inside the chat screen (entered
from the composer's mic, sharing `ChatViewModel`) and the Android assistant slot (a `VoiceInteractionService`
whose session opens a translucent `AssistActivity` sheet). Both run on one voice controller over platform
`SpeechRecognizer` and `TextToSpeech` adapters; the transcript is the session, and approvals, questions and
password prompts stay a tap.

Deliberately not there: a hotword; assist from the lock screen; per-word reveal of spoken text (the reveal is per
sentence). (The Android 10/11 stub recogniser became a forwarding one in v0.8.0: it relays to the system
recogniser instead of failing.)

### Open (deferred minors)

- Controller: no timeout if the status never leaves Idle after a sent final; a message rebuilt under the same id
  mid-stream is re-spoken; stop() during the hand-off line drops the approval callback; assist hand-off is once per
  id, not strictly the first card.
- Speech adapters: stop() does not end a multi-chunk speak (> 4000 chars); a cancelled speak can stop a following
  one (no join); the TTS engine is never shut down.
- UI: haptic can fire again after rotation; orb ring easing is not `Motion.easing`; `ChatScreen` is 639 lines
  (split transcript items and sheets); `onVoice?.invoke()` null branch is unreachable; swipe-down dismiss works
  from the sheet body only.
- Tests: thin beyond the pure parts (sendText "Still connecting", onCleared, mute/tap delegation, voicePhase for
  Compacting/Stopped/Question/Secret, queued messages, barge-in mid-stream); no whitespace-only composer test.
- Chunker: a same-line "```code```" at line start opens a fence that never closes; inline code containing `*` or
  `_` is unwrapped before emphasis stripping.

## v0.8.0 (2026-10-21)

Closed in this release:

- Chat: edit and regenerate (rewind via `truncate_before_row_id`), side questions and background tasks
  (`prompt.btw` / `prompt.background`, `btw.complete` / `background.complete` handled — previously listed as
  unhandled in Tier 2), PDF attach (first 25 pages as images), images in replies (foreign origins on tap only),
  vault one-time code and save-login prompts answered in chat (no longer declined).
- Support: Settings → Support → Report a problem, and a crash banner after a crash.
- Voice: spoken turns flagged `voice_turn` / `interrupted`; one "Voice processing" switch for recognition and
  speech (migrates 0.7.x `voice_recognition`); server speech output through `/api/audio/speak`; Opus uploads with
  a WAV fallback; the loud-room guard; the Android 10/11 forwarding recogniser (callers must hold RECORD_AUDIO).
- Tests: Robolectric Compose UI tests (composer, markdown table/links, Settings → Voice, voice screen, an a11y
  walk of the chat screen).
- Final-wave fixes: a completing side note no longer scrolls the chat and an empty one reads "No answer"; side
  entries hidden while editing; same-origin image fetches do not follow redirects; 5xx/429 image failures retried;
  server speech `stop()` drops a sentence still being synthesised.

Still open:

- **Localisation** — deferred by the user (UI strings are still literals).
- **In-app updates** — out of scope by decision (the user updates through Obtainium).
- **Device checks pending**: Opus output on a real device (first upload starts with `OggS`, logcat shows
  `audio/ogg`, and it transcribes), the Android 10/11 forwarder, server TTS playback, long-press on links, the
  server-recognition end-of-speech thresholds.
- Editing a message that carried attachments resends its text only; Regenerate is not offered after a message
  that sent images.
- Mixed voice setups (phone recognition + server speech, or the reverse) are not a supported configuration.
- The forwarding recogniser does not relay `segmentResults` on API 33+.
- Smaller deferred review items (test gaps, KDoc line lengths, edge cases such as a PDF retry toast that says
  "images") are tracked outside the repo.

Device-check list from the final whole-branch review (only a phone can verify these), most important first:

1. Server voice end to end: a 3+ sentence reply plays via Piper; a tap while speaking stops at once and the next
   sentence never plays; End voice mid-synthesis plays nothing afterwards; music ducks and returns after the last
   sentence.
2. The first server-recognition upload is Opus (logcat `audio/ogg`, body starts `OggS`) and transcribes; a phone
   without the encoder falls back to WAV in the same run.
3. Loud room: two 30-second empty utterances show "Too noisy to hear you — tap to retry"; the tap listens again with
   two fresh chances.
4. Android 10/11 as the assistant: the assistant gesture recognises speech through the forwarder; another app's
   `SpeechRecognizer` works; an app without RECORD_AUDIO gets `ERROR_INSUFFICIENT_PERMISSIONS`.
5. Edit and Regenerate on a live chat and after a kill-and-reopen; editing the first message; Regenerate hidden on
   an image-bearing message.
6. A side question mid-turn with the phone locked until it completes; a background task across a reconnect keeps
   its question on the answer card.
7. PDF attach: a 30-page PDF shows "25 pages" and "First 25 pages attached"; removing the chip detaches every page;
   an unreadable PDF fails with the server's detail.
8. Reply images: same-origin loads with the bearer; foreign shows "From <host> — tap to load" and loads on tap;
   a 404 shows the alt text; an image over 8 MB fails cleanly; a same-origin 401 fails quietly without a sign-in.
9. Report a problem: the share sheet opens with the text file and a host-only header; after a forced crash the
   banner appears on the next launch and Dismiss clears it.
10. Upgrade from 0.7.1 with "Speech recognition: On Hermes" shows "Voice processing: On Hermes" and runs voice on
    Hermes both ways.
11. `vault.code` / `vault.save_login` from the browser tool: the cards render (number keyboard for a digits hint),
    Decline / Don't save answer `""`, the notification reads "Hermes needs your attention", and the tool proceeds
    with the typed code.

Parked from the final review (rulings in the SDD ledger): failed images share the 8-entry cache with decoded
bitmaps (refetch churn on image-heavy replies); an edit/regenerate failure restores the pre-edit snapshot and can
drop an event that landed during the request; a problem report requested just before leaving Settings can open the
share sheet on the next visit; server speech has no sentence prefetch (one round trip per sentence).


## v0.9.0 (2026-10-21)

From the user's session-testing report (`docs/superpowers/2026-10-21-e2e-gaps-report.md`), all eight closed:

1. **`connection.request` not implemented** (connector tools hung ~5 min) — "Hermes wants to connect" card with
   Connect (browser link + wake on return, or env fields) / Skip / Skip all / Continue, countdown, notification,
   restored from `pending_connection`. Live probe: Skip all settled in 0.01 s, tool completed 0.58 s later.
2. **`todo.updated` ignored** — "Tasks" checklist card, updated in place by revision, seeded from `todo_state` on
   resume; the smoke's `todo_part` checks the event live.
3. **LaTeX shown raw** — JLaTeXMath rendering (inline, display, table cells), safety gate, code fallback, never
   spoken.
4. **Task-list checkboxes** — `[ ]` / `[x]` items render as ☐ / ☑ with a state description.
5. **Backslash escapes shown literally** — CommonMark escapes for ASCII punctuation.
6. **`vision_analyze` image never shown** — thumbnail on the tool row via `/api/media` (paths), inline `data:` or
   http(s) by the reply-image rules; tap to enlarge / load / retry.
7. **No queueing while a turn runs** — sends mid-turn are `queued: true` ("Queued", "Send when done"); Stop
   turns them into "Not sent" with Retry; a failed turn keeps them queued.
8. **Interrupted tool looks like an error** — amber "Interrupted" with partial output; orphan-recovery notices
   in a tool row's history result become a warning line on that tool.

### Still open

Device checks (only a phone can verify these):

1. Connection card: a real connector (e.g. linear) — Connect opens the browser, coming back wakes the operation and
   the row turns connected; an MCP target with required env fields (secret masked) approves; Skip all and Continue
   settle; the notification appears with the chat off screen and clears on settle.
2. Todo card: a multi-step task builds the checklist, statuses update in place, more than 6 rows fold done items,
   the card survives a kill-and-reopen.
3. LaTeX in the **release** APK (R8 keep rules were added on mapping evidence only): `$$\frac{1}{2}$$` and an inline
   `$x^2$` render; `\input{/etc/passwd}` shows as code.
4. vision_analyze thumbnails: a screenshot under `~/.hermes/screenshots` loads through `/api/media`; a foreign
   http(s) image waits for a tap; tap to enlarge; airplane mode → "tap to retry" works.
5. Queued send + Stop: a send mid-turn shows "Queued" and runs after the turn; Stop turns it "Not sent" and Retry
   sends it; a send while an approval waits does not kill the turn.
6. Interrupted rows: Stop during a long `terminal` command shows amber "Interrupted" with the partial output.

Known gaps:

- `display.suggested` (directory) connection cards render without rows; Continue settles them.
- Orphan-recovery notices reach the client only for `write_file`, `patch` and `skill_manage` rows in history — the
  Hermes history projection drops other tools' content (Hermes side; source reading, unprobed).
- `$HOME/$USER` in prose (outside backticks) reads as math.
- TalkBack reads raw TeX (the formula image's description is its source).
- No Robolectric UI test for the connection card (reducer, VM and RPC paths are unit-tested).

## v0.9.2 (2026-10-21)

The known-gaps pass over the parked lists of the v0.8.0 and v0.9.0 passes.

### Closed

- Queue and Stop edge cases: stop flag lifecycle, "Send when done" between turns, a purged send keeps its Not-sent
  bubble across a rebuild or reload.
- Markdown: the O(n²) `_` scan (T10) is linear (memoised closers); `$HOME/$USER` and `a$b$c` no longer read as math;
  escaped `]` in link text; empty display math reads as text in both chat and speech.
- Math UX: formulas draw off the main thread and a cached one in its first frame; inline formulas scale to fit or clip
  with a full-size dialog; TalkBack reads formulas in words (the "TalkBack reads raw TeX" gap).
- Connection card: countdown rounding, answers sent once, env then "Continue in browser", suggestion hint for
  `display.suggested` requests, pending request restored after a real reconnect (unit-tested).
- Todo card: stale revisions ignored, tail key follows the card, hidden done parents do not indent children.
- Images: failures no longer evict bitmaps, one tap loads a foreign URL everywhere, reply previews are not
  strip-capped, inline `data:` keys hashed once.
- v0.8.0 leftovers: edit/regenerate failure keeps late events; Report a problem no longer shares into a later visit;
  server TTS prefetches the next sentence.

### Still open

Decisions (kept out on purpose): localisation, in-app updates, mixed voice setups, `segmentResults` on API 33+.

Hermes-side limits:

- Orphan-recovery notices reach the client only for `write_file`, `patch` and `skill_manage` rows (the history
  projection drops other tools' content).
- `display.suggested` (directory) connection cards show only the "Hermes suggests connecting" hint; there is no
  directory UI beyond it.

Parked (not worth a wave, listed so they are not rediscovered):

- Stop-ack race where the server catches the interrupt before draining: with several clients it can mark a fresh
  queued send "Not sent" (duplicate on Retry).
- A Stop tapped between turns after the server purged a never-started queued send leaves it "Queued" until a new live
  session (needs a probe).
- A retried matched twin leaves the original history row active (an extra unanswered copy on a later reload); an
  anchor after an earlier queued send drained falls back to append and may show a send twice if Hermes keeps purged
  accept rows active (unverified).
- Perf tests: one `println`; the `*a ` and `[a ` inputs are guards, not RED evidence.
- Formulas: a reused whole-fit bitmap is double-counted in the LRU (about half capacity for those formulas, memory
  stays bounded); input nested past the depth limit reads command names flat.
- Connection card: `typed.clear()` also runs when the VM silently drops an answer; the env-sent mark is lost on a
  rebuild (retype before the browser leg); env fields stay editable while an answer is in flight; the failed env
  send clears typed values; test gaps (disabled Skip not asserted, no Robolectric test for the card).
- Todo: no nested-hidden-ancestor test, collapse threshold not asserted in the UI test.
- Images: a URL shown in a reply and a tool row is fetched twice (deliberate); weak observability assertion.
- Server TTS: two speak requests in flight (current and next); a prefetched file can linger after a reconnect rebuild
  until the next sentence or stop; a report is dropped on any pause.

Device checks (only a phone can verify these):

1. Extreme-strip reply images (very tall or wide) on GPUs with a small max texture size.
2. Formulas in the release APK (R8): `$$\frac{1}{2}$$`, an inline `$x^2$`, a clipped wide formula with its dialog.
3. Connection card with a real connector (Connect, return, wake, env fields, Skip all, Continue, notification).
4. Queued send plus Stop: Queued label, Send when done between turns, Not sent plus Retry after a reconnect.
5. Todo card across a kill-and-reopen with revisions still ordered.
6. Server TTS prefetch latency: the gap between sentences with a real voice.
