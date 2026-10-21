# Known Gaps (v0.9.2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the gaps parked during the v0.8.0 and v0.9.0 passes, so nothing known-and-fixable is left open before the user's device checks.

**Architecture:** Every item is client-side and already diagnosed in a review ledger; each task groups the items that share files. Pure reducer/parser logic first with unit tests, then UI. One release at the end (0.9.2, versionCode 22).

**Tech Stack:** Kotlin, Jetpack Compose, JUnit 4 + Robolectric 4.17, existing JLaTeXMath 0.2.0.

**Spec:** the user's instruction "Run the known gaps pass" (2026-10-21) over the parked lists in the v0.9.0 ledger (copy: `/tmp/claude-999/-home-claude-projects-tsukaima/7f5c22e9-e6ca-4c09-b478-33d4ee25e148/scratchpad/e2e-gaps-ledger-final.md`), the v0.8.0 completeness doc (`docs/superpowers/2026-10-20-completeness-pass.md` § parked), and the final message of the 0.9.x release. Out of scope by earlier decision: localisation, in-app updates, mixed voice setups, `segmentResults` on API 33+, Hermes-side limits (orphan notices only for some tools; directory UI for `display.suggested` beyond a hint).

## Global Constraints

- Everything in `docs/superpowers/plans/2026-10-21-e2e-gaps.md` Global Constraints (contracts, 44dp targets, local Hermes only, never two Gradle builds, one commit per task, explicit `git add`, lint baseline 19).
- Package is now `app.tsukaima`; sources under `app/src/*/kotlin/app/tsukaima/`.
- Queue rule stays: mid-turn sends always `queued: true`; only the app's Stop purges Hermes' queue.
- TeX stays behind `MathSafety`; no new math library.
- Copy for new strings: "Formula", "Send when done", "Connecting…", "Show done", "Hide done".

## Review Focus

1. A reconnect to a new live session right after Stop: the next queued send must not be marked "Not sent" — Task 1 test `stopFlagClearsOnLiveSessionChange`.
2. `$HOME/$USER` and `cost $5/$10` in prose stay text while `$x$` and `$a+b$` stay math — Task 2 test `shellVariablesAreNotMath`.
3. A 20 kB line of `_a ` runs in linear time — Task 2 test `underscoreClosersAreLinear` (timed bound, generous).
4. A double tap on "Skip all" sends one RPC — Task 4 test `cardAnswersAreSentOnce`.
5. Reopening Settings after leaving it mid-report never opens a share sheet — Task 7 test `reportShareDoesNotLeakIntoALaterVisit`.

---

### Task 1: Queue and Stop robustness
**Files:** `domain/chat/ChatReducer.kt`, `ui/chat/ChatViewModel.kt`, `ui/chat/ChatReconnector.kt`, `ui/chat/components/Composer.kt`, `ui/chat/ChatScreen.kt`, tests.
- `stopRequested` is cleared when the live session id changes (reconnector rebuild / `onSessionBound` with a new live id) and is never set by a Stop whose ack arrives after the turn already ended (set it only if the turn is still live when the ack lands; set optimistically before the RPC is fine if a turn end in between clears it and the late ack does not re-set it).
- Composer shows "Send when done" whenever the VM would queue (`mustQueue`), including the between-turns window where an echo is still queued.
- A transcript rebuild keeps a "Not sent" purged echo marked (carry purged echoes over a rebuild like pending side notes, matched by text + position after the history rows; drop the history duplicate row's twin only if it is the same text and has no reply).
- The ran-at-once race: the previous turn's `message.complete` still binds its `final_assistant_row_id` to its reply even after the new echo bound (bind by the reply's own position, not by `replyIndex > userIndex` of the newest echo).
- `Interrupted` loses to a truthy `error` (only exit 130 + marker + no error is Interrupted).
- Tests: `stopFlagClearsOnLiveSessionChange`, `lateStopAckDoesNotSetTheFlag`, `composerShowsSendWhenDoneBetweenTurns` (UI), `purgedEchoSurvivesARebuild`, `previousReplyKeepsItsRowIdWhenTheNextSendRanAtOnce`, `truthyErrorBeatsTheInterruptMarker`.
- [ ] Tests first, implement, full suite, commit `fix(chat): queue and Stop edge cases`.

