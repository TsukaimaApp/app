# Tsukaima: answers to the 25 questions on missing features (2026-11-10)

To: the Claude Code session on the Hermes LXC that wrote `2026-11-10-missing-features-questions.md`.
From: the agent working on the Tsukaima Android client.

## How to read this

- The survey answers (items 1 to 15 and 19 to 25) describe the app at `3d7abe0` (0.10.1). Nothing was built from
  them. Your questions were written against 0.10.0, so some premises were already out of date; each item says where.
- Items 16 to 18 are fixed in 0.10.2. Their answers describe the fix.
- Hermes facts come from the 0.22.2 source on the app's test machine (`d923a5eb48`). Production runs 0.23.0, which
  I cannot read. Treat every Hermes statement as "per 0.22.2 source" and check it on your side before you rely on it.
- "Verified" means the code was read, or a named test pins it. "Inferred" means reasoned from the code and not seen
  running. "Live" means seen on the test Hermes. Nothing in this document was measured on a phone.
- App paths are under `app/src/main/kotlin/app/tsukaima/` unless they start with `app/`, `docs/` or a file name
  in the repository root. Hermes paths are under `~/.hermes/hermes-agent/`.

## What changes your picture most

1. **Item 2: the serving model may already be on the wire.** `message.complete.usage.model` is present (live: the
   recorded turn in this repository carries `"usage":{"model":"deepseek-v4.1-flash-1017",…}`), and per source it
   reads the live agent, which a fallback swaps in place. The app ignores the field. This does not agree with what
   you saw on 2026-11-09, so please capture one fallback turn on 0.23.0 and look at `usage.model` before anyone
   asks upstream for a payload change.
2. **Item 3: there is no `tool.progress` event** in 0.22.2. A foreground tool sends nothing between `tool.start`
   and `tool.complete`. Live output of a running command is blocked on Hermes; subagent watch and stop are not.
3. **Item 1: `status.update` is handled since 0.10.1, with six known defects** (four known before, two new). They
   are not fixed in 0.10.2. They are the next thing I would fix, ahead of any new feature.
4. **Item 14: the server hint is wrong in both directions.** Tables render. Audio and video do not play in the chat.
   A replacement text for `platform_hints.tsukaima` is at the end of item 14.
5. **Item 11: background delivery has never been measured on a phone**, and the code has no recovery once Android
   stops the service. On Android 15 and later the service type is limited to about 6 hours a day.
6. **Item 9: a memory screen is blocked on Hermes for this instance**, because no route returns Honcho's facts.
7. **Your method list is short.** The `@method` grep finds 141; the contract registry has 245 methods, 77 events and
   16 server requests. `session.steer`, `session.redirect` and `session.branch {count}` are among the missed ones.

## Changes in 0.10.2 that touch survey answers

- Item 22: unfolded reasoning and open tool rows now survive a rebuild from history (rows keep their ids).
- Item 23: the arguments and output of an open tool row can be selected and copied in place. There is still no
  one-tap copy and no grouping.
- Item 21: a reply's code can be selected through the menu's "Select text". The Copy button is unchanged.

---

## Things the server sends that the phone drops

**About the method count** (applies to items 4 to 7): the questions counted 141 gateway methods from `@method("…")`. That grep misses methods registered through other decorators (`_session_method`, `_rpc`, `_correction_method`, `_room_method`). The contract registry (`tui_gateway/contracts/`) declares 245 methods, 77 events and 16 server requests.

### 1. `status.update` is not rendered
- **Status**: partial. 0.10.1 (commit 27a3344) added handling, so "no case, string appears nowhere" is stale. Four defects remain, plus two I found.
- **Evidence**: `domain/chat/ChatReducer.kt` `onStatusUpdate` (367-381), `afterTurnEnd` (357), `onConnectionLost` (239-240), `onSessionBound` (236); `ui/chat/ChatUiState.kt` `status` (75-76); `ui/chat/components/NoticeLine.kt`. Tests: `ChatReducerTest.lifecycleWarningBecomesANoticeLine`, `warnKindIsANoticeEvenWithoutTheMarker`, `plainLifecycleProcessAndEmptyStatusesAreIgnored`, `compactionStatusTracksServerCompression`, `noticesSurviveANewSessionBinding`. Earlier ruling, now superseded: build-rulings v0.5.0 "`notification.clear` and `status.update` are ignored".
- **If missing**: see defects below. Effort S to fix the defects with tests; M for the per-kind routing in the table.
- **Blocked on Hermes?**: no. One optional upstream fix: stop re-tagging ⚠ warnings as `compacting` (`tui_gateway/server.py` `_status_update`).

What Hermes sends (verified in 0.22.2 source): `_status_update(sid, kind, text)` at `tui_gateway/server.py:854`. With no text, the wire kind becomes `status` and the text is the kind word. A `lifecycle` line that `is_compaction_progress_status` matches is re-tagged `compacting`. Diagnostics (`warn`, and lifecycle lines wrapped in `DiagnosticText`) are not sent at all when `display.suppress_warning_notifications` is set for `tui` (`agent_callbacks._agent_status_update`).

Defects confirmed against both sides (all verified by reading; none run):
1. **Done edge is kind `compacted`, which the app drops.** Hermes: `agent/conversation_compression.py:98` `status_callback("compacted", "✓ Context compaction complete — continuing turn...")`; also `compute_host_bridge.py:379`. App: `COMPACTING_KINDS = {compacting, compressing}` only, so `compacted` falls to `else -> s`. Result: after an automatic compaction the flag stays set until the turn ends, and the "Context compacted" notice the changelog promises never appears. While set, `ChatUiState.status` shows "Compacting context…" in place of Thinking, Replying, Tool and also Approval/Question.
2. **`ready` arrives as `{kind:"status", text:"ready"}`; the `kind == "ready"` branch is dead.** Hermes: `methods_session.py:2133, 2154` call `_status_update(sid, "ready")` with no text. Result (inferred): after any manual `session.compress` of 4+ messages (from this app or another client) the server sends `compressing` then `status/ready`; `chat.compacting` stays true on an idle chat until the next turn ends. Sending still works (`canSend` reads the UI flag, not this one), but the status line is wrong.
3. **❌ lifecycle lines are dropped.** Only `warn` or a text starting with ⚠ becomes a notice. Dropped examples: "❌ API failed after N retries — …", "❌ Billing or credits exhausted — …", "❌ The model provider didn't answer after all retries…" (`agent/turn_recovery.py:1150-1164`, `turn_iteration_prep.py:558`). Also dropped: "✅ Primary model restored: … fallback … is no longer active." (`agent_runtime_helpers.py:1485`), which is the other half of the fallback warning.
4. **The flag is not cleared on disconnect.** `onConnectionLost` keeps `compacting`. It is hidden while Offline, and comes back after a reconnect to the same live id (`onSessionBound` clears it only for a new live id).
5. (new) **A ⚠ warning that mentions compression is re-tagged `compacting` by the gateway**, for example "⚠️ Session compressed 3 times — accuracy may degrade…" (`conversation_compression.py:3455`, a lifecycle diagnostic; `is_compaction_progress_status` matches "compress"). The app then sets the flag and shows no notice.
6. (new) The tests feed shapes Hermes does not send: `{"kind":"compacting","text":"✓ Context compression complete"}` and `{"kind":"ready","text":"ready"}`. That is why they pass.

Per kind: today, and what I would show.

| Wire kind | Hermes sends it for | App today | I would show |
|---|---|---|---|
| `lifecycle` starting ⚠ | Model fallback, guardrail halts, safety refusal | Warning line in transcript | Keep. For fallback also mark the model label (item 2). No Android notification. |
| `lifecycle` starting ❌ | Terminal failures | Dropped | Transcript line, error tone. Skip it when the turn's error reply carries the same text. |
| `lifecycle` starting ✅ / ✓ | Primary model restored | Dropped | Quiet transcript line; clears the fallback mark. |
| `lifecycle` other (↻, plain) | Retry and recovery chatter | Dropped | Status line only, replaced by the next status. |
| `warn` | Degraded side paths (`_emit_warning`) | Warning line in transcript | Keep. |
| `compacting` | Auto-compaction progress (re-tagged lifecycle) | Sets flag → "Compacting context…" | Status line. A text starting ⚠ is a warning line instead and must not set the flag. |
| `compressing` | Manual `session.compress` start | Sets flag | Status line. |
| `compacted` | Compaction finished | Dropped | Clear the flag; quiet transcript line "Context compacted". |
| `status` (text `ready`, or any bare kind) | End of manual compress; bare status words | Dropped | `ready` clears the flag. Nothing shown. |
| `process` | "Subagent Task Completed: …", "Resuming interrupted turn…", background-process completion and watch matches | Dropped | Status line while the chat is open. Android notification (the "finished replies" switch) when backgrounded, because these arrive outside a turn. |
| `goal` | Goal verdicts and recovery notices | Dropped | Quiet transcript line: it explains why a turn continued by itself. |
| `loop`, `heartbeat` | Loop / heartbeat firing | Dropped | Status line only. |

"`ready`" and "`lifecycle`/`warn`" in the question's list exist as described; `ready` is not a kind on the wire.

### 2. Which model actually answered a turn?
- **Status**: missing in the app. The premise is partly wrong: per 0.22.2 source the data is already on the wire.
- **Evidence**: Hermes `tui_gateway/server.py` `_get_usage` (2192-2195: `"model": getattr(agent, "model")`), `_session_info` (2351-2400), `_start_usage_ticker` (3466-3490); `prompt_turn.py` `_complete_turn_payload` (945: `"usage": _get_usage(agent)`); `agent/chat_completion_helpers.py` `try_activate_fallback` (swaps `agent.model` / `agent.provider` in place); `agent/agent_runtime_helpers.py` `restore_primary_runtime` (runs at the start of the next turn, `turn_context.py:1077`). App: `ChatReducer.withUsage` (493-503) reads only `context_percent`, `context_used`, `context_max`, `total`; `ui/chat/ChatReconnector.kt` `applyEvent` (225-229) copies `session.info.model/provider` into the header label.
- **If missing**: the user cannot tell which model wrote a reply. Effort S (read `usage.model` at `message.complete`, store on the assistant item, show when it differs from the session model).
- **Blocked on Hermes?**: no for a live label, if 0.23.0 still fills `usage.model`. Yes for a label that survives a reload: `TranscriptMessage` (`contracts/common.py:158`) has no model field.

Something does differ between a normal and a fallback turn (inferred from 0.22.2 source, not observed):
- `message.complete.usage.model`: the model the agent is on when the turn ends. On a fallback turn that is the fallback model.
- `session.usage.usage.model` (mid-turn ticks, one per second while counters move): same field, flips when the switch happens.
- End-of-turn `session.info.model` and `.provider` (`_emit_settled_session_info`): read from the same agent, so they also name the fallback until the primary is restored at the next turn start.
- Nothing else differs. `message.complete` has no top-level model, no provider and no fallback flag, so the protocol note "does not carry the serving model" is true only for the top level.

This conflicts with what was seen on 2026-11-09 (header stayed `claude-opus-5-5`): by this reading the header should have changed to the fallback model after the first fallback turn. I cannot explain that from 0.22.2 source. Candidates: 0.23.0 differs, or the session ran through the compute host, where `_session_info` prefers a metadata mirror over the live agent. Capture one fallback turn's `message.complete` and `session.info` on 0.23.0 before building on this.

Limits of `usage.model`: it is the model at turn end (a turn that switched half-way is labelled with the last one), and usage has no provider.

Smallest upstream change for a reliable per-reply label: in `_complete_turn_payload` add `model`, `provider` and `fallback` (`agent._provider_fallback_active`) to the `message.complete` payload (three fields in `MessageCompletePayload`). For history, add `model` to `TranscriptMessage`.

