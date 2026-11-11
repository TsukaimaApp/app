# Changelog

All notable changes to Tsukaima. Versions follow `versionName` in `app/build.gradle.kts`. Decisions behind each
release are in [`docs/superpowers/2026-10-20-build-rulings.md`](docs/superpowers/2026-10-20-build-rulings.md).

## 0.14.1 — 2026-11-11 (versionCode 33)

Not checked on a phone.

### Changed
- **Settings is a short list of pages.** It was one page about fifteen screens long, most of it a hundred skill
  switches. Now it lists ten topics, each with its state in one line ("Skills · 96 of 104 on"), and a tap opens
  that topic on a page of its own. Hermes' settings come first (Connection, Model, Skills, Toolsets, Pairing), then
  the phone's (Notifications, Voice, Offline, Appearance, Diagnostics and support). Profile is on the Connection
  page. Nothing was removed.
- **Skills page**: a search field (name, description or category), All / On / Off, and the skills grouped by the
  server's category (by first letter where a skill has none).
- **The status line on your turn** reads "Hermes awaits, ready to go all out" (the underlined part is still the
  reasoning picker). It read "Hermes is listening, thinking hard", which sounded as if Hermes were thinking already,
  and was wrong in an empty chat. The eight levels, Off to Ultra: taking it easy, barely lifting a finger, just
  warming up, getting serious, ready to go all out, holding nothing back, at full power, going beyond its limits.
- **Wide screens** (an unfolded phone, a tablet): held on its side, Settings shows the list on the left and the
  open page on the right. Held upright it starts as one column, no wider than a phone's. A button in the top bar
  switches between the two.

## 0.14.0 — 2026-11-11 (versionCode 32)

Not checked on a phone.

### Added
- **The chat opens without Hermes.** The phone keeps a copy of each chat you have opened and of the chat list.
  With no connection, the drawer lists your chats and a chat shows its copy under a bar that reads "Offline · saved
  copy from 14:02". Messages, reasoning and finished tool rows are kept; approval cards, notices and the task list
  are not. The copy is replaced by Hermes' history as soon as the connection is back. Signing out deletes every
  copy.
- **Write while offline.** The composer stays usable without a connection. A message you send waits under the chat
  ("Sends when you are back online", with Cancel) and goes out by itself when Hermes is reachable again. If that
  takes longer than 10 minutes the message stops waiting and returns to the composer as a draft, with a notice.
  Settings › Offline sets the wait: Never, 1, 5, 10 or 30 minutes, or 1 hour. Never keeps every offline message as
  a draft. Text only: attachments and voice need a connection. A message waits only while its chat is open or the
  app is alive; one whose wait ran out while the app was closed is a draft when you open the chat.
- **Drafts are kept per chat.** What you typed is still in the composer after you switch chats, or after Android
  closes the app.
- **Commands the agent left running.** While background commands run, the status line shows "2 running" in place of
  the context meter. Tap it for the list: each command with its running time and last output line, Output for its
  live output, and Stop for that command alone. A command that ended lately stays listed ("Finished", "Exit 1",
  "Stopped").
- **Code blocks**: Wrap switches long lines between wrapping and sideways scrolling, per block. A block of 16 lines
  or more has a second Copy at its end.
- Problem reports include a "Background connection" section: when the background service started, when Android's
  time limit or a kill ended it, and when the app process began. It holds no message text.

### Changed
- The notification for a reply that finished in the background names the background work that caused it
  ("Background Process Finished: npm run build") when Hermes reported one. Before, it always read "Hermes finished
  the task".
- With no connection the status line is the Offline bar; before, it read "Can't reach Hermes" and the composer was
  disabled with "Hermes is unreachable".

### Not done
- Recovery after Android ends the background connection (its time limit on Android 15, or a kill). The app still
  goes quiet until you open it. The new report section is there to find out when and why that happens on a real
  phone before the behaviour is changed.

## 0.13.0 — 2026-11-11 (versionCode 31)

Not checked on a phone.

### Added
- **Time marks** between messages ("Today 09:02", "Yesterday 21:14", "Tue 3 Nov 18:05"), above a message that
  starts a new day or follows the previous one by an hour or more. 12- or 24-hour as the phone is set. Times of
  earlier messages come from the server's history; a message sent now is stamped by the phone.
- **Earlier turns fold their work.** In every turn before the current one, a run of three or more steps (tool calls
  and the reasoning between them) is one line: "6 steps · 4 tools · 1 failed". Tap it to unfold the steps, tap
  again to fold. The current turn is never folded, so nothing moves while the agent works; its steps fold when you
  send the next message.
- **How long a tool ran**, beside the check mark of a finished tool row ("3.2s", "2m 5s"). A row loaded from history
  has none: Hermes does not store it.
- **Latest** button over the bottom corner of the chat while you are scrolled up. A tap goes back to the newest
  message and follows it again. A dot on it says something arrived below since you left.