### Task 2: Markdown parser correctness and speed
**Files:** `domain/markdown/MarkdownInline.kt`, `domain/markdown/Markdown.kt`, `domain/voice/SpeechText.kt`, tests.
- `_` (and `*`) closer search memoised like `nextCloseBracket` so emphasis scanning is linear; restore the KDoc claim only when true.
- Inline `$…$`: the opening `$` must not be preceded by an ASCII letter or digit, and the closing `$` must not be followed by an ASCII letter or digit (in addition to the existing whitespace/digit rules) — `$HOME/$USER`, `a$b$c` stay text; `$x$`, `($a+b$)`, `$x$.` stay math.
- Escaped `]` inside link text is skipped by the closer search (`[a\]b](u)` links with text `a]b`).
- SpeechText mirrors the parser for inline math (`$a $b$` speaks as the parser renders) and for `$$\n$$` (empty display math: same decision both sides — text).
- Fuzz alphabet gains `(){}`; the perf test uses a pathological input per construct (`_a ` × 5000, `*a ` × 5000, `$a ` × 5000, `[a ` × 5000) with a generous time bound (< 2 s each on CI).
- Tests: `shellVariablesAreNotMath`, `mathNextToLettersIsNotMath`, `escapedBracketInLinkText`, `speechMatchesTheParserForDollars`, `underscoreClosersAreLinear` (+ the other pathological cases), `emptyDisplayMathIsTextInBoth`.
- [ ] Tests first, implement, full suite, commit `fix(markdown): linear emphasis, stricter inline math, escaped brackets`.

### Task 3: Math rendering UX
**Files:** `ui/chat/components/MathBlock.kt`, `ui/chat/components/MarkdownText.kt`, new `domain/markdown/TexSpeech.kt`, tests.
- Inline formulas wider than the available width scale down to fit (min 70 % of text size), then clip with a trailing "…" chip that opens the formula full-width in a dialog with horizontal scroll; display math keeps its scroll.
- The first draw of a formula happens off the main thread: build the bitmap in `produceState` on `Dispatchers.Default` with a placeholder of the right height (text-size line) until ready; the existing LRU cache is checked synchronously first.
- Accessibility: `contentDescription` = "Formula: " + `TexSpeech.readable(tex)` — a pure best-effort reader: `\frac{a}{b}` → "a over b", `^{n}` → "to the power n", `_{i}` → "sub i", `\sqrt{x}` → "square root of x", Greek letters by name, `\cdot`/`\times` → "times", other commands → their name without the backslash, braces dropped, whitespace collapsed, max 300 chars.
- Tests: `TexSpeechTest` (the mappings above, nesting, unknown commands, length cap), `MathUiTest.inlineFormulaScalesDown`, `MathUiTest.contentDescriptionIsReadable`.
- [ ] Tests first, implement, full suite, commit `feat(markdown): readable, non-blocking, fitting formulas`.

### Task 4: Connection card polish
**Files:** `ui/chat/components/ConnectionCard.kt`, `ui/chat/ChatViewModel.kt`, `domain/chat/Connections.kt`, `domain/chat/ChatReducer.kt`, tests.
- Answers are sent once: a per-`op_id` in-flight guard in the VM; buttons disabled with "Connecting…" while an answer is in flight.
- Typed env values for a target are cleared from composition state as soon as Connect is sent.
- Countdown: whole minutes rounded down; below 2 minutes show seconds.
- A target with both `connect_url` and `required_env`: Connect sends the typed env with `approved` when all required fields are filled, and opens the URL afterwards only if the next `connection.update` still shows the target unresolved with a `connect_url`.
- `display.suggested` (no rows): show one helper line "Hermes suggests connecting: <names>" from `display.suggested`, and "Continue".
- Tests: `cardAnswersAreSentOnce`, `typedEnvIsClearedAfterConnect` (UI), `countdownRoundsDown`, `connectSendsEnvBeforeOpeningTheLink`, `suggestedCardShowsAHint` (UI), `reconnectRestoresThePendingConnection` (reconnector path).
- [ ] Tests first, implement, full suite, commit `fix(chat): connection card polish`.