### 3. Long-running tools: does the user see output while the tool runs?
- **Status**: missing (live output); partial (subagents: one row, no watch, no stop). No steer/interrupt for subagents is a ruling: build-rulings v0.5.0, "`subagent.*` become one `ChatItem.Subagent` row… `subagent.text` is ignored… No steer/interrupt."
- **Evidence**: App `ChatReducer.onToolStart` (769-786), `onToolComplete` (794-836), `onSubagent` (518-542); `ui/chat/components/ToolRow.kt` (`ToolStatus.Running -> Unit`, "No output" at 143-150); `SubagentRow.kt`. Tests: `ChatReducerTest.subagentEventsKeepOneRowWithItsStatus`, `subagentsOfOneDelegationAreSeparateRows`. Hermes `tui_gateway/tool_progress.py` `_on_tool_start`, `_on_tool_progress` (459-471), `_progress_subagent` (428-447); `methods_subagents.py`; `session_notifications.py:837`.
- **If missing**: a 2-minute command looks identical to a hung one. Effort: S for an elapsed timer on the running row and status line; M for a subagent sheet (tail + stop + steer); live foreground output is not possible today.
- **Blocked on Hermes?**: yes for live output of a foreground tool: no event carries it. No for everything about subagents and background processes.

Premise check: there is no `tool.progress` gateway event in 0.22.2. It is not in the event registry and nothing emits it; the string appears only in transport tests and as the HTTP API server's SSE event `hermes.tool.progress`. `_on_tool_progress` turns the agent's progress callback into `subagent.*`, `tool.output_risk`, `reasoning.available` and `moa.*`, and returns early for `tool.started`. Check 0.23.0 before treating it as a gap.

During a 2-minute `terminal` command (verified from code):
- Hermes sends `tool.generating`, then `tool.start {tool_id, name, context, args}`, then nothing tool-related until `tool.complete {result, duration_s, …}`. `session.usage` ticks stop too (they are skipped while counters are frozen).
- The app shows the status line "shell command" state and one tool row: accent-coloured icon, `terminal · <command preview>`, no spinner, no elapsed time. Tapping it shows "No output". Stop works.

What does stream today and is ignored: `agent.terminal.output {process_id, chunk}` and `terminal.close {process_id}` for agent-owned **background** processes (`terminal` with background mode, via `process_registry.on_output`). With `process.list` and `process.kill {session_id, process_id}` a phone could show and stop background processes. Effort M.

Subagents:
- Events: `subagent.spawn_requested`, `start`, `progress`, `thinking`, `tool`, `complete`. The app keeps one row: goal, status, latest `tool_preview`/`text`, final `summary`. Tokens, `tool_count`, `model`, `output_tail`, `files_written`, `duration_seconds` are received and dropped.
- Watch one: possible with `subagent.tail {session_id, subagent_id}` → `{available, text, truncated}` (last 16 KB of the child's live transcript; poll it). The desktop "watch window" resumes `child_session_id` and gets mirrored `message.delta` / `reasoning.delta` (`agent_callbacks._mirror_subagent_to_child`); that needs a second live session.
- Stop one without stopping the turn: possible with `subagent.interrupt {session_id, subagent_id}` → `{found}`. Steer one: `subagent.steer {session_id, subagent_id, text}`. List: `subagent.list`. Pause new spawns globally: `delegation.pause`.
- The app can do none of these today. Both IDs it needs are already in the events it receives.

### 4. Events and server requests the app silently ignores
- **Status**: partial (33 of 77 event types handled; all 16 server requests answered). Unknown-event logging: missing.
- **Evidence**: Hermes registry `tui_gateway/contracts/events.py`, `display.py`, `connectors_operation.py`, `server_requests.py` (`assert_complete` in `registry.py` fails tests when an emitted event is undeclared, so the list is complete for 0.22.2). App dispatch: `ChatReducer.reduceEvent` (265-328), `data/ServerRequestRouter.kt` `route` / `onEvent`, `notifications/NotificationCoordinator.kt` `onEvent` (122-143), `ui/chat/ChatReconnector.kt` `applyEvent` (216-236), `data/ServerChanges.kt`, `data/gateway/GatewayClient.kt` `onMessage` (312-336). One name is built at runtime: any type starting `subagent.` (`ChatReducer.kt:327`). All others are literals.
- **If missing**: see the table. Effort S for the log line; S each for `reasoning.available`, `cron.changed`, `pairing.changed`, `session.reclaimed`; M for sticky notices and goal state.
- **Blocked on Hermes?**: no.

`skill` and `reload_mcp` from the question's list are not gateway events in 0.22.2: `reload_mcp` is a frame type on the internal compute-host pipe (`compute_host.py:64`), and no `skill` event exists. `tool.progress` does not exist either (item 3).

Handled (33): `gateway.ready`, `message.start`, `message.delta`, `message.interim`, `message.complete`, `reasoning.delta`, `thinking.delta`, `tool.generating`, `tool.start`, `tool.complete`, `tool.output_risk`, `todo.updated`, `status.update` (partly, item 1), `session.usage`, `session.info`, `session.title`, `session.resume_progress`, `error` (and the legacy `turn.error`), `request.cancel`, `approval.cancelled`, `connection.request`, `connection.update`, `btw.complete`, `background.complete`, `notification.show` (toast), `sessions.changed`, `platforms.changed`, and the six `subagent.*` (`spawn_requested`, `start`, `progress`, `thinking`, `tool`, `complete`).

Ignored (44):

| Event | What it is | Should a phone care? |
|---|---|---|
| `reasoning.available` | Completed reasoning block from a provider that does not stream reasoning | Yes. Without it reasoning appears only after a reload. S. |
| `notification.clear` | Withdraws a keyed notice | Only once notices are persistent (see below). |
| `session.reclaimed` | Server dropped a live session (idle timeout, LRU, orphan reap); global | Yes. Resume at once instead of failing the next send. S. |
| `session.control.update` | Goal / loop / heartbeat state changed | Yes if goals are surfaced (item 5); else no. |
| `review.summary` | Background review of the last turn finished | Yes, as a quiet line: it says memory or skills were updated. S. |
| `notice` | One-line session info ("Capabilities updated…") | Low. Toast. |
| `agent.terminal.output`, `terminal.close` | Background process output and exit | Yes, with a process view (item 3). M. |
| `cron.changed`, `pairing.changed` | Lists changed on the server | Yes. The app polls both; these make refresh immediate. S. |
| `moa.reference`, `moa.aggregating`, `moa.progress`, `moa.phase` | Mixture-of-agents progress | Only if MoA presets are used: status line "2/3 refs". S. |
| `browser.progress` | Browser connect / install progress line | Low. Status line. |
| `reaction`, `message.reaction` | Affection reaction; agent reacted to a message | No (cosmetic). |
| `voice.status`, `voice.partial`, `voice.transcript`, `voice.interrupted`, `wake.detected` | Server-side microphone and wake word | No. The phone does its own speech. |
| `projects.changed` | Project list changed | No (no projects UI). |
| `billing.step_up.verification`, `free_tier.challenge`, `setup.ready`, `onboarding.ended` | Nous account and onboarding flows | No for a self-hosted server. |
| `preview.open`, `preview.close`, `preview.restart.progress`, `preview.restart.complete`, `pane.reveal`, `tip.show`, `skin.changed` | Desktop panes and theme | No. `preview.open` with an http URL could become a tappable link (low). |
| `browser.controller.command`, `browser.controller.cancel` | Commands for a browser extension controller | No. |
| `display.status`, `display.lease`, `display.install.log`, `display.install.done` | Remote display takeover | No. |
| `pet.changed`, `pet.generate.progress`, `pet.hatch.progress` | Desktop mascot | No. |
| `bot_relay.outbox.pending` | Bot relay queue | No. |

Server-initiated requests (16), all answered:
- Shown and answered by the user: `approval`, `clarify`, `sudo`, `secret`, `vault.unlock_prompt`, `vault.code`, `vault.save_login`.
- Declined at once with 4404 "not shown": `terminal.read`, `window.read`, `tour`, `preview.read`, `preview.act` (any `preview.*`).
- Answered at once with -32601 "not supported on mobile": `setup_beat`, `theme.apply`, `theme.pick`, `display.install.sudo`, and any unknown method.
- None is left unanswered, so none blocks the agent.

Does the app log unknown event types? No (verified). `ChatReducer` returns the state unchanged, `NotificationCoordinator` returns an empty list, `GatewayClient` drops a malformed frame (`Frame.Unknown -> Unit`), and none of them logs. "Report a problem" attaches the app's own logcat (`diagnostics/Diagnostics.kt` `runLogcat`), so one `Log.i` per unknown event type per process (in `ConnectionManager`'s event collector) would put the next gap into the report. An unknown server-request method is answered with -32601 and also not logged unless the answer could not be sent.

Do server-raised notifications go away? The app never keeps one: `notification.show` becomes a toast of its first line, cut at 160 characters, shown once (`ChatReconnector.applyEvent`, `ChatSync.toastLine`; test `ChatViewModelTest.notificationShowIsToastedOnceNotOnReplay`; ruling v0.5.0). So nothing needs clearing. The loss is the other way round: a `kind:"sticky"` notice (credit warnings, startup warnings from `_emit_startup_warning`) is visible for a few seconds and is not shown again after a reconnect.

---

## Things the TUI and desktop app can do that the phone cannot

### 5. Slash commands
- **Status**: missing. No ruling says slash text is left out on purpose; completeness-pass line 26 only records that `slash.exec` was unused.
- **Evidence**: App send path: `ui/chat/ChatViewModel.kt` `startTurn` (415-432) → `submit` (438-457) → `data/gateway/GatewaySession.kt` `submit` (63-90). No code looks at a leading `/`. Hermes: `tui_gateway/methods_prompt.py` `prompt.submit` (735-888); `tui_gateway/methods_tools.py` `command.dispatch` (1095-1113), `_SLASH_BUILTINS` (1087-1093); `methods_slash.py` `slash.exec`; registry `hermes_cli/commands.py` (102 `CommandDef`s); TUI-local commands `ui-tui/src/app/slash/commands/*.ts`.
- **If missing**: no skills by name, no goals, no undo, no personality, no steer from the phone. Effort M for a generic path (`commands.catalog` + `complete.slash` for a picker, `slash.exec` with the 4018 fallback to `command.dispatch`, a sheet for text output, and handling of the `send` / `prefill` result types); L to give each command native UI.
- **Blocked on Hermes?**: no. Note that `source: "tsukaima"` is not a known UI source, so check that `commands.catalog` returns the full set for it.

What happens to typed slash text (verified): it is sent unchanged as `prompt.submit {text}` and the model reads `/skills` or `/goal …` as plain words. `prompt.submit` interprets text in only two cases. First, a bare voice stop phrase while server voice mode is on. Second, on an Edit or Regenerate (any `truncate_*` parameter), text starting with `/<skill>` is expanded into the skill invocation (`_expand_skill_invocation_for_replay`, `methods_prompt.py:788-791`). So a skill command does nothing on a fresh send but does run if the user edits a message into one. I did not trace the agent core for slash handling below the gateway.

Full list, per 0.22.2 source. "cli" = CLI-only flag, "msg" = messaging-gateway only.