### Changed
- **Reasoning is a margin note**: an accent rule down the left, a header and the text in serif italic. In 0.12.0 it
  was small grey text under the word "Reasoning". The header reads "Thinking… 0:12" while reasoning streams and
  "Thought for 12s" after ("Thought" for reasoning loaded from history, which is not timed). Folded, the block
  shows two lines: the newest while it streams, the first once done. Tap it to read all of it.
- Reasoning now starts folded to those two lines. Since 0.5.3 reasoning streamed in the open chat stayed fully
  open until you folded it by hand. A block you opened stays open.

## 0.12.0 — 2026-11-10 (versionCode 30)

Not checked on a phone.

### Added
- Reasoning picker in the status line. While it is your turn, the line reads "Hermes is listening," followed by the
  reasoning level as a phrase ("thinking hard"). Tap the phrase to open the eight levels, Off to Ultra, under the
  line, and tap one to set it. A new phrase appears with a burst of sparks; Max and Ultra open with a rainbow and
  keep a gold shimmer. With the phone's "remove animations" setting on, nothing moves. The Reasoning entry in the
  top bar's menu is still there. Beside the picker the context meter shows the percentage only.

### Changed
- During the agent's turn the composer shows **Stop** and **More** in place of Stop and "Send when done". More
  opens the ways to send the typed message: Steer now (into the running turn), Queue (a turn of its own when this
  one ends), Send as side question, Send as background task. In 0.11.0 Steer now was in the top bar's menu, where it
  was easy to miss, and the send button always queued. Those entries are no longer in the top bar's menu during a
  turn. A hardware keyboard's Enter still queues.

## 0.11.0 — 2026-11-10 (versionCode 28)

Everything here was checked against recordings from Hermes v0.23.1. Nothing was checked on a phone.

### Fixed
- Rename, archive, unarchive and delete of a session failed against Hermes 0.23 ("Couldn't rename the session" and
  the like), from the drawer and from the chat menu. That server refuses a session edit that does not name the
  revision it changes. Each edit now reads the revision first and sends it.
- "Compacting context…" could stay on the status line after the compaction was over, and the "Context compacted"
  line never appeared. The app waited for a completion message that Hermes does not send in that form. It now
  follows the real ones, and also ends the state when the turn produces output again, when the turn ends and when
  the connection drops.
- A warning that mentions compression ("Session compressed 2 times — accuracy may degrade") was taken for a running
  compaction and not shown. It is a warning line now.
- After a turn served by a fallback model, the model name in the top bar changed to the fallback model. It keeps
  the session's own model.

### Added
- Steer now: with a turn running and text typed, the top bar's menu has "Steer now". The text goes into the running
  turn, which reads it after its current tool call. Nothing is stopped. If the turn ends before it read the
  message, the message is sent as the next turn.
- The model that answered: when a fallback model wrote a reply, its name is shown under that reply. The "Primary
  model restored" line and Hermes' failure lines (retries exhausted, credits gone) are shown too. The label is lost
  when the chat is loaded again, because the stored history has no model.
- Running tools: a tool row that has run for 3 seconds shows its running time, and an MCP tool that reports
  progress shows its percentage and message. Hermes sends a command's output only when it ends.
- Subagent page: an opened subagent row has "Watch", which shows that subagent's live transcript, with Stop and a
  message field for that subagent alone.
- Slash commands: typing `/` in the composer suggests the server's commands and skills. A known command is run on
  the server and not sent to the model: `/goal`, `/undo`, `/personality`, `/context`, a skill by name. Its output
  appears as a note in the chat.
- Branch from here: the long-press menu of a message makes a new chat that holds this one up to that message, and
  opens it. The original chat is not changed. From one of your own messages the branch stops before it and the
  message waits in the new chat's composer. Hermes copies the text of the conversation, not the tool results.
- Sessions drawer: chats are sorted by latest activity; Pin and Unpin, with a Pinned section; a dot on chats that
  are active now; the origin of chats that did not start in the app ("Telegram · 2h"); "Show older chats" loads
  past the first 100.
- Reasoning from a provider that does not stream it appears with the reply (folded), without a reload.
- A live session that Hermes drops while the chat is open is resumed at once.
- An event type the app does not know is logged once, so it shows in a problem report.

## 0.10.3 — 2026-11-10 (versionCode 27)

### Fixed
- Reasoning in a reopened chat: some stored replies keep their reasoning under `reasoning_content` and not
  `reasoning`, and the app read only the second, so those reasoning rows (the thinking before a tool call) were
  missing after the chat was loaded again. Both are read now.
- The reasoning shown above a tool call keeps its identity when the chat is rebuilt from the server's history, like
  the tool rows and the reply do since 0.10.2: it stays unfolded if it was, and the list does not lose its place.
  This matters after any pause longer than about 20 seconds, when the server hands the chat a new live session.

