# Tsukaima ↔ Hermes — gaps found in session testing (2026-10-21)

Scope: a stock Hermes 0.22.2 gateway (`tui_gateway` backend) exercised by the Tsukaima client.
No Hermes core changes are required for any fix below; all contracts named here are stock.

## BLOCKING

### 1. `connection.request` is not implemented → connector tools hang ~5 minutes
- Symptom: turn appears frozen showing only the tool call; nothing asks the user anything.
- Evidence: `connection op a442c3e92e99: linear pending -> initiated by backend_watcher`
  02:15:40.405 → no client answer → settled only when the user pressed stop at 02:16:02.298
  (`settled by interrupt: linear=not_connected`). `deadline_at` was 02:20:38 (~5 min).
- Cause: the client advertises `client.capabilities {server_requests: true}` (proven by clarify
  working), so the backend assumes a card exists and waits instead of returning a `connect_url`.
- Inbound: server→client `connection.request` (emitted from `tui_gateway/agent_callbacks.py:179`
  off the operation in `tools/connectors/run.py`). Payload: `op_id`, `seq`, `deadline_at`,
  `targets[{name, kind, action, state}]`. Progress arrives as `connection.update`.
- Outbound: RPC `connection.respond`
  ```json
  { "op_id": "<op_id>",
    "result": { "targets": [ { "name": "linear", "status": "approved|skipped",
                               "detail": null, "env": null } ],
                "settled_by": "continue|null", "said": null } }
  ```
  Per-target `status` is only `approved` or `skipped` (`ConnectionAnswerStatus`).
- Reference implementation: `apps/desktop/src/store/connection-request.ts`
  (also handles `connection.update` and `connectors.operation.wake` for the browser leg).
- Stopgap until the real card exists: on `connection.request`, immediately respond with every
  target `skipped` and no `settled_by`. The tool then settles in ~1 s as `skipped` instead of
  hanging the turn for 5 minutes.

### 2. `todo.updated` is ignored → checklists never render
- Symptom: `todo_list` produces no visible output at all.
- Cause: todo state is not read out of the tool result. It is a dedicated event.
- Contract: event `todo.updated` → `{ todos: [{id, content, status, parent?}], revision }`.
  `status ∈ pending | in_progress | completed | cancelled`. Fires on every todo call regardless of
  tool-progress settings. The same snapshot is mirrored on `tool.complete` as `todos` + `revision`.
- The `summary` rollup (`total/pending/in_progress/completed/cancelled`) exists ONLY in
  `tool.complete.result.summary`; it is not part of `todo.updated`.
- Reconcile by `revision` (monotonic).

## RENDERING (minor, cosmetic)

### 3. LaTeX shown raw
`$…$` / `$$…$$` pass through untouched. Renderer needs KaTeX or MathJax, or the model must stop
emitting math — the agreed choice is to render.

### 4. Task-list checkboxes not rendered
`- [x] item` / `- [ ] item` parse as a plain list. Needs the GFM `tasklist` extension.

### 5. Backslash escapes shown literally
`\*not italic\*` renders with the backslashes visible. Backslash escapes are core CommonMark;
escapes are being skipped or consumed by a regex pre-pass before parsing.

### 6. `vision_analyze` image is never delivered to the client
- Hermes collapses the multimodal tool result to text and the payload literally ends with
  `[screenshot]` (verified in the stored transcript: 203 chars ending in that marker). There is no
  base64 image in the tool result — nothing to expand into.
- Fix without core changes: on `tool.start`/`tool.complete` for `vision_analyze`, take
  `args.image_url` and fetch it with the stock route `GET /api/media?path=<path>` →
  `{"data_url": "data:image/png;base64,…"}`.
  Constraints: path must resolve under `~/.hermes/{images,screenshots,cache}`; suffix must be one of
  png/jpg/jpeg/gif/webp/svg/bmp/ico; 25 MiB cap (413 above it; 415 unsupported type; 403 outside roots).
  If `args.image_url` is already `http(s)`, render it directly.

## BEHAVIOUR / UX

### 7. No message queueing while a turn runs
The composer blocks input during a turn, so Hermes' server-side queue (`_pending_messages` in the
platform base adapter, drained after the turn) is never exercised. Not a bug, but a decision to make:
either allow `prompt.submit` while running (Hermes queues it, no core change) or keep blocking and
guarantee the draft survives.

### 8. Interrupted-tool state is indistinguishable from a plain error
Two distinct turn-end reasons exist and should map to different UI:
- `reason=interrupted_during_api_call` — stop while streaming the model.
- `reason=interrupted_by_user` — stop while a tool ran.
A tool killed mid-run returns a real result: `exit_code: 130` (SIGINT) with
`[Command interrupted]` appended to the partial output, and `error: null`. Partial output should be
shown with an interrupted marker, not a generic failure.
Hermes also flags orphan recovery (`interrupted side-effecting tool may have executed; its effect is
UNKNOWN`). For mutating tools that warning should be surfaced — "may have partially run", never
"cancelled cleanly".

### 9. Failure predicate — currently correct, keep it that way
Verified good: all three failure shapes render red; successes including an empty 0-byte file render
as a checkmark. Keep the union rule rather than any single field:
failed ⇔ `result.error` truthy **or** `result.success === false` **or** `result.exit_code` non-zero.
Shapes seen:
```
terminal:      {"output": "...", "exit_code": 2, "error": null}      ← error null; exit_code is the only signal
read_file:     {"content": "", ..., "error": "File not found: ...", "not_found": true}
text_to_speech:{"error": "Text is required", "success": false}
empty file:    {"content": "", "total_lines": 0, ..., "not_found": false}   ← success
```

## VERIFIED WORKING — do not regress

- **clarify**: one card with single-select + multi-select + free-text; answers returned verbatim;
  free-text newline preserved.
- **Session resume**: an open server→client request is replayed on relaunch and stays answerable
  (`session.resume` / `session.events.since`). Restarting mid-request survived intact.
- **Approval card** (needs `approvals.mode: manual`; `smart` auto-decides and often shows nothing):
  blocks, approves, and the tool runs. Payload carries `status: "pending_approval"`, `command`,
  `description`, `pattern_key`, `smart_denied`, `allow_permanent`. Cards can arrive at any time, so
  the UI must accept one mid-turn.
- **Media delivery**: single image; three attachments in one message (PDF 375 KB, ZIP 467 B, PNG);
  voice bubble from `MEDIA:` ogg/opus. All rendered.
- **TTS/STT**: OpenRouter command providers — ElevenLabs `eleven-v4-turbo` (Polish voice), STT
  `microsoft/mai-transcribe-2`; round-trip word-perfect; both pin `provider: {"zdr": true}`.
- **`POST /api/audio/transcribe`**: base64 data URL → transcript. Empty/silent audio returns
  `{"ok": true, "transcript": ""}` (success, so a VAD loop re-listens) — not an error. 25 MiB cap,
  MIME must start with `audio/` (bare `video/webm` also accepted), non-mp3/wav transcoded server-side.
- **Long tool output**: truncated head+tail with a notice naming the full-output path.

## RETRACTED

An earlier theory that the client was sending a stray interrupt after each `prompt.submit` was wrong.
Every interrupt in the logs corresponds to a deliberate stop pressed by the user. There is no
ghost-cancel bug to chase.