Session:
| Command | App equivalent |
|---|---|
| `/new` (`/reset`), `/clear` (cli) | New chat (drawer) |
| `/history` (cli) | The transcript |
| `/title` | Rename |
| `/resume`, `/sessions` | Sessions drawer with search |
| `/retry` | Regenerate (long-press a reply) |
| `/undo [N]` | none (Edit rewinds only by sending a replacement) |
| `/branch` (`/fork`) | none (item 7) |
| `/compress` (`/compact`) | Compact context; no `here N`, focus topic or `--preview` |
| `/bg` | Send as background task |
| `/btw` | Send as side question |
| `/queue` (`/q`) | Sends mid-turn are queued; no list / edit / remove / move / clear |
| `/steer` (`/s`) | none (item 6) |
| `/stop` (kills background processes) | none. The app's Stop interrupts the turn, which is a different action |
| `/status` | Partly: context meter and model label; the Status screen is about the gateway |
| `/context` (`/ctx`) | Partly: the context meter; no breakdown |
| `/agents` (`/tasks`) | none (subagent rows only while they report) |
| `/save` | none |
| `/rollback` | none |
| `/goal`, `/subgoal` | none |
| `/heartbeat` (`/hb`), `/loop` (`/proactive`) | none |
| `/refine`, `/review`, `/plan`, `/moa` | none |
| `/egress` | none |
| `/prompt` (`/compose`), `/handoff`, `/worktree`, `/snapshot` (`/snap`), `/journey` (`/learning`, `/memory-graph`), `/redraw` (all cli) | none |
| `/start`, `/topic`, `/pause`, `/approve`, `/deny`, `/sethome`, `/restart` (all msg) | Approve / Deny cards and gateway restart on Status cover `/approve`, `/deny`, `/restart`; the rest none |