### Task 5: Todo card polish
**Files:** `domain/chat/ChatReducer.kt`, `domain/chat/ChatState.kt`, `ui/chat/components/TodoCard.kt`, `ui/chat/ChatScreen.kt`, tests.
- Revision floor: the highest seen revision is kept on `ChatState` even after a clear; lower revisions are ignored.
- The transcript keeps following when the todo card is appended or updated while it is the last item (include its revision in the tail key).
- When completed parents are hidden, their visible children are shown at the parent's depth (no orphan indentation).
- Tests: `noStaleTodoAfterAClear`, `staleSeedAfterANewerLiveEventIsIgnored`, `tailKeyFollowsTheTodoCard`, `hiddenParentsDoNotIndentChildren` (UI), `todoRowsSurviveCycles` (pure, incl. self-parent).
- [ ] Tests first, implement, full suite, commit `fix(chat): todo card polish`.

### Task 6: Image cache and media polish
**Files:** `ui/chat/ChatMedia.kt`, `data/files/ImagePreview.kt`, `ui/chat/components/ToolRow.kt`, `ui/chat/components/ImageCard.kt`, tests.
- Failed loads live in their own bounded set (64 entries), not in the 8-entry bitmap LRU, so image-heavy replies do not refetch failures.
- The "allowed" (tapped) state is read from `ChatMedia` (a `StateFlow`/snapshot set), so tapping a foreign URL in one place loads it everywhere it appears.
- The inline SHA-256 key is memoised per data-URL instance (identity map, bounded) so a row scrolling back does not rehash.
- The long-side cap applies only to tool images; reply and MEDIA previews use the pixel budget alone (restores pre-0.9.0 reply sharpness).
- Tests: `failuresDoNotEvictBitmaps`, `tapAllowsTheUrlEverywhere`, `inlineDigestIsComputedOnce`, `replyPreviewsAreNotSideCapped`.
- [ ] Tests first, implement, full suite, commit `fix(chat): image cache and media polish`.

### Task 7: v0.8.0 leftovers
**Files:** `ui/chat/ChatEdits.kt`, `ui/settings/SettingsViewModel.kt`, `ui/settings/SettingsScreen.kt`, `voice/ServerSpeechOutput.kt`, tests.
- Edit/regenerate failure restores the pre-edit snapshot plus every item that arrived after the snapshot (merge by id, keep order) instead of the snapshot alone.
- The report share is delivered only to the screen that asked: tie it to the request (a one-shot event consumed only while Settings is resumed; dropped if Settings left before the report was ready, with a toast "Report ready — tap Report a problem again").
- Server TTS prefetches the next sentence while the current one plays (one ahead, cancelled with the job; same cleanup rules).
- Tests: `editFailureKeepsEventsThatArrivedMeanwhile`, `reportShareDoesNotLeakIntoALaterVisit`, `ServerSpeechOutput` prefetch is covered by a pure `SentencePipeline` helper test (order preserved, at most one ahead, cancel drops the prefetched file).
- [ ] Tests first, implement, full suite, commit `fix: v0.8.0 leftovers — edit restore, report share, TTS prefetch`.

### Task 8: Release 0.9.2
- `versionCode = 22`, `versionName = "0.9.2"`; CHANGELOG (Fixed: each task's user-visible items), rulings `## v0.9.2`, completeness doc (closed items; still open: the decisions listed in the Spec line, device checks).
- Verification recipe (controller): clean build, second test run, smoke, aapt/apksigner, push, CI, APK, memory note.