## 0.10.2 — 2026-11-10 (versionCode 26)

### Fixed
- Tool rows no longer jump up when the reply starts. On a model that streams no reasoning (DeepSeek, Kimi), the empty
  reply the turn opened sat as a blinking caret line above the tool rows for the whole tool phase and was removed at
  the first word of the reply, which moved every tool row up by a line, each turn. The tool row (or an approval,
  question or connection card) now takes that line's place the moment it arrives.
- Rows keep their identity when the chat is rebuilt from the server's history (a reconnect after a longer pause, a
  resume, an edit or regenerate): a tool row is keyed by its tool-call id on both the live and the history path, and
  a message keeps the id it had on screen. The list keeps its scroll position, and open tool rows and unfolded
  reasoning stay open instead of every row being replaced. Server notices and the todo checklist stay where they
  were instead of moving to the end. A provider that reuses tool-call ids (`terminal:0` in every turn) no longer
  overwrites an earlier turn's row.
- Tool output: an opened tool row shows the result of every tool, not only of shell commands. The app read only the
  `output` field, so `read_file` (`content`) and most other tools showed "No output"; it now shows the tool's own
  text field, an error under it, or the whole result pretty-printed when there is no text field. Very long results
  are cut at 20,000 characters with a note.
- Tool output in a reopened chat: the history does not carry tool results, so every tool row of a loaded chat showed
  "No output". Opening one now loads the chat's stored results once ("Loading output…") and fills all of them.

### Added
- Select text: the long-press menu of a message has "Select text", which opens the message on its own page where
  any part can be selected and copied, across paragraphs, list items and code, and from a reply's reasoning into its
  text. The output and arguments of an open tool row are selectable in place.

## 0.10.1 — 2026-11-09 (versionCode 25)

### Added
- Server notices in the transcript: Hermes’ warning lines (`status.update` kind `warn`, or a lifecycle line marked
  ⚠️) now appear as a quiet system line between bubbles, so a model fallback ("Model fallback: claude-opus-5-5 via
  anthropic unavailable (rate limit); using … via synthetic") is visible on the phone instead of silently dropped.
  Nothing to dismiss; a chat rebuilt after a reconnect keeps them.
- Automatic context compaction shows "Compacting context…" in the status line while the server compresses the
  session on its own, and a "Context compression complete" line when it finishes, so the wait does not look like a
  hung turn.

### Fixed
- A provider control-token fragment leaked at the start of a reply (`ep|>`, `<|im_end|>`, a Synthetic/Kimi stream
  artefact) is stripped from streamed, completed and history replies.

## 0.10.0 — 2026-10-24 (versionCode 24)

### Added
- Send to Tsukaima: share a link, text, screenshots, photos or a PDF from any app’s share sheet. A small "Send to
  Hermes" card asks whether it goes to the last chat or a new one; the text lands in the composer and the files as
  attachments, and nothing is sent until you tap Send. Files over 12 MB are skipped with a notice.
- Live voice (GPT-Live): when your Hermes runs GPT-Live, voice mode becomes a live, full-duplex conversation with
  the server’s voice while Hermes does the work. Every request the voice hands over runs as an ordinary turn in the
  open chat, so the transcript shows it with its tools and reply; the voice says when Hermes is working and
  paraphrases the answer. The running time is always on screen (the session is billed per minute), a notification
  with End keeps it going with the screen off, and saying "stop", "that’s all" or "goodbye" ends it. If it can’t
  start, phone voice opens instead. Settings → Voice → "Live voice (GPT-Live)" turns it off and says what the server
  reports. The assistant sheet keeps phone voice.

### Changed
- The app is now built for 64-bit ARM (arm64-v8a) only, which every Android 10+ phone is; the live voice engine’s
  WebRTC library would otherwise add 7–25 MB per extra processor type. The APK grows from about 6 MB to about 18 MB.
- New permissions for live voice: changing audio settings (call audio on the headset or loudspeaker for the
  session), and the microphone and media-playback foreground service types. The WebRTC library also adds network
  state access, which the app does not use.

## 0.9.3 — 2026-10-22 (versionCode 23)

### Fixed
- Clarify questions: choices are full-width rows whose text wraps, so a long choice is read in full (they were
  one-line chips cut with "…", which hid what was being picked). The question notification shows up to 300 characters
  instead of 120.
- Tool rows: tapping a row open unfolds its whole command or summary instead of keeping the one-line "…" cut, and a
  row that came without a summary shows its arguments above the output. Risk and interruption notes are no longer cut
  at two lines. Subagent rows unfold their goal and detail the same way.
- Reasoning and tool rows keep their arrival order (reasoning, tool, reasoning, tool, reply). The reasoning that led
  to a tool call was moved below the tool each time one started, so tool rows appeared to hop above it; a loaded
  history now shows those reasoning rows in their place too, instead of dropping them.

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