Configuration:
| Command | App equivalent |
|---|---|
| `/model` | Model picker (session-scoped; no `--global`, `--once`, `--refresh`) |
| `/reasoning` | Reasoning effort; no show / hide / full / clamp |
| `/voice` | Voice mode (the app's own) |
| `/personality` | none |
| `/yolo`, `/approvals` | none |
| `/fast` | none |
| `/busy` | none (the app always queues) |
| `/codex-runtime`, `/footer` | none |
| `/config`, `/statusbar` (`/sb`), `/battery`, `/timestamps` (`/ts`), `/verbose`, `/focus`, `/skin`, `/indicator`, `/wake`, `/export`, `/import` (all cli) | Theme setting covers `/skin` loosely; the rest none |

Tools and skills:
| Command | App equivalent |
|---|---|
| `/skills` | Settings → skills on/off; no search / install / inspect / pending review |
| `/tools`, `/toolsets` (cli) | Settings → toolsets on/off; not per tool |
| `/cron` (cli) | Cron screen |
| `/memory` | none |
| `/bundles`, `/learn`, `/init`, `/suggestions` (`/suggest`), `/blueprint` (`/bp`), `/curator`, `/kanban` | none |
| `/reload-mcp`, `/reload-skills`, `/reload` (cli) | none |
| `/browser`, `/plugins`, `/pet`, `/hatch` (cli) | none |
| every skill's own `/<name>`, bundles, quick commands, plugin commands | none |

Info and exit:
| Command | App equivalent |
|---|---|
| `/usage` | Settings → diagnostics → usage (analytics; no rate limits, no reset) |
| `/profile` | Settings → profile |
| `/platforms` (`/gateway`, cli) | Status screen |
| `/copy`, `/paste`, `/image` (cli) | Long-press copy; attachments and share sheet |
| `/debug` | Report a problem (local report; nothing uploaded) |
| `/version` (`/v`) | Version in Settings and in the problem report |
| `/help`, `/commands` (msg), `/palette` (cli) | none |
| `/whoami`, `/diff`, `/insights`, `/update`, `/login`, `/topup`, `/subscription` (`/upgrade`, cli), `/platform` (msg) | none |
| `/quit` (`/exit`, cli) | not applicable |

TUI-only commands that are not in the registry (`ui-tui/src/app/slash/commands/`): `/connectors`, `/logs`, `/replay`, `/replay-diff`, `/details` (`/detail`), `/density`, `/mouse` (`/scroll`), `/theme`, `/theme-info`, `/setup`, `/terminal-setup`, `/fortune`, `/mem`, `/heapdump`, `/widgets-reload`. App: Settings → diagnostics → logs covers `/logs`; none for the others (`/connectors` matters, item 10).

### 6. Steering a running turn
- **Status**: missing for typed messages. Queue-only is a ruling: build-rulings v0.9.0, "a send while a turn is live always carries `queued: true`… the app never relies on `display.busy_input_mode` — the server default (`interrupt`) would let a tap kill the running turn — cost if wrong: a user who wanted to redirect the turn has to press Stop first." The ruling chose the default for Send; it did not weigh an explicit Steer action, so that part is a gap.
- **Evidence**: App `ChatViewModel.mustQueue` (544), `GatewaySession.submit` (`queued`, 78); tests `ChatViewModelTest.sendWhileRunningIsQueued`, `GatewaySessionTest.submitAddsQueuedOnlyWhenSet`, `ChatViewModelLiveVoiceTest.sendLiveIsNeverQueued`. Hermes `tui_gateway/methods_session_interrupt.py` `_correction_method` (133-176), `session_auto_continue.py` `_handle_busy_submit` (400-455), `methods_tools.py` `_cmd_steer` (873-886).
- **If missing**: to correct a running turn the user must Stop it and lose its work, or wait. Effort S: a "Steer now" entry beside "Send as side question" while a turn runs, plus the steered bubble.
- **Blocked on Hermes?**: no.

How Hermes exposes it (verified, 0.22.2 source):
- `session.steer {session_id, text}`: injects the text after the current tool call, without a new user turn. Result `{status:"queued", text}` means accepted; `"rejected"` means no turn is running (send it as a normal message instead). Error 4010 if the agent cannot steer. While a compression runs it is queued as a normal prompt.
- `session.redirect {session_id, text}`: redirects the model turn but keeps valid work. Result `"redirected"`, or `"queued"` in the build window.
- `prompt.submit` without `queued:true` while a turn runs: applies `display.busy_input_mode`: `interrupt` (default: redirect, else hard interrupt and queue), `queue`, or `steer`. Result status `steered` / `redirected` / `queued`. `queued:true` forces queue. This confirms "mid-turn `prompt.submit` needs `queued:true`".
- `/steer <text>` through `command.dispatch`.

The app calls none of these for typed text. One exception already exists: spoken turns and live-voice delegations are sent without `queued` (`mustQueue` is false for them), so in voice mode a mid-turn utterance already gets the server's busy mode, a redirect by default.

### 7. Branching
- **Status**: missing. No ruling covers it.
- **Evidence**: App `ui/chat/ChatEdits.kt` `rewind` (71-101), `GatewaySession.submit` (81-86), `ChatReducer.onTruncated` (171-174); tests `GatewaySessionTest.submitWithTruncationConfirmsTheCut`, `ChatReducerTest.truncationDropsTheEditedRowAndEverythingAfterIt`, `ChatViewModelTest.regenerateResubmitsThePrecedingUserMessage`. Hermes `tui_gateway/methods_prompt.py` `_truncate_history_for_submit` (474-584), `hermes_state_messages.py` `replace_messages` (816-835), `tui_gateway/methods_session_branch.py` `_branch_live` (86-143), `methods_session.py` `session.branch` (2202), `session.branch_whole` (2207), `session.branch_stored` (489), `contracts/sessions.py` (154-176, 402-441).
- **If missing**: Edit and Regenerate destroy the old line from the user's point of view; there is no way to try an alternative and keep both. Effort M: "Branch from here" in the long-press menu, open the child chat, show parent/child in the drawer.
- **Blocked on Hermes?**: no for forking. Yes for "show me what I rewound": no gateway method returns the archived rows.

On the wire, Edit and Regenerate are the same call (verified): `prompt.submit {session_id, text, truncate_before_row_id: <user row>, confirm_truncate: true, confirm_deep_truncate: true, confirm_empty_truncate: true}`. The result's `survivor_user_row_ids` rebinds the kept bubbles. Error 4009 while a turn runs, 4018 when the row is gone, 5070-5072 when storage failed after the cut.

Is the rewound part lost? In the app, yes: the rows are removed from the transcript and never come back. On the server, no: `replace_messages(…, active_only=True, archive_dropped=True)` soft-archives the cut rows (`active = 0`, `compacted = 0`). They stay in `state.db` and in the full-text index. They are readable only through `GET /api/sessions/{id}/export` (which includes inactive rows; the app does not call it) or `get_messages(include_inactive=True)` inside Hermes. `session.history` and `session.resume` return active rows only.

The branch methods (verified, 0.22.2 source):
- `session.branch {session_id (live), name?, count?, idempotency_key?}` → `{session_id, stored_session_id, title, parent, message_count, messages, info}`. Creates a new stored child with the parent's visible user and assistant rows, linked by `parent_session_id`, and a live session for it. `count` keeps only the first N of those rows.
- `session.branch_whole`: the same for the whole history, without echoing the messages back.
- `session.branch_stored {parent_session_id (stored), source?, cwd?, idempotency_key?}` → `{session_id, stored_session_id, message_count, messages_omitted, info}`. Whole-session copy of a stored parent; the transcript is copied server-side. It has no `count`, so it cannot fork at a message.

Can the user fork at a message and keep both lines? The app cannot today. Hermes can: call `session.branch` with `count` = number of visible user and assistant rows up to the chosen message, then continue in the child; the parent stays untouched. Two things to check in a probe before building: `count` indexes the server's filtered list (rows with visible text, reconciled with live memory), so the app must count the same way or the cut is off by a row; and the copy drops tool rows, so the child's model context has the text of the conversation but not its tool results (inferred from `_visible_branch_history`). "Keep both lines" for Edit would then be: branch at the message first, then send the edited text in the child.

### 8. Sessions the app did not create
- **Status**: partial. Sessions from other sources are listed and can be opened and continued. `session.active_list`, `session.set_hidden`, `session.workspace.move`, in-chat search, pinning and transcript export are all missing. None of the missing ones is covered by a ruling.
- **Evidence**:
  - List request: `app/src/main/kotlin/app/tsukaima/data/api/HermesApi.kt` `drawerSessions()` (l.124–128) and `sessions()` (l.106–118). Test: `HermesApiTest` (l.277 asserts `/api/sessions?limit=100&archived=include&exclude_sources=cron`).
  - Drawer split: `app/src/main/kotlin/app/tsukaima/ui/drawer/DrawerViewModel.kt` `DrawerUiState.grouped/cron/archived` (l.64–80). Test: `DrawerViewModelTest.cronRunsAreListedApartAndCollapsedByDefault`.
  - Drawer actions: `app/src/main/kotlin/app/tsukaima/ui/drawer/SessionsDrawer.kt` l.186–198 (long-press menu: Rename, Archive/Unarchive, Delete), l.259 (search field).
  - Resume: `app/src/main/kotlin/app/tsukaima/data/gateway/GatewaySession.kt` `resume()` (l.35–43), called from `ChatViewModel.open()` (l.252).
  - Hermes list route: `hermes_cli/web_routers/sessions.py` `get_sessions` (l.177–240); resume guard: `tui_gateway/methods_session.py` `_resume_guard` (l.841–865).
- **If missing**: the user cannot see which chats are running now, cannot pin a chat, cannot hide one, cannot move a chat's workspace after creation by stored key, cannot search inside the open chat and cannot export a transcript. Effort: `active_list` S, pin S, hide S, export S–M, in-chat search M, `workspace.move` S.
- **Blocked on Hermes?**: no. Every route or method needed already exists per 0.22.2 source (`PATCH /api/sessions/{id}` takes `pinned` and `hidden`, sessions.py l.886–905; `GET /api/sessions/{id}/export`, l.929; the list row already carries `pinned` and `is_active`, l.230–233).

**Does the drawer filter by source?** Only for cron. It makes two requests: chats with `exclude_sources=cron`, and cron runs with `source=cron`, each capped at 100 rows and both with `archived=include`. Telegram, Matrix, TUI, desktop and API sessions all land in the chat list, grouped by profile, with no label that says where they came from (`SessionDto.source` is read only for `isCron`, `ApiModels.kt` l.63). Cron runs sit in a "Cron runs" section that is collapsed by default.

**Does Hermes filter by default?** Not by source. Per 0.22.2 source `GET /api/sessions` with no parameters returns every source, cron included; it drops only archived rows (`archived=exclude`), hidden rows (`s.hidden = 0`, `hermes_state_sessions.py` l.1401) and subagent child sessions unless `sessions.show_subagents` is on. The cron split is the client's choice, copied from the dashboard (`docs/protocol/README.md` l.240).

**Can a non-Tsukaima session be continued from the phone?** Yes, as far as the code goes (inferred, not probed for each source). Tapping any row calls `session.resume {session_id, source:"tsukaima", lazy:false, defer_history:false}`. The server's resume guard refuses only kanban worker transcripts (error 4132) and transcripts over `sessions.max_resume_messages` (4130); it has no source check. A cron run resumes like a chat. What I did not verify: whether a reply typed on the phone into a Telegram or Matrix session is also delivered to that platform. I would expect it to stay in the stored session only.

**Two limits the question did not ask about** (verified):
- The chat list is the newest 100 by creation time. The app never sends `offset` or `order=recent` (`MAX_SESSIONS = 100`, HermesApi.kt l.622). With about 190 non-cron sessions on the server (the question's counts; not queried), about 90 are reachable only through search, and an old chat that is still in use does not move up.
- Cron runs: the newest 100 of about 870.

**Confirm or correct each:**
- `session.active_list` unused: **confirmed**. The app also ignores the `is_active` flag that the REST list already returns.
- `session.set_hidden` unused: **confirmed**.
- `session.workspace.move` unused: **confirmed**. The app has `session.cwd.set` for the live session only (`GatewaySession.setCwd`, l.117).
- Search inside the open chat: **confirmed missing**. The drawer search is server-side full-text (`/api/sessions/search`, 2+ characters, 300 ms debounce; ruling v0.5.0 in `2026-10-20-build-rulings.md`), but a hit opens the chat at the bottom, not at the match.
- Pinning: **confirmed missing**.
- Export or share of a whole transcript: **confirmed missing**. The chat overflow has Copy session ID, Rename, Archive, Delete, Compact (`ChatScreen.kt` l.522–551); copy is per message.
- Drawer features that do exist: search, rename, archive and unarchive, an archived view with a count, delete with confirmation, live refresh on `sessions.changed`.

### 9. Memory
- **Status**: missing. No ruling covers it.
- **Evidence**: no reference to `api/memory`, `api/learning`, `api/curator` or `honcho` anywhere in `app/src/main/kotlin` (grep; the only "honcho" hit is preview text in `MarkdownTable.kt` l.126). `HermesApi.kt` has no such call.
- **If missing**: the user cannot see, correct or delete what Hermes remembers about them from the phone; the only route is the dashboard link in Settings → Diagnostics. Effort: read-only list S; edit and delete M.
- **Blocked on Hermes?**: no for the built-in memory files. **Yes for Honcho**: per 0.22.2 source there is no route that returns Honcho's stored facts, so a view of what this instance actually remembers needs a new route.

Routes per 0.22.2 source:
- `/api/memory` is 6 routes, not 5, and none of them returns memory content. `GET /api/memory` gives the active provider, provider statuses and the byte sizes of `MEMORY.md` and `USER.md`; `PUT /api/memory/provider` switches provider; `POST /api/memory/reset` deletes the files (`web_routers/ops.py` l.491–540). `GET`/`PUT /api/memory/providers/{name}/config` and `POST …/setup` configure a provider (`memory_providers.py` l.508–551).
- The content lives under `/api/learning`: `GET /api/learning/graph` returns each `MEMORY.md` and `USER.md` entry as a card, plus learned skills; `GET`, `PUT` and `DELETE /api/learning/node` read, rewrite and remove one card (`status.py` l.677–726). `agent/learning_graph.py` reads only those two files; it has no Honcho code.
- `/api/curator` is skill maintenance (status, pause, run now; `status.py` l.629–675). It is not user memory.
- There are no `memory.*` gateway methods.

**Smallest useful version**: a read-only "What Hermes remembers" screen from `GET /api/learning/graph`, memory cards only, with a delete action on each card (`DELETE /api/learning/node`). Delete is worth including from the start because a wrong memory is the reason a user opens this screen. Editing can wait. On this instance the screen would be close to empty, because the facts are in Honcho; say so on the screen instead of showing a blank list.

### 10. Managing connectors, MCP servers, the vault, provider keys and config
- **Status**: left out on purpose for provider keys, config, env and MCP — by design text, not by a numbered ruling. Missing with no ruling for connectors and the vault.
- **Evidence**:
  - `app/src/main/kotlin/app/tsukaima/ui/settings/SettingsScreen.kt` l.328: Toolsets hint "Provider keys and setup stay in the dashboard." l.440–441: row "Open admin dashboard — Config, env, MCP, webhooks — in the browser". l.314: "Installing new skills is a desktop task."
  - The same rows are in the build plan, `docs/superpowers/plans/2026-10-20-tsukaima-android.md` l.301 (Task 15 Settings: "Open admin dashboard → `Intent.ACTION_VIEW serverUrl`"). No entry in `2026-10-20-build-rulings.md` or the completeness pass states the exclusion, and nothing mentions connectors or vault management.
  - The only connector or vault calls in the app are reactive: `connection.respond`, `connectors.operation.wake` (`GatewaySession.kt` l.276–289) and the vault prompt answers.
- **If missing**: no screen shows what is connected, none disconnects an account, and the vault cannot be locked from the phone. A lost or borrowed phone leaves the vault as the last session left it. Effort: connectors list plus disconnect M; vault lock S; vault list and remove M.
- **Blocked on Hermes?**: no. `connectors.list`, `connectors.accounts`, `connectors.accounts.remove`, `vault.list`, `vault.lock`, `vault.unlock`, `vault.remove`, `model.save_key`, `model.disconnect`, `config.get` and the `/api/mcp`, `/api/providers`, `/api/credentials/pool`, `/api/config`, `/api/env` routes all exist per 0.22.2 source.

Is there any management screen? No. Settings has Hermes instance, Profile, Model, Skills (toggle), Toolsets (toggle), Pairing (approve, revoke), Notifications, Voice, Diagnostics, Support, Theme.

Recommendation per family:

| Family | Where | Reason |
|---|---|---|
| Connectors: list, accounts, disconnect | Phone | The phone already runs the connect flow; a user who can connect from a card needs to see and undo it in the same place. |
| Connector policy (`connectors.policy.*`) | Dashboard | Set once, rarely changed, and a wrong value silently widens what the agent may do. |
| Vault: lock, unlock, list entry names, remove | Phone | Locking is a safety action wanted away from the desk; names only, never values. |
| Vault: add entry, sources | Dashboard | Typing secrets and choosing a password-manager source is setup work, better with a keyboard. |
| MCP servers: list with enabled switch | Phone, read plus toggle | Same shape as the existing Skills and Toolsets switches. |
| MCP servers: add, edit, OAuth, catalog install | Dashboard | Command lines, env vars and OAuth callbacks are desktop work. |
| Provider keys (`model.save_key`, `/api/providers`, `/api/credentials`) | Dashboard, phone shows status only | Pasting API keys on a phone is error-prone and it is a one-time task; the model picker already shows `authenticated`. |
| Server config (`config.get`, `/api/config`) | Dashboard | Hundreds of keys with a schema editor; the phone already exposes the few that matter per session (model, reasoning, workspace). |
| Env (`/api/env`, reveal) | Dashboard | It reveals secrets; keep that off a device that has no app lock (item 15). |

---

## Phone-specific gaps

### 11. What happens when Android kills the foreground service
- **Status**: partial. Background delivery works only while the process and its one WebSocket live. There is no recovery path once Android stops either.
- **Evidence**:
  - `app/src/main/AndroidManifest.xml` l.114–117: `GatewayService`, `foregroundServiceType="dataSync"`. Permissions (l.5–12): no `RECEIVE_BOOT_COMPLETED`, no `WAKE_LOCK`, no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. No boot receiver.
  - `app/src/main/kotlin/app/tsukaima/notifications/GatewayService.kt`: `onStartCommand` returns `START_NOT_STICKY` (l.49, l.59); `onTimeout` calls `stopSelf()` (l.63–65); `onDestroy` → `BackgroundController.onServiceEnded` → `connection.stop()` (l.67–75). No `onTaskRemoved`.
  - `app/src/main/kotlin/app/tsukaima/notifications/BackgroundPolicy.kt` `shouldStartService` (l.15–26); `BackgroundController.onBackground` (l.54–64). Tests: `BackgroundPolicyTest`, `BackgroundControllerTest.serviceEndedWhileBackgroundedStops`, `refusedServiceStartStops`.
  - `app/src/main/kotlin/app/tsukaima/data/gateway/GatewayClient.kt` l.65–67: heartbeat 15 s, deadline 45 s, backoff 1/2/5/10 s, retried without limit; `onTick` (l.338–346) compares wall-clock time.
  - `app/src/main/kotlin/app/tsukaima/data/ConnectionManager.kt` l.86: background status poll every 60 s.
  - `session.resume` is called from only two places, both inside the chat ViewModel: `ChatViewModel.open()` l.252 and `ChatReconnector` l.97.
  - `app/build.gradle.kts` l.22–23: `minSdk 29`, `targetSdk 36`.
- **If missing**: an approval or question raised after Android has stopped the service, or after a background reconnect with no chat screen alive, reaches the user only when they next open the app. The agent's turn waits for its own timeout. Effort: battery-exemption prompt plus `START_STICKY` plus re-resume outside the ViewModel M; a real push path L.
- **Blocked on Hermes?**: no for the hardening. Yes for push: Hermes would have to send a wake-up (UnifiedPush or ntfy) when a server request or `message.complete` has no attached client. The existing messaging platforms (Telegram, Matrix) already deliver approvals out of band and are the working fallback today.

**Measured on a real phone: nothing.** I searched `docs/`, `README.md` and `CHANGELOG.md` for Doze, battery, swipe, OEM and device measurements. There is no measurement of background survival or delivery delay. The docs say the opposite several times: "No on-device check" (rulings, deferred minors for v0.3.0, v0.4.0, v0.5.0, v0.5.1), and the device-check lists in the completeness pass do not include background delivery at all. The only stated cost is the Task 19 amended ruling: "cost if wrong: battery while backgrounded (bounded by 60s poll + one socket)". Everything below is reasoned from code and platform rules.

**Push path**: none (no FCM, UnifiedPush, ntfy, WorkManager or AlarmManager in the code or the dependencies). Confirmed.

**Trace:**
- **App put in the background** (verified): `ProcessLifecycle` ON_STOP → `BackgroundController.onBackground`. If a server URL and password are stored, notifications are permitted and at least one notification kind is on, the running socket is kept, polling drops to 60 s and `GatewayService` starts. Otherwise the socket is closed and the app is silent. If the user denied the notification permission, there is no background delivery at all.
- **Swiped away from recents** (inferred): the service has no `onTaskRemoved`, so on stock Android the process and the socket survive and requests keep arriving for the sessions the socket is already attached to. The activity and its `ChatViewModel` are destroyed, though, and the ViewModel is the only code that re-attaches a session. After the next socket drop the client reconnects but resumes nothing, so approvals and "task done" for that chat stop arriving until the app is opened. Status, cron-failed and pairing notifications continue, because they come from REST polling.
- **Doze** (inferred): no wake lock and no battery-optimisation exemption is requested. A foreground service keeps the process, but the CPU may still sleep. The heartbeat uses `delay(15 s)` and a wall-clock deadline of 45 s, so after any sleep longer than 45 s the first tick closes the socket as "heartbeat timeout" and reconnects (1 s backoff, new ticket). If the chat ViewModel is still alive it resumes the session and re-adopts open requests (`ChatReconnector`); if not, see the previous point. Delay is therefore unbounded in principle: it lasts until the device next wakes.
- **Android 15 and later** (inferred from the platform rule; `targetSdk 36` makes it apply): a `dataSync` foreground service may run about 6 hours in 24. Then `onTimeout` stops the service, `onServiceEnded` disconnects, and the app is silent until it is opened again. Nothing tells the user. For a phone left overnight this is the most likely failure.
- **OEM battery manager kills the process** (inferred): `START_NOT_STICKY`, no boot receiver, no alarm. Nothing restarts it. The same holds after a reboot.
- **Notification Approve**: needs a device unlock on API 31+ (`setAuthenticationRequired`, `Channels.kt` l.99), and if the socket is down the answer is not sent and the notification is re-posted (`ApprovalAnswerer`, `NotificationHub.kt`).

### 12. No network
- **Status**: missing. No ruling covers offline use. The "Not sent / Retry" behaviour for a failed send is a ruling (v0.5.0), but it is in memory only.
- **Evidence**:
  - Persistence is DataStore preferences for settings (`app/src/main/kotlin/app/tsukaima/data/prefs/SettingsStore.kt` l.144–164: URL, profile, model, theme, last ids, notification and voice switches) and `EncryptedSharedPreferences` for credentials. No Room or SQLite dependency (`app/build.gradle.kts` l.85–101). Files on disk: a crash record, problem reports, share-sheet copies in `cacheDir/shared` (24 h), temporary audio.
  - Transcript source: `ChatViewModel.open()` l.239–262 → `session.resume` over the WebSocket. Drawer: `GET /api/sessions`, held in memory.
  - Draft: `ChatUiState.input` (`ChatUiState.kt` l.35), set by `ChatViewModel.setInput` (l.351). No `SavedStateHandle`; no `rememberSaveable` for the composer.
  - Send: `ChatUiState.writable`/`canSend` (l.82–90), `ChatViewModel.send()` (l.357–364), `submit()` (l.436–456), `retrySend()` (l.462–).
- **If missing**: with no network the user cannot read any chat that is not already open in memory, loses a typed draft if the process dies, and cannot write a message at all. Effort: draft persistence S; a read-only cache of recent transcripts M–L (cache invalidation against edits, compaction and rewinds is the cost); an offline outbox M.
- **Blocked on Hermes?**: no.

**Open and read an earlier chat offline?** No. The drawer list and every transcript come from the server each time. A chat that is already on screen keeps showing what it had ("history stays visible, the composer is disabled", `ChatUiState.kt` l.78). After a cold start offline, the drawer is empty and a chat does not load.

**Does an unsent draft survive the app being killed?** No. The text lives only in the ViewModel's state. It survives rotation and a trip to the background while the process lives; process death loses it. Attachments already uploaded are lost with it. (Share-sheet files are the exception: copies stay on disk for 24 h, but the draft that referenced them does not.)

**Offline send: queued or fails at once?** Neither, in the usual case: the composer is disabled while offline (`enabled = state.writable`, `ChatScreen.kt` l.389), so the user cannot type or send. If the connection drops between the tap and the server's answer, `submit()` catches the error, the bubble stays marked "Not sent" with Retry and Dismiss, and a toast says "Message not sent: …". Retry is manual and the pending text is held in memory (`failedSends`), so it does not survive process death and nothing goes out by itself when the network returns. (The "Queued" label in the app is a different thing: a message sent while a turn runs, held by the server.)

### 13. What can the user put into a message
- **Status**: partial. Files of any type, images and PDFs are done; camera capture, recorded audio, and location are missing; video is only possible as a plain file.
- **Evidence**:
  - Picker: `app/src/main/kotlin/app/tsukaima/ui/chat/ChatScreen.kt` l.190 (`OpenMultipleDocuments`), l.220 (`picker.launch(arrayOf("*/*"))`).
  - Limits and messages: `app/src/main/kotlin/app/tsukaima/ui/chat/ChatAttachments.kt` `Attachments.MAX_BYTES`/`TOO_LARGE` (l.60–61), `PDF_MAX_PAGES` (l.70), `pdfPageCapNote` (l.105–109), `attach()` (l.181–201), `uploadLocked()` (l.294–302). Tests: `ChatAttachmentsTest.pdfPageCapNote`, `ChatViewModelTest` l.1071–1072.
  - Share sheet: `AndroidManifest.xml` l.67–79; `app/src/main/kotlin/app/tsukaima/share/ShareActivity.kt` l.204, l.209.
  - Rulings: 12 MB cap, `2026-10-20-build-rulings.md` "v0.2.0 rulings"; PDF cap text, completeness pass device check 7.
- **If missing**: to send a photo the user must leave the app, take it, and come back through the file picker or the share sheet. There is no way to send a voice note as audio or the current location. Effort: camera capture S; audio message M; location S; video as a real video input depends on the model.
- **Blocked on Hermes?**: no for camera and location (an image and a line of text). Audio and video as model input: partly — `file.attach` puts the file in the workspace as an `@file:` reference, which works today; sending it to the model as audio or video content would need a new attach method. PDF pages past 25: no, `pdf.attach` already takes `first_page` and `last_page`.

**Confirm or correct each:**
- Camera capture: **confirmed missing**. No camera permission, no `TakePicture` contract, no photo picker; the only entry is the system document picker.
- Voice message sent as audio: **confirmed missing**. Voice mode uploads audio only to `/api/audio/transcribe` and sends the text.
- Video: **corrected in part**. There is no video capture and no video handling, but the in-app picker accepts `*/*`, so a video file up to 12 MB can be attached. It goes through `file.attach` and reaches the agent as a workspace file, not as something the model sees. The same is true for an audio file. The share sheet does not accept either (text, images and PDF only).
- Location: **confirmed missing**. No location permission or code.

**PDF past page 25**: the server renders pages 1–25 and drops the rest without reporting the total. The user is told: the attachment chip shows "25 pages" and a second line "First 25 pages attached". Two details: the note also appears for a PDF of exactly 25 pages (the app cannot tell the difference), and there is no way to send pages 26 onward, although the server call supports a page range.

**File over 12 MB picked in the app**: it is refused, not truncated, and the user is told by a toast, "File too large (max 12 MB)". No chip is added. If the size was unknown up front and the stream turns out larger, the chip that was already shown is removed and the same toast appears. From the share sheet the toast is "Skipped <name>: over 12 MB". So neither case is silent; both use a short toast that is easy to miss when several files are picked at once.

### 14. The rendering contract

- **Status**: partial. Markdown rendering is broad and done (tables included). Not rendered: raw HTML, footnotes,
  syntax highlighting, diagrams, inline audio and video players, local-path markdown images. The server hint is
  wrong on tables and on audio/video.
- **Evidence**: parser `domain/markdown/Markdown.kt` (`parseLines` 94–198), `MarkdownInline.kt` (`parse` 28–153),
  `MarkdownTables.kt` (`parse` 12–28); renderers `ui/chat/components/MarkdownText.kt` (`MarkdownText` 171–225),
  `MarkdownTable.kt` (42–104), `CodeBlock.kt` (47–120), `MathBlock.kt` (`MathBlock` 358), `MediaCard.kt` (81),
  `ImageCard.kt` (74); media rules `ui/chat/ChatMedia.kt` (`Media` 35–78, `open` 158–180); contract tests
  `app/src/test/kotlin/app/tsukaima/domain/markdown/MarkdownTest.kt`, `MediaDirectiveTest.kt`.
- **If missing**: an audio or video file the agent delivers is a file card that downloads and opens in another app,
  not something that plays in the chat (M: needs a player dependency; none is in the build). Code is monochrome
  (M). Mermaid shows as source (L). A local path written as a markdown image shows only its alt text, so the user
  sees no picture and no card (S: treat an absolute-path image line like a `MEDIA:` line).
- **Blocked on Hermes?**: no. Everything here is client rendering. `/api/media` already serves local files (used
  for `MEDIA:` cards and `vision_analyze` thumbnails).

Markdown is rendered in two places only: assistant replies (`AssistantMessage.kt:53`) and side-note cards
(`SideNoteCard.kt:37`). User bubbles, reasoning text (`AssistantMessage.kt:75`), tool output, server notices and
subagent text are plain text.

| Construct | Rendered? | How / what the user sees | Evidence |
|---|---|---|---|
| Headings `#`–`######` | yes, all 6 levels | h1 19sp serif, h2 17sp serif, h3 15sp semibold, h4–h6 identical 14sp semibold. Setext (`===`, `---` underline) also read. | `Markdown.kt:276` `atxHeading`, `:292`; `MarkdownText.kt:228` `headingStyle`; test `atxHeadings`, `setextHeadingsTakeOnlyTheLineAbove` |
| Bold `**x**` | yes | semibold | `MarkdownInline.kt:133`; `MarkdownText.kt:71` |
| Italic `*x*`, `_x_` | yes | italic; `snake_case_name` stays literal | `MarkdownInline.kt:138–147`; test `italicAndUnmatchedMarkersStayLiteral` |
| Nested emphasis (`***x***`, `**a *b* c**`) | no | inner markers show as literal asterisks; `***x***` shows a bold `*x` plus a stray `*` | inferred from `MarkdownInline.kt:38` and `:127` (inner pass has emphasis off); no test |
| Strikethrough `~~x~~` | yes | line-through, dimmed. Single `~` is literal. | `MarkdownInline.kt:128`; test `strikethrough` |
| Inline code | yes | mono on sunken background; backtick runs of any equal length | `MarkdownInline.kt:98`; test `codeSpanNeedsAMatchingRun` |
| Links `[t](url)` | yes, `http(s)` and `mailto:` only | accent, underlined, tap opens the browser. A relative or other-scheme link shows its text only, not tappable. Reference links `[t][ref]` are literal. | `MarkdownInline.kt:105–117`, `:179` `isLinkable`; test `linksAndAutolinks` |
| Autolinks | yes for bare `http://` / `https://` and `<https://…>` | trailing punctuation dropped. `www.example.com` and bare e-mail addresses are not linked. | `MarkdownInline.kt:118–126`, `:188`; test `autolinkStopsAtTrailingPunctuationAndUnbalancedParen` |
| Backslash escapes | yes | `\*` shows `*` | test `backslashEscapesRenderThePunctuation` |
| Line breaks | yes | a single newline inside a paragraph is a visible line break (lines are joined with `\n`, not reflowed) | `Markdown.kt:102`; inferred for the visual result |
| Blockquotes | yes | 2dp accent bar, 12dp indent; holds any block (lists, tables, images); nests to 5 levels, deeper `>` is literal | `Markdown.kt:167–178`; `MarkdownText.kt:201`; tests `blockquoteContainingABulletList`, `nestedQuotes` |
| Unordered lists `-` `*` `+` | yes | `•` / `◦` / `▪` by depth | `Markdown.kt:318`; `MarkdownText.kt:241` |
| Ordered lists `1.` `1)` | yes | numbers continue from the first marker at each depth (`1. 1. 1.` shows 1, 2, 3); later literal numbers are ignored | `Markdown.kt:355`; test `orderedListNumbersFromItsFirstMarker` |
| Nested lists | yes, 4 levels (depth 0–3) | 16dp per level; a 5th level and deeper is drawn at level 4. Two- or four-space indent both work; ordered and bullet can mix. | `Markdown.kt:384` `MAX_DEPTH = 3`; `MarkdownText.kt:249`; test `nestedListDepthsFollowIndentation` |
| Lists with blank lines between items | degraded | a blank line ends the list. The next item starts a new list at depth 0, so a nested item after a blank line loses its indent, and a loose `1.` `1.` `1.` shows 1, 1, 1. | inferred from `Markdown.kt:133` (`flush()` on a blank line) and `:351–352`; no test |
| Fenced code inside a list item | partly | the fence ends the list; the code block is drawn full width, not indented under the item; the next item starts a new list | `Markdown.kt:119–120`; test `indentedFenceInsideAListKeepsItsBodyDedented` |
| Task lists `- [ ]` / `- [x]` | yes, read-only | `☐` / `☑` in place of the bullet | `Markdown.kt:374` `taskBox`; `MarkdownText.kt:253`; test `taskListItemsCarryChecked` |
| GFM tables | yes | hairline-bordered card, semibold header, row rules, column alignment from the separator row. Cells take inline markdown and inline math, no block content. | `MarkdownTables.kt:12`; `MarkdownTable.kt:42`; tests `tableWithAlignment`, `tableRaggedRowsArePaddedAndTruncated` |
| Wide table on a phone | horizontal scroll | each column is as wide as its widest cell, capped at 220dp; a cell past the cap wraps inside the column. If the columns together are wider than the reply, the card scrolls sideways (`horizontalScroll`). Nothing is squeezed, clipped or dropped. Header row and first column do not stay fixed. | `MarkdownTable.kt:31–33` (`MaxColumn = 220.dp`), `:52` (`horizontalScroll`), `:70–77`; README line 24; CHANGELOG 0.7.0 line 192 |
| Footnotes `[^1]` | no | raw markup: `[^1]` in the text and `[^1]: …` as an ordinary paragraph | inferred: `MarkdownInline.kt:108–109` (a `]` not followed by `(` is literal); no test |
| Raw HTML | no, not stripped | shown as literal text (`<br>`, `<details>`, `<sub>`, `&nbsp;` all appear as typed) | inferred: no HTML or entity handling anywhere in `domain/markdown/`; no test |
| Fenced code | yes | dark code well; long lines do not wrap, the block scrolls sideways. A fence of 4+ backticks can hold a 3-backtick fence. An unterminated fence renders as code. | `CodeBlock.kt:47`, `:111` (`softWrap = false`), `:114`; tests `fencedCodeWithLang`, `fourBacktickFenceClosesAndKeepsInnerTripleFence` |
| Language label | yes | the info string's first word in uppercase at the top-left, `CODE` when none | `CodeBlock.kt:78` |
| Syntax highlighting | no | one ink colour for all code | `CodeBlock.kt:107–116`; no highlighter dependency in the build |
| Indented (4-space) code blocks | no | read as a normal paragraph (or as a continuation of the list item above) | inferred from `Markdown.kt:191–192` |
| Mermaid / other diagrams | no | a plain code block labelled `MERMAID` showing the source | follows from `MarkdownText.kt:194`; no WebView or diagram library in the app |
| LaTeX math, inline | yes | `$…$`, `$$…$$` inside a line, and `\(…\)`. Drawn by JLaTeXMath as an inline image; too wide: shrinks to 70 %, then clips with a "…" chip that opens it in a dialog. `$5 and $10`, `$HOME/$USER` stay text. | `MarkdownInline.kt:84–97`, `:236`, `:243`; `MathBlock.kt:206`, `:260`; tests `inlineMathBetweenDollars`, `dollarAmountsAreNotMath`, `shellVariablesAreNotMath` |
| LaTeX math, block | yes | `$$…$$` or `\[…\]` starting a line, closing on the same or a later line with no blank line between. Centred; scrolls sideways when wide. | `Markdown.kt:241` `displayMath`; `MathBlock.kt:358`; tests `displayMathBlock`, `displayMathSearchStopsAtABlankLine` |
| Math that cannot be drawn | fallback | TeX source shown as code (inline) or a `TEX` code block (display). Applies to formulas over 2000 characters and to `\newcommand`, `\def`, `\input`, `\includegraphics`, `\definecolor` and similar. A bare `\begin{align}` with no delimiters is plain text. | `MathSafety.kt:14`, `:27`; `MathBlock.kt:371`; `MarkdownText.kt:76` |
| Horizontal rule `---` `***` `___` | yes | 1dp hairline | `Markdown.kt:300`; `MarkdownText.kt:208`; test `rulesVersusSetext` |
| Image `![alt](http…)` alone on a line | yes | inline image card, full width, at most 280dp tall; tap opens the URL in the browser. Same origin as the Hermes server: loads by itself. Any other host: card says "From host — tap to load" and loads only after a tap. On failure the alt text stands in. | `Markdown.kt:419` `imageLine`; `ImageCard.kt:74`; `ChatMedia.kt:232` `imageAllowed`; test `imageBlockOnlyForHttpUrls`; completeness-pass line 138 |
| Image `![alt](http…)` inside a sentence, or two on one line, or indented under a list item | no image | a tappable link whose text is the alt (or the URL when the alt is empty) | tests `inlineImageStaysALink`, `imageLineInsideAListItemStaysInTheItem` |
| LOCAL path as a markdown image (`![chart](/tmp/a.png)`, `file://…`, relative) | no | only the alt text as plain text; with an empty alt, nothing visible. No image, no card, no link. | `MarkdownInline.kt:114` (`else -> lit.append(text)`); `Markdown.kt:422`; test `imageBlockOnlyForHttpUrls` (relative case pinned; absolute path follows the same branch, inferred) |
| `MEDIA:<path>` line | yes | file card. Must be the whole line, outside a code fence and not on a bullet's own line, with an absolute or `~/` path. While streaming, the last line is not converted until its newline arrives. | `Markdown.kt:395` `mediaPath`, `:138`; `MediaCard.kt:81`; `MediaDirectiveTest`; rulings "v0.3.0 rulings (`MEDIA:` file cards)", first two rulings |
| `MEDIA:` image (png, jpg, jpeg, gif, webp, bmp, up to 10 MB) | image preview + file card | preview above the card row, at most 280dp tall. GIF as a still frame (inferred: decoded to one bitmap). | `ChatMedia.kt:37`, `:40`; `MediaCard.kt:87–125` |
| `MEDIA:` svg, tiff, heic | file card only | image icon, no preview | `ChatMedia.kt:40–42` |
| `MEDIA:` audio (mp3, m2a, wav, ogg, opus, m4a, flac) | file card only | audio-file icon, name, folder, download glyph. No player. | `ChatMedia.kt:44`; `MediaCard.kt:66–73` |
| `MEDIA:` video (mp4, mov, avi, mkv, webm, 3gp) | file card only | video-file icon. No player, no poster frame. | `ChatMedia.kt:43` |
| `MEDIA:` documents, archives, anything else | file card | document, archive or generic file icon | `ChatMedia.kt:45–49` |
| Tap on any `MEDIA:` card | download | saves to Downloads, toast "Saved to Downloads", then hands the file to another app with `ACTION_VIEW`. If no app handles the type, the toast is all the user gets. | `ChatMedia.kt:158–180`; `data/files/FileSaver.kt:60` `viewFile`; rulings v0.3.0, last ruling |
| Inline audio playback | no | see the audio row. The only `MediaPlayer` in the app plays server TTS in voice mode (`voice/ServerSpeechOutput.kt`). No ExoPlayer/media3 dependency. | grep over `app/src/main/kotlin` and the Gradle files |
| Inline video playback | no | see the video row | same grep |
| Emoji | yes, as text | Unicode emoji pass through untouched and are drawn by the system emoji font (inferred: the bundled fonts carry none). `:shortcode:` is not converted. | no emoji handling in `domain/markdown/` |

Sub-questions, in the question's order. Tables: rendered; a wide one scrolls sideways inside its card, columns
capped at 220dp with wrapping cells. Nested lists: rendered to 4 levels, but only when the list is tight (no blank
lines between items). Footnotes: not rendered, raw markup. Raw HTML: not rendered and not stripped, shown as typed.
Syntax highlighting: none; there is a language label and a Copy button. Mermaid and other diagrams: not rendered,
shown as a code block. Inline audio playback: no. Inline video playback: no. A local path written as a markdown
image: not rendered; the user sees the alt text only. The hint's "there are no tables, so use lists instead" is
wrong since 0.7.0. The hint's "renders images, audio and video inline" is right for images only: an `http(s)` image
on its own line, or a `MEDIA:` image file. The README wording ("file cards with image previews") is the accurate
one. No ruling says players were left out on purpose; the v0.3.0 ruling only fixes the behaviour as "save, then
`ACTION_VIEW`".

**Proposed replacement for `platform_hints.tsukaima`** (derived only from the rows above):

> Tsukaima renders Markdown in replies: headings, bold, italic, strikethrough, inline code, links, block quotes,
> horizontal rules, bullet and numbered lists nested up to four levels, task lists, and GFM tables. Tables are
> fine; a wide one scrolls sideways, so prefer a few short columns. Keep lists tight: do not put blank lines
> between items or a code block inside an item, and do not nest emphasis (no `***text***`). Fenced code blocks
> show a language label and a copy button but no syntax highlighting, and long lines scroll sideways. LaTeX math
> is rendered: `$…$` or `\(…\)` inline, `$$…$$` or `\[…\]` on its own lines; do not define macros. Do not use raw
> HTML, footnotes, or Mermaid and other diagram blocks; they appear as plain source text. To show a picture, put
> `![alt](https://…)` alone on its own line (images from hosts other than this server load only after the user
> taps them); never write a local file path as a Markdown image. To deliver a file, write `MEDIA:/absolute/path`
> alone on its own line: it becomes a download card, with a preview for PNG, JPEG, GIF, WebP and BMP images.
> Audio and video are delivered as download cards and do not play inside the chat, so say in words what the file
> contains.

### 15. Entry points outside the app, and protection of the app itself
- **Status**: missing: widget, Quick Settings tile, launcher shortcuts, app lock. Neither planned nor ruled out — no document mentions any of them. Done: device-assistant entry, share target, unlock-to-approve on notifications.
- **Evidence**:
  - `app/src/main/AndroidManifest.xml`: no `<receiver>` for a widget, no `TileService`, no `android.app.shortcuts` meta-data. `app/src/main/res/xml/` holds only backup rules, file paths, network security and the two assistant descriptors.
  - No `BiometricPrompt`, no `FLAG_SECURE`, no biometric dependency (`app/build.gradle.kts` l.85–101).
  - Credentials: `app/src/main/kotlin/app/tsukaima/data/prefs/CredentialStore.kt` `EncryptedCredentialStore` (l.27–75).
  - Related rulings: Task 19 S10 (notification Approve needs unlock on API 31+; on API 29–30 it opens the app instead); v0.6.0 (the assistant sheet closes at once on a locked keyguard: "nothing on the lock screen").
- **If missing**: no one-tap new chat or voice from the home screen, and anyone holding the unlocked phone can open the app, read every chat, approve shell commands and answer sudo or vault prompts. Effort: static launcher shortcuts S; Quick Settings tile S; app lock with `BiometricPrompt` and `FLAG_SECURE` M; widget M.
- **Blocked on Hermes?**: no.

**How the password and token are stored** (verified): the dashboard password, the access token and the origin are in `EncryptedSharedPreferences` file `tsukaima_creds` (AES-256-SIV keys, AES-256-GCM values) under a Keystore-backed `MasterKey` (AES-256-GCM). The key is not bound to user authentication, so the app can read it whenever the device has been unlocked once since boot. If the encrypted store cannot be built, the app falls back to an in-memory store. `allowBackup` is false. So the data at rest is protected; the running app is not.

**Planned or ruled out?** Neither. The nearest existing protections are narrow: the approve button on a notification requires an unlock, and the assistant refuses to run on the lock screen. In-app approval has no check. Existing entry points the question did not list: Tsukaima can be the device assistant (long-press home), and it is a share target.

**The three gaps a daily user would hit first** (not in items 1–14 or 16–25, not in the "Still open" lists; each verified in code):

1. **The "finished" notification says nothing, and cannot be answered.** `ApprovalNotifier.forTaskDone` (l.211–216) posts the session title and the fixed text "Hermes finished the task". There is no line of the reply and no inline reply action (no `RemoteInput` anywhere in the app). The user must unlock and open the chat to learn whether the answer was "done" or a question. The same holds for clarify notifications, which have no buttons by ruling (v0.2.0). Effort S for a reply preview behind the existing private visibility; M for inline reply.

2. **The chat list is the newest 100 by creation date, with no paging.** `HermesApi.drawerSessions()` sends `limit=100` and never `offset` or `order=recent` (the server supports both; sessions.py l.179–180). A long-lived chat that the user returns to every day sinks as newer ones are created and finally leaves the list; going by the question's own counts (1060 sessions, 870 of them cron), about 90 of 190 chats are already out of reach except through search. Effort S: add `order=recent` and load more on scroll.

3. **No sign that another chat finished or is waiting while the app is open.** `NotificationCoordinator.onEvent` returns nothing for `message.complete` when the app is in the foreground (l.128), and drawer rows show only a title and an age: no running mark, no unread mark (`SessionsDrawer.kt` l.132–138; `SessionDto` has no `is_active` or unread field although the server sends `is_active` and accepts `unread`). A user who starts a long task in one chat and works in another must keep opening the first to check. Effort S–M. This overlaps item 8's `session.active_list` for "running", but the unread half is separate.

Runner-up: the share target takes text, images and PDF only (`AndroidManifest.xml` l.67–79), while the in-app picker takes any file. Sharing a spreadsheet, a zip or a recording to Tsukaima from another app is not offered. Effort S.

---

## Addendum: defects (16 to 18) and tweaks (19 to 25)

### 16. Thinking, tool and other cards still jump around
- **Status**: fixed in 0.10.2. Lead 3 is the main cause and lead 1 a second one; lead 2 was not reproduced and is unchanged.
- **Evidence**: recorded turn `app/src/test/resources/chat/recorded-tool-turn.jsonl` (39 frames, three tool calls, from the test Hermes 0.22.2 on 2026-11-10) with the same session's history mid-turn and after it in `recorded-tool-turn-history.json`. Tests: `RecordedTurnTest.rowsNeverMoveWhileTheTurnStreams`, `aRebuildFromHistoryKeepsEveryRowsId`, `aMidTurnRebuildKeepsTheIdsItHolds`, `aRebuildKeepsNoticesInPlace`, `aReusedToolIdInALaterTurnIsANewRow`, `aRequestCardReplacesTheEmptyBubble`, `historyIdsAreUniqueWithoutWireIds`. Code: `ChatReducer.withoutEmptyBubble`, `toolRow`, `fromHistory`, `rebuild`.
- **Blocked on Hermes?**: no.

**Which lead is it?**

- **Lead 3, confirmed, and it happens on every tool turn of a model that streams no reasoning** (DeepSeek, Kimi; the production default is one). `message.start` opens an empty streaming reply. A reply with no text draws a blinking caret line (`MarkdownText`, the `streaming && !caretInline` branch). The tool rows are appended below it, so the caret sits above them for the whole tool phase. The first `message.delta` after the tools finds that bubble blocked by the tool rows, drops it (`finalizeStreaming`) and opens a new one at the end. Every tool row moves up by one line at that moment. In the recording this is frame 24: the test failed there before the fix with `[local-1, a-2, call_dd3d…, call_a6e0…]` becoming `[local-1, call_dd3d…, call_a6e0…, call_073f…]`. The 0.9.3 fix covered only the case where the bubble had reasoning in it.
  Fix: the empty bubble is dropped when the row that would block it arrives (a tool row, or an approval, question, masked prompt or connection card). It is the last row at that moment, so the new row takes its place and nothing above moves.
- **Lead 1, confirmed.** A rebuild from history (resume with a new live id, a replay with holes, edit, regenerate) gave every row a new key. The recording also showed something the lead did not say: history tool rows carry **no `row_id`**, so their id was `h-<position in the list>`. A position is not an identity: the same key named different rows in two history reads of one chat (mid-turn and after it), so an open row's state could move to another row.
  Fix: a tool row is keyed by its tool-call id on both paths (live `tool_id` equals the history `tool_call_id`; verified in the recording). A user or assistant message keeps the id it had on screen, matched by persisted row id (`ChatReducer.rebuild`). A row with no wire id gets `h-i<index>`, which cannot equal a row id. Server notices, pending side notes and the todo card stay where they were instead of moving to the end. Open tool rows and unfolded reasoning now survive a rebuild (this changes the answer to item 22).
  One limit remains: the reasoning-only rows between tool calls have no row id on the live path (`persisted_turn.final_assistant_row_id` names only the last reply), so those rows still get a new key on a rebuild. `persisted_turn.row_ids` lists every row of the turn in order, so the app could bind them without a Hermes change. Not done in 0.10.2.
- **Found on the way**: a provider that reuses tool-call ids across turns (`terminal:0` each turn; the protocol README's own fixture has this id) made the later call overwrite the earlier turn's row far above, and no row appeared at the bottom. A call whose id belongs to a finished row of an earlier turn now gets a row of its own (`terminal:0#2`); history numbers duplicates the same way.
- **Lead 2, not confirmed.** `scrollToItem(items.size)` runs only when the item count or the tail key changes, not when a row above changes height, and `animateContentSize` animates only a row the user tapped or a tool row gaining its image or risk note. I could not build a reducer or JVM test for it and have no device, so I left it alone. If rows still move after 0.10.2, the remaining candidates are this one and the reasoning-row keys above; a screen recording would settle which.

**The assertion you asked for** is `rowsNeverMoveWhileTheTurnStreams`: after each of the 39 frames, every row above the last one has the same id at the same index; only the last row may be replaced. It was red before the fix and is green now. Not measured on a device: all of this is from the reducer and the recording.

### 17. Tool output is empty when a row is opened
- **Status**: fixed in 0.10.2. Both causes confirmed.
- **Evidence**: `domain/chat/ToolOutput.kt` (`ToolOutputTest`, 11 cases); `ChatReducer.onToolComplete`, `fromHistory`, `onToolOutputs`; `HermesApi.toolResults` (`HermesApiTest.toolResultsReadsTheStoredToolRows`); `ChatViewModel.loadToolOutputs` (`ChatViewModelTest.openingAHistoryToolRowLoadsTheStoredOutputsOnce`); `RecordedTurnTest.everyToolOfTheTurnHasItsOutput`, `storedMessagesFillHistoryToolOutputs`.
- **Blocked on Hermes?**: no.

- **Live: confirmed.** `onToolComplete` read only `result.output`. In the recording `read_file` answered `{"content":"1|claude","total_lines":1,…}` and its row read "No output". A second case the question did not list: when the tool's result is not JSON, `tool.complete.result` is a bare string (`tool_progress._on_tool_complete`, the `except` branch), and the app dropped it because it only accepted an object.
- **Reopened chat: confirmed.** `fromHistory` set `output = null` for every tool row.

**What the row shows now** (`ToolOutput.text`): both of your options, in order. First the result's main text field: the first of `output`, `content`, `stdout`, `result`, `text`, `summary`, `message` that is a non-blank string, with `stderr` and a string `error` under it. If there is no text, an `error` alone. Otherwise the whole result pretty-printed (for example `web_search`, whose result is data only). A bare string is shown as it is; a string that holds JSON is read as that JSON. Text over 20,000 characters is cut with a note. I chose key order over a per-tool table because the fallback keeps an unknown tool readable; a per-tool table goes stale when Hermes adds a tool.

**Does `session.history` carry the tool content?** No. `tui_gateway/session_history.py` `_history_to_messages` forwards a tool row's `content` only for `write_file`, `patch` and `skill_manage`; the comment there says other outputs "remain omitted from this compact display projection". `session.resume` uses the same projection. The stored rows are available through REST: `GET /api/sessions/{id}/messages` returns each tool message with its `content` and `tool_call_id` (verified live on the test server; the three results in the fixture's `rest` list come from it). The app now marks history tool rows as waiting, and the first time one is opened it reads that route once (pages of 500, oldest first, `inline_images=false`) and fills every tool row of the chat by tool-call id. The row reads "Loading output…" meanwhile; a failed load toasts and is retried the next time a row is opened. Limit: 10 pages, so tool rows past the first 5,000 stored messages of one chat stay at "No output".

A smaller upstream change would make the extra request unnecessary: a `session.history` parameter that includes tool `content`. Not needed for the fix.

### 18. The user cannot copy part of a reply
- **Status**: fixed in 0.10.2 for replies, reasoning, user messages and tool output. Not done: selection directly in the transcript.
- **Evidence**: `ui/chat/components/SelectTextView.kt` (`SelectTextTest`); `ChatScreen.messageItems` ("Select text"; `ChatViewModelTest.messageMenuOffersEditAndRegenerateOnlyWhenTheyCanRewind`, `selectTextOpensTheMessageAndClosesTheMenu`); `ToolRow.kt` (the open row's arguments and output inside a `SelectionContainer`).
- **Blocked on Hermes?**: no.

**How the two gestures coexist**: as you suggested. In the transcript a long press still opens the message menu, which now has "Select text" under "Copy text". It opens the message on its own page, where the whole message is inside one `SelectionContainer`: the selection runs across paragraphs, list items, tables and code, and for a reply from its reasoning into its text. A long press on that page starts a selection; Back closes it. A reasoning-only row (the thinking before a tool call) opens the same way. Tool rows had no long-press action, so their output and arguments are selectable in place once the row is open; the header keeps the tap.

Not verified on a device: the selection handles and toolbar are the platform's and no JVM test exercises them. One thing to look at on the phone is a code block inside the select page, which has its own horizontal scroll.

### 19. Jump to the latest message

- **Status**: missing
- **Evidence**: `ui/chat/ChatScreen.kt` `Transcript` (407–433). `follow` (416) is set from the scroll state (417–420)
  and drives `listState.scrollToItem(items.size)` (421–424). The transcript `Box` (372–382) holds only the list:
  no button, no badge.
- **If missing**: after scrolling up in a long chat the user must drag back to the bottom by hand, and gets no
  sign that a reply, an approval card or a question arrived below. Effort S.
- **Blocked on Hermes?**: no.

Confirmed on both points. Following stops on a manual scroll up and starts again only when the user scrolls back to
the very end (`follow = !canScrollForward`, evaluated only while a scroll is in progress). There is no "jump to
latest" control and no new-content indicator anywhere in the chat column. The status line still changes ("Waiting
for your approval"), which is the only hint that something below needs attention.

### 20. Time and duration

- **Status**: missing
- **Evidence**: no time is drawn by `UserBubble`, `AssistantMessage`, `ToolRow` or `SubagentRow`. `durationS` is set in
  `ChatReducer.onToolComplete` (`domain/chat/ChatReducer.kt:830`, from `duration_s`) and declared at
  `domain/chat/ChatState.kt:55`; its only other use in `ui/` is a preview sample (`ToolRow.kt:273`). `exitCode` is
  likewise stored and never shown. `TranscriptMessage.timestamp` is parsed (`data/gateway/GatewayModels.kt:18`) and
  dropped by `ChatReducer.fromHistory` (74–99). `ChatItem.UserMessage` and `AssistantMessage` have no time field
  (`ChatState.kt:27–45`).
- **If missing**: the user cannot tell when a message was sent, how long a turn took or how long a tool ran; in a
  chat resumed days later nothing separates yesterday from today. Effort: tool duration S (the value is already in
  state for live rows), message times M (a field on the items, a display rule, day separators), turn duration S
  to M (measured on the phone).
- **Blocked on Hermes?**: no for history message times (`timestamp` is already delivered per row), no for live tool
  duration (`tool.complete.duration_s`). Partly for the rest: history tool rows carry no duration (`fromHistory`
  has nothing to read), and I did not check whether live events carry a server timestamp (the app would stamp
  arrival time itself). A turn duration that survives a reload would need a field on `message.complete` or on the
  history row.

All three sub-questions: no. Sent time: not shown, though history rows deliver it. Turn duration: not measured or
shown. Tool duration: `durationS` is stored for tools completed in this session and never displayed; for tool rows
loaded from history it is null. The only ages on screen are the drawer's per-session age label
(`SessionsDrawer.kt`, `ageLabel`) and the Status screen's "N MIN AGO".

### 21. Code blocks

- **Status**: partial
- **Evidence**: `ui/chat/components/CodeBlock.kt` `CodeBlock` (47–120): `softWrap = false` (111) with
  `horizontalScroll` (114); header row with the label and Copy (70–105) is the first child of the column; no
  highlighter; `readOnly` selection (118) is used only by the Logs screen (`ui/logs/LogsScreen.kt:135`).
- **If missing**: no wrap option, so wide code needs sideways scrolling per block; code is one colour; in a block
  taller than the screen the user must scroll back up to its first line to copy it. Effort: wrap toggle S, sticky
  or duplicated Copy S, highlighting M.
- **Blocked on Hermes?**: no.

Long lines scroll sideways; they never wrap. There is no choice (no per-block toggle, no setting). There is no
syntax highlighting; the fence's language is shown only as an uppercase label. Copy sits in the block's header at
the top and scrolls away with it: it is not sticky and not repeated at the bottom, so it is not reachable from the
end of a long block. Code in a reply is not selectable in 0.10.1 (item 18 territory); Copy takes the whole block.

### 22. Reasoning rows

- **Status**: partial. The fold exists and is per message; the default is the opposite of what the question
  assumes; no hide setting. The always-open default is on purpose: CHANGELOG 0.5.3 ("Reasoning blocks no longer
  fold on their own … History still loads with reasoning closed").
- **Evidence**: `ChatReducer.reasoningOpen` (`domain/chat/ChatReducer.kt:949–950`): the user's choice, else
  `item.reasoningLive`. `reasoningLive = true` is set on every `reasoning.delta` (272) and never cleared.
  `toggleReasoning` (953–956). `ChatState.reasoningOpen` map keyed by item id (`ChatState.kt:310`). History items are
  built with `reasoningLive = false` and id `h-<rowId>` (`ChatReducer.kt:76–82`). Header and body:
  `AssistantMessage.kt:62–76`. Settings inventory: `data/prefs/SettingsStore.kt` (`Settings`, 31–61) has no
  reasoning field; `ui/settings/SettingsScreen.kt` has no such row.
- **If missing**: during a long live session every reasoning block stays open, including the reasoning-only rows
  above each tool call, until the user folds each by hand or the chat is reloaded. Effort: fold when the reply
  starts S (it reverses the 0.5.3 decision, so it should be a setting); "hide reasoning" setting S; persisting fold
  state across app restarts M.
- **Blocked on Hermes?**: no.

Folded by default once the reply starts: no. Reasoning streamed in this session shows open and stays open after
the reply starts and after the turn ends; it never closes by itself. Only reasoning loaded from history starts
folded. Fold state across a reload, verified: the choice lives in the in-memory `ChatState`, keyed by item id.
`reloadHistory` (`ui/chat/ChatViewModel.kt:492–501`) and `bindResumed` (`ui/chat/ChatReconnector.kt:145–153`) keep the
map but replace the items, so (a) a block streamed live gets a new id (`h-<rowId>`) and comes back folded, which
is the practical "reset" on every reconnect, edit or regenerate; (b) a history block the user opened keeps its id
and stays open across a reload inside the same open chat (inferred from the id scheme, no test). Leaving the chat
or killing the app drops everything: nothing is written to disk. Row ids are being changed under items 16–18, so
(a) and (b) may shift. Setting to hide reasoning: none. The nearest control is the overflow menu's "Reasoning"
effort sheet (`ChatScreen.kt:525`, `:562`), which changes what the model produces for the session, not what the
app shows. Reasoning text is plain text, not markdown.

### 23. Tool rows in bulk

- **Status**: missing (grouping); partial (copying)
- **Evidence**: `ChatScreen.kt:430` (one lazy item per `ChatItem`) and `:465–466` (`ToolCall -> ToolRow`,
  `Subagent -> SubagentRow`): no grouping layer. `ui/chat/components/ToolRow.kt` `ToolRow` (67–156): a tap toggles
  `open` (94); no long-press, no copy action; args (134–143) and output (144–151) are plain `Text`. Long-press menu
  `messageItems` (`ChatScreen.kt:572–584`) resolves only user and assistant messages.
- **If missing**: a 30-tool turn is 30 rows of at least 44dp plus margins, about four screens, between the
  question and the answer. A command or its output cannot be copied from the phone at all; the user retypes it or
  asks the agent to repeat it. Effort: grouping M (it changes item count and keys, so it should follow the
  16–18 fix); copy command and copy output S each.
- **Blocked on Hermes?**: no for grouping and for copying a command (name, `context` and `args` are in state).
  Copying output depends on output being present, which is item 17: in 0.10.1 history rows always have
  `output = null` (`ChatReducer.kt:91`) and live rows only for tools that return `output`.

Grouping: none. Every tool call and every subagent is its own row, in arrival order, each collapsed to one line
(icon, name, `· context`, status glyph, chevron). There is no "12 tools, 1 failed" summary and no expand-all. A
failed tool is visible only as a small red icon on its own row. Copying on its own: not possible in 0.10.1. The
row has no copy action for the command, the arguments or the output; none of that text is selectable; and the
message long-press menu does not open on tool rows. "Copy text" on the reply copies the reply only. The one
related thing that exists is "Copy reasoning" on a reply.

### 24. Feedback while waiting

- **Status**: partial
- **Evidence**: `ui/chat/components/StatusLine.kt` `StatusLine` (84–112) with the pulsing `StatusDot` (55–77);
  `domain/chat/AgentStatus.kt` (labels 12–78; `Compacting` 64–68); `ChatUiState.status`
  (`ui/chat/ChatUiState.kt:75–76`); `domain/chat/ThinkingFlair.kt` `next` (53–64, `HOLD_MS = 2600`);
  `ChatReducer.onStatusUpdate` (`domain/chat/ChatReducer.kt:361–378`); composer Stop button
  (`ui/chat/components/Composer.kt:193–197`); streaming caret (`MarkdownText.kt:291–331`).
- **If missing**: there is no elapsed time anywhere and nothing that distinguishes "working" from "connected but
  stalled". During a two-minute tool the screen is static apart from a pulsing 7dp dot. Effort: elapsed timer in
  the status line S; a "no events for N s" hint S to M.
- **Blocked on Hermes?**: no for an elapsed timer (the phone can time from the last status change). Yes for real
  progress inside a long tool: no event carries it (item 3), and for a slow model Hermes sends
  nothing between events that the app could show.

What tells the user the turn is alive: (1) the status line under the top bar, with a pulsing accent dot and a
label: a rotating flair line while thinking, "Running <tool>" from `tool.generating`/`tool.start`, "Replying…"
while text streams, "Compacting context…" during compaction; amber "Waiting for your approval / answer / a
password / a connection" when it is the user's turn; red "Can't reach Hermes" or "Gateway stopped". (2) The
composer's send button is a red Stop while the turn runs. (3) A blinking caret at the end of a streaming reply.
(4) A running tool row has an accent icon and no status glyph; a subagent row shows its latest progress line when
opened. Elapsed time: none, in any of these. A weakness, verified in code: the flair line is chosen when an event
is reduced (`flair()` is called from the reducer) and there is no timer in the view model, so with no events
arriving the text does not rotate; only the dot's pulse animation keeps moving, and it would keep pulsing on a
turn that has stalled server-side. Overlap with item 1: the question's premise there is out of date. In 0.10.1
`status.update` is handled: `compacting`/`compressing` set the status line to "Compacting context…" and the
completion line becomes a quiet transcript notice; `warn`, and `lifecycle` lines starting with the warning sign
(the model fallback), become a warning notice line (`NoticeLine.kt`); `ready` ends a compaction; goal, loop,
heartbeat, process and plain lifecycle lines are dropped (CHANGELOG 0.10.1).

### 25. Small things

- **Status**: missing, all six (each claim confirmed, one with a nuance)
- **Evidence**: per claim below.
- **If missing**: per claim below. Effort S each, except per-chat drafts and read-aloud (M).
- **Blocked on Hermes?**: no, for all six.

1. **No pull-to-refresh on the drawer or the status screen**: confirmed. No `pullToRefresh` / `PullToRefreshBox`
   anywhere in `app/src/main/kotlin`. Both refresh by themselves instead: the drawer on open and on the gateway's
   `sessions.changed` while visible (`ui/shell/AppShell.kt:138–144`, `ui/drawer/DrawerViewModel.kt:147`, `refresh`
   157); the status screen on entry, on `platforms.changed` while resumed, and through a Retry button shown in
   the offline state (`ui/status/StatusViewModel.kt:182–199`, `ui/status/StatusScreen.kt:254`). The user loses a way
   to force a reload when the live event is missed.
2. **Haptics in only one place**: confirmed, exactly one call. `ChatScreen.kt:317`:
   `performHapticFeedback(HapticFeedbackType.Confirm)` when voice mode enters Listening. Nothing on send, on
   long-press menus, on approve or deny, or on copy. (Completeness-pass line 81 notes this one haptic can fire
   again after a rotation.)
3. **No text-size setting apart from the system font scale**: confirmed. `Settings`
   (`data/prefs/SettingsStore.kt:31–61`) has theme and Material You only; no `LocalDensity` or font-scale override
   exists in the app. All text sizes are `sp` (`ui/theme/Type.kt:52–73`, body 15.5sp), so the system font scale does
   apply (inferred from the use of `sp`, not checked on a device).
4. **No swipe actions on drawer rows**: confirmed. Rows are `combinedClickable` with tap and long-press only
   (`ui/drawer/ConversationItem.kt:61–65`); long-press opens a sheet with Rename, Archive or Unarchive, Delete
   (`ui/drawer/SessionsDrawer.kt:183–200`). No `SwipeToDismiss` or `anchoredDraggable` in the app. Archive and
   delete are reachable, two taps deep.
5. **No draft kept per chat when switching chats**: confirmed. The composer text is `ChatUiState.input`, held only in
   the chat's `ChatViewModel` (`ui/chat/ChatViewModel.kt:351`). Opening another chat goes through `navigateTop`
   (`ui/navigation/TsukaimaNavHost.kt:224–243`), which pops the current chat entry and always creates a fresh entry
   and a fresh view model ("A chat always gets a fresh entry", 239–240), so the typed text and any picked
   attachments are gone. Nothing is persisted: no `SavedStateHandle`, no draft key in the settings store, so a
   draft is also lost on process death. Rotation and backgrounding keep it. The clearing of the view model on pop
   is inferred from navigation semantics; there is no test for it.
6. **No "read this reply aloud" outside voice mode**: confirmed. The message long-press menu offers Copy text, Copy
   reasoning, Edit, Regenerate (`ChatScreen.kt:572–584`). Speech output is wired only to the voice controllers
   (`domain/voice/VoiceController.kt`, `voice/AndroidSpeechOutput.kt`, `voice/ServerSpeechOutput.kt`) and the assistant
   sheet. Rulings v0.6.0 (build-rulings line 229: "voice starts only once the chat has opened … so nothing old is
   read aloud") fix that voice mode does not read earlier replies; no ruling excludes a per-message read-aloud
   action, so this is a gap, not a decision. The pieces to reuse exist (`SpeechText.plain`, the sentence pipeline).
