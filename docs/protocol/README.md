# Hermes dashboard protocol — verified notes

Everything here was verified on 2026-10-20 against Hermes Agent **0.22.2** (commit d923a5eb), both
the user's real instance (`http://hermes:9119`) and the local test instance
(`http://100.69.91.116:9119`, creds in `.local/hermes-test-creds.env`). Both use the `basic`
auth provider. The full JSON-RPC contract is `gateway-contract.openrpc.json` next to this file
(copied from `apps/shared/src/` in the Hermes checkout). Source of truth in the Hermes checkout
(`~/.hermes/hermes-agent` on the claude VM): `hermes_cli/dashboard_auth/`, `hermes_cli/web_routers/`,
`hermes_cli/web_models.py`, `tui_gateway/`.

## 1. HTTP auth

- `GET /api/health` → `{"ok":true,"version":"0.22.2","auth_required":true}` — **public**.
- `GET /api/status` — **public** (no auth needed, even when gated).
- `GET /api/auth/providers` → `{"providers":[{"name":"basic","display_name":"Username & Password","supports_password":true}]}` — public.
- Everything else under `/api/` → `401` without credentials.
- Login: `POST /auth/password-login` with JSON
  `{"provider":"basic","username":"…","password":"…"}` →
  `200 {"ok":true,"next":"/"}` and `Set-Cookie: hermes_session_at=<token>` (+ `hermes_session_rt`).
  Errors: `401` bad credentials, `404` unknown provider, `429` rate limited (per IP), `503`.
- The `hermes_session_at` cookie value is a bearer token: send `Authorization: Bearer <token>` on
  every gated request **and** it is accepted on every route (RFC 8252 native path in
  `dashboard_auth/middleware.py`). Basic provider tokens are HMAC-signed, TTL **12 h**
  (`HERMES_DASHBOARD_BASIC_AUTH_TTL_SECONDS`). On `401` the app re-runs the password login (single-flight) —
  but only over `https://` or to a trusted `http://` host (loopback, RFC 1918, Tailscale 100.64/10, `*.ts.net`,
  single-label names; `TrustedHost`). Any other `http://` host gets an auth error and a manual sign-in instead, so
  the stored password is never replayed in clear text to an untrusted network.
- `GET /api/auth/me` → `{"user_id":"tsukaima","provider":"basic","expires_at":<unix>}` (verifies a token).
- `POST /api/auth/ws-ticket` (bearer) → `{"ticket":"<43 chars>","ttl_seconds":30}` — single use.

## 2. WebSocket chat (`/api/ws`)

URL: `ws(s)://host:port/api/ws?ticket=<ticket>` (ticket in the **query string**; the SPA does the
same — see `web/src/lib/gatewayClient.ts`). Rejected upgrades close immediately.

Wire: **JSON-RPC 2.0**, one JSON object per text frame (newline-delimited allowed; split on `\n`).
Three frame kinds:

1. Client → server request: `{"jsonrpc":"2.0","id":"r1","method":"session.create","params":{…}}`
   → reply `{"jsonrpc":"2.0","id":"r1","result":{…}}` or `{"id":"r1","error":{"code":…,"message":…}}`.
   Unknown param keys are rejected with code `4000`.
2. Server → client **event** notification: `{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"d4fb0dea","seq":12,"payload":{…}}}`.
   `seq` is per session and monotonic; `session_id` may be `""` for global events.
3. Server → client **request** (agent asking the user): `{"jsonrpc":"2.0","id":"srq-<12hex>","method":"approval","params":{…}}`.
   The client answers with `{"jsonrpc":"2.0","id":"srq-…","result":{…}}`. The client must announce
   it answers these once per connection: `client.capabilities {"server_requests":true}`.
   A withdrawn request arrives as event `request.cancel {id, method, reason}`.

First frame after accept is event `gateway.ready` (payload has `replay_epoch`, `skin`, …).
Heartbeat: send `gateway.ping` every **15 s**; treat **45 s** with no inbound frame as dead and reconnect.

### Methods the app uses (params → result; `*` = required)

Every params object also accepts `profile` (the Hermes profile to act in); the app adds it to every RPC while a
profile other than `default` is active.

| Method | Params | Result |
|---|---|---|
| `client.capabilities` | `server_requests:bool` | `server_requests:[…]` |
| `gateway.ping` | – | `{}` |
| `session.create` | `source`, `cwd`, `model`, `provider`, `close_on_disconnect:bool` | `session_id*` (live id, 8 hex), `stored_session_id*` (`YYYYMMDD_HHMMSS_xxxxxx`), `messages*`, `info*` |
| `session.resume` | `session_id*` (stored id), `source`, `lazy`, `defer_history` | same + `resumed`, `running`, `status`, `pending_approval`, `open_requests`, `inflight` (see below) |
| `session.history` | `session_id*` | `count*`, `messages*` (TranscriptMessage[]) |
| `session.list` | `limit`, `include_hidden` | `sessions*` (`id`, `title`, `preview`, `started_at`, `message_count`, `source`) |
| `session.most_recent` | – | `session_id` (nullable) |
| `prompt.submit` | `session_id*`, `text*` | `status` (`"streaming"`), `user_row_id` |
| `session.interrupt` | `session_id*` | `status` (`interrupted`/`not_interrupted`); the turn then ends with `message.complete {text:"", status:"interrupted"}`. Verified: an idle session also answers `interrupted`; `not_interrupted` is only a hosted-task mismatch. |
| `session.cwd.set` | `session_id*`, `cwd*` | session info |
| `session.title` | `session_id*`, `title` | – |
| `session.archive` | `session_id`, `archived:bool` | `archived*` |
| `session.delete` | `session_id*` | `deleted*` |
| `session.close` | `session_id*` | `closed*` |
| `model.options` | `session_id` | `providers*` (`slug`, `name`, `models[]`, `is_current`, `authenticated`), `model`, `provider` |
| `session.events.since` | `session_id*`, `last_seen` | `events*`, `latest_seq*`, `truncated*`, `open_requests*` (events with `seq > last_seen`; the app starts its watermark at -1, seq numbering starts at 1) |
| `file.attach` | `session_id*`, `data_url*`, `name` | `ref_text` (`@file:…`, goes into the prompt), `path`, `attached` |
| `image.attach_bytes` | `session_id*`, `content_base64*`, `filename` | `attached`, `path`, `name`, `width`, `height` (queued for the next turn) |
| `image.detach` | `session_id*`, `path*` | – |
| `approval.respond` | `session_id*`, `choice`, `request_id` | – (RPC fallback for a resume's `pending_approval`, which has no `srq-…` id) |
| `config.set` | `session_id`, `key*`, `value*`, `scope`, `confirm_expensive_model` | `key`, `value` (+ `warning`, `confirm_required`, `confirm_message`, `scope`, `deferred` for `model`); see §4 |
| `session.usage` | `session_id*` | the `usage` object (see §4) |
| `session.compress` | `session_id*`, `focus_topic` | `status`, `summary{headline,…}`, `info`, `usage`, `messages`, … (see §4) |

Note: the REST `/api/sessions` list and `session.list` return the **stored** id. Live sessions have a
separate short `session_id`; events carry the **live** id. Keep both.

### Events (payload)

Observed in a real turn, in order:
`session.info {model, provider, approval_mode, tools, cwd, running,…}` → `message.start {}` →
`session.title {session_id(stored), title}` → `thinking.delta {text}` (status flair, may be empty) →
`reasoning.delta {text}` (model reasoning) → `tool.generating {name}` →
`tool.start {tool_id, name, context, args}` → `tool.complete {tool_id, name, args, duration_s, result:{output, exit_code, error}}` →
`session.usage {usage}` → `message.delta {text}` (visible reply chunks) → `reasoning.available {text}` →
`message.complete {text, usage}` → `session.info` → `sessions.changed {}` (global, `session_id:""`).
v0.5.0 also handles `session.usage`, `session.info` (`usage`, `reasoning_effort`), `subagent.*`, `tool.output_risk`,
`session.resume_progress`, `notification.show`, `sessions.changed` and `platforms.changed` (shapes in §4).

Also: `turn.error {message,…}`, `message.interim {text, already_streamed}` (commentary beside tool calls; when
`already_streamed` is false the text never came as `message.delta`, so the app shows it as its own completed
reply; when true it is ignored), `approval.cancelled`,
`request.cancel {id, method, reason}`, `platforms.changed {}`, `gateway.ready {heartbeat:true}` (server-side pong).

`message.delta`, `reasoning.delta`, `thinking.delta` are coalesced server-side (~30 fps).

### Server → client requests

- `approval` params: `session_id`, `request_id`, `command`, `choices`, `allow_session`, `allow_permanent`,
  `smart_denied`, `tool_name`, … (extra keys allowed). The server always fills `choices` (`once`, then `session`
  unless smart-denied or `allow_session` is false, then `always` unless `allow_permanent` is false, then `deny`).
  Reply result: `{"choice":"once"|"session"|"always"|"deny","all":bool?}`.
  The RPC (not REST) `approval.respond {session_id*, choice, request_id}` answers one by `request_id` (stale sids,
  a resume's `pending_approval`).
- `clarify` params: `session_id`, `questions[]` (`ClarifyQuestion`). Reply `{"answers":{qid: text|null}}`
  (`null` = that question skipped), or `{}` (no `answers` key) to cancel all. `{"answers": null}` is **not** a
  cancel: the server records it as submitted-empty.
- Masked one-string prompts — reply `{"value": "<string>"}` (`ValueResult`), where `""` means skipped / declined:
  - `sudo {command}` — the terminal tool's sudo password (`command` redacted server-side; 120 s). Only asked when
    no `SUDO_PASSWORD` is configured, none is cached, and `sudo -n true` fails on the backend (NOPASSWD sudoers
    never prompt — the test instance's user has passwordless sudo, so no live `sudo` request was observed).
  - `secret {env_var, prompt, metadata?}` — a value for a named env var (skills / setup flows); a non-empty value
    is saved to the profile's `.env`.
  - `vault.unlock_prompt {backend, display_name}` — master password for an external password manager.
  - `vault.code {site?, hint?}` — a one-time / 2FA code; reply `{"value": "<code>"}`.
  - `vault.save_login {origin, site}` — a login to save; the value is a JSON *string*
    `{"identifier": …, "password": …}`.
  - Since 0.8 the app answers both from the chat like the other masked prompts (`""` declines). Never decline them
    with 4404: with the phone as the only client, Hermes settles the decline with a `{"success":false}` refusal JSON,
    which the browser tool would type in as the one-time code (`tui_gateway/server_requests.py` `_decline`,
    `server._ask`, `tools/browser_vault_tool.py`). 0.5.1–0.7 declined `save_login` with 4404 and answered the code `""`.
- `session.resume` while a turn runs (`running: true`, `status: "working"`) returns `messages` up to the running
  turn's user row only; the partial reply is `inflight {assistant, streaming, user, …}`, where `assistant` is every
  `message.delta` of the turn so far (`tui_gateway/session_history.py` `_append_inflight_delta`). Verified live
  2026-10-20: dropped the socket mid-reply, resumed within the grace window (same live id): `inflight.assistant`
  held 1396 chars of the partial reply while `messages` held only the user row; `session.events.since(0)` was
  `truncated` (the 512-event ring was mostly `reasoning.delta`s) and carried only part of the deltas.

TranscriptMessage (history): `role` (`user`|`assistant`|`tool`), `text`, `reasoning`, `name`, `context`,
`args`, `tool_call_id`, `timestamp`, `row_id`, `display_kind`.

## 3. REST endpoints the app uses

All need the bearer header unless marked public. Management endpoints accept `?profile=<name>`.

| Endpoint | Notes |
|---|---|
| `GET /api/status` | public. Keys: `version`, `gateway_running`, `gateway_state` (`running`/`stopped`/null), `gateway_platforms{}`, `active_sessions`, `active_agents`, `profiles[]`, `memory{}`, `disk{total_mb,free_mb,used_percent}`, `overall`, `auth_required`, `auth_providers[]`. |
| `GET /api/system/stats` | `hostname`, `os`, `hermes_version`, `cpu_count`, `memory{total,available,…}`, … |
| `POST /api/gateway/start` / `stop` / `restart` | `{"ok":true,"pid":…,"name":"gateway-start"}`; state flips within ~5 s. |
| `GET /api/sessions?limit=N&archived=…&exclude_sources=…` | `{"sessions":[{id,title,preview,source,started_at,ended_at,message_count,model,archived,profile,…}]}`. `limit` must be `0..100` (422 otherwise; the app clamps). `archived` = `exclude` (default) / `only` / `include`; `exclude_sources` is a comma list (the drawer sends `archived=include&exclude_sources=cron`). |
| `GET /api/sessions/search?q=&limit=20` | `{"results":[…]}`: session-id matches, then FTS over message text, then titles; rows are session rows plus `snippet` (`>>>`/`<<<` around the match), `role`, `session_id`, `lineage_root`. A blank `q` → `{"results":[]}`. |
| `GET /api/sessions/{id}` | The raw session row; `archived` is `0`/`1` (a number, not a boolean). |
| `PUT /api/tools/toolsets/{name}` | `{"enabled":bool}` → `{"ok":true,"name","platform","enabled","post_setup_started"}`; unknown name → 400, missing `enabled` → 422. |
| `PATCH /api/sessions/{id}` | `{"title":"…"}` → `{"ok":true,"title":"…"}` (also `{"archived":true}`). |
| `DELETE /api/sessions/{id}` | |
| `GET /api/model/options` | same shape as RPC `model.options`. `GET /api/model/info` → `{model, provider, effective_context_length}`. |
| `GET /api/chat/workspaces` | `{"projects":[],"repos":[],"default_cwd":"/home/claude","home":"/home/claude"}`; `?scan=true` lists repos. |
| `GET /api/cron/jobs` | array of jobs: `id`, `name`, `prompt`, `schedule{kind,expr,display}`, `schedule_display`, `enabled`, `state` (`scheduled`/`paused`/…), `next_run_at` (ISO), `last_run_at`, `last_status`, `deliver`. |
| `POST /api/cron/jobs` | `{"name","schedule":"0 9 * * *","prompt","deliver":"local"}` → job. |
| `PUT /api/cron/jobs/{id}` | `{"updates":{"name":…,"schedule":…,"prompt":…,"deliver":…}}` |
| `POST /api/cron/jobs/{id}/pause` · `/resume` · `/trigger` | `/trigger` = run now. |
| `DELETE /api/cron/jobs/{id}` | `{"ok":true}` |
| `GET /api/cron/delivery-targets` | `{"targets":[{"id":"local","name":"Local (save only)"},{"id":"bot-chat:default",…}]}` — Telegram/Discord appear here once configured. |
| `GET /api/pairing` | `{"pending":[…],"approved":[…]}` |
| `POST /api/pairing/approve` | `{"platform":"telegram","request_id":"…"}` (or `"code"`). |
| `POST /api/pairing/revoke` | `{"platform":"telegram","user_id":"…"}` |
| `GET /api/skills` | `[{name, description, category, enabled, provenance}]` |
| `PUT /api/skills/toggle` | `{"name":"…","enabled":bool}` → `{"ok":true,"name","enabled"}` |
| `GET /api/tools/toolsets` | `[{name,label,description,enabled,available,configured,tools[]}]` |
| `GET /api/profiles` | `{"profiles":[{name,is_default,model,provider,gateway_running,…}]}`; `GET /api/profiles/active` → `{"active":"default"}` |
| `GET /api/logs?file=agent|gateway|errors&lines=200&level=WARNING&search=` | `{"file","lines":[…]}` |
| `GET /api/analytics/usage?days=30` | `{"daily":[…],"by_model":[…],"totals":{…}}` |

## 4. Addendum (2026-10-20, verified live)

- **Approvals only fire when Hermes decides to ask.** `approvals.mode: smart` (the install default) lets an LLM classifier auto-approve; a live probe saw `sudo rm -rf <nonexistent>` run with no prompt. With `approvals.mode: manual` the pattern list in `tools/approval_detection.py` applies, and the server request looks like:
  `{"id":"srq-…","method":"approval","params":{"session_id":"07fd87fa","command":"rm -r /tmp/x","pattern_key":"delete in root path","pattern_keys":[…],"description":"…","allow_permanent":true,"allow_session":true,"request_id":"<32hex>","choices":["once","session","always","deny"]}}`.
  A `deny` reply makes the tool fail with `BLOCKED: Command denied by user` and the agent does not retry. No `tool_name` field.
- **Clarify** is server request `clarify {session_id, questions:[{qid, question, choices?:[string], multi_select?:bool}]}`; reply `{"answers":{qid: string|null}}`, `null` = skipped, no `answers` key = cancel all. `clarify.lock {request_id, question_id, answer}` locks one answer early (optional).
- **Attachments** go through RPC, not REST: `file.attach {session_id, data_url, name}` → `{ref_text:"@file:…", path, uploaded}`; the prompt text then includes `ref_text`. Images: `image.attach_bytes {session_id, content_base64, filename}` → `{attached, path, name, width, height, token_estimate}`, queued for the next turn; `image.detach {session_id, path}` drops one. `POST /api/chat/image-upload {data_url, filename}` is the REST equivalent for images only.
- The test instance is now pinned to `approvals.mode: manual` so approval UI can be exercised.
- **HTTPS fronts rename the cookies.** When the dashboard is reached over HTTPS (Tailscale Serve, any TLS reverse proxy) `dashboard_auth/cookies.py` names them `__Host-hermes_session_at` / `__Host-hermes_session_rt` / `__Host-hermes_session_provider` with `Secure`. Verified via `tailscale serve --https=8443 http://127.0.0.1:9119` on the claude VM: login, bearer `/api/auth/me`, ws-ticket and the `wss://…/api/ws` upgrade all work with no `dashboard.public_url` set, because the test instance binds `0.0.0.0` (`_is_accepted_host` then accepts any Host). An instance bound to a specific host needs `dashboard.public_url` set to the Serve origin or Host/WS checks fail. v0.1.x only parsed the unprefixed name, so HTTPS logins failed with "carried no hermes_session_at cookie".
- **File deliveries (`MEDIA:`).** The agent hands a file to the user by putting a whole line `MEDIA:<path>` in its reply (seen as `MEDIA: /home/…/out/file.txt`, `` MEDIA:`/path with space.pdf` ``, `MEDIA:"/p.pdf"`, `~/…` paths). The dashboard leaves it as prose; the TUI (`ui-tui/src/components/markdown.tsx` `MEDIA_LINE_RE`) and desktop (`apps/desktop/src/lib/chat-messages/parts.ts`) render cards; deliverable extensions are `MEDIA_DELIVERY_EXTS` in `gateway/platforms/base.py`. The bytes come from `GET /api/files/download?path=<absolute or ~/ path>` (bearer, or `?token=`; `hermes_cli/web_routers/files.py`): `200` streamed with `Content-Disposition: attachment; filename="name"` (`filename*=utf-8''…` for non-ASCII names), `Content-Type` from `mimetypes` (fallback `application/octet-stream`) and `Content-Length`. Errors: `401` no auth, `404` missing, `400` directory / relative path / `..`, `403` sensitive path (`.env`, credential dirs), `409` a live SQLite file, `413` over 100 MB. `HEAD` → `405`. Not profile-scoped. `GET /api/files/read?path=` returns the same file as base64 `data_url` JSON (not used for downloads). Verified live: the test agent answered a delivery request with `MEDIA: /home/claude/workspace/output/tsukaima-probe/tsukaima-media-probe.txt`, and the download returned it (94 B, `text/plain; charset=utf-8`).

### v0.5.0 additions (2026-10-20, verified live against 0.22.2)

- **Live model switch**: `config.set {session_id, key:"model", value:"<model> --provider <slug> --session"}` (the
  desktop picker's format; `tui_gateway/methods_config_set.py::_set_model`, `model_switch.py::_switch_request`). The
  value is parsed like `/model` (bare name, `vendor/model`, `--provider`, `--session` / `--global` / `--once`). Reply:
  `{"key":"model","value":"deepseek-v4.1-flash-1017","warning":"","confirm_required":false,"confirm_message":"","scope":"session"}`,
  followed by a `session.info` event. While a turn runs the pick is queued: `{…,"deferred":true}` (applied at the
  next turn start; a dropped queued pick arrives as `notification.show {key:"model_switch.dropped", level:"warn"}`).
  A model the selection guards flag answers `confirm_required:true` with `confirm_message` and switches nothing;
  re-send with `confirm_expensive_model:true`. An unknown provider → error 5001 ("Unknown provider 'nope'…"). Without
  `--session` the server may persist the pick as the profile default (`resolve_persist_behavior`); the app always
  sends `--session`. There is no `session.info` RPC (-32601); the info arrives as events and in create/resume.
- **Reasoning effort**: `config.set {session_id, key:"reasoning", value}` with no `scope` is session-scoped
  (`scope:"global"` would write `agent.reasoning_effort`). Values: `none` (off), `minimal`, `low`, `medium`, `high`,
  `xhigh`, `max`, `ultra`; reply `{"key":"reasoning","value":"low"}` and a `session.info` with
  `reasoning_effort:"low"`, `reasoning_effort_wire:"low"`. `bogus` → error 4002 "unknown reasoning value". The words
  `show`/`hide`/`full`/`clamp` are display settings that write config.yaml — never sent by the app.
- **`session.info`** (event; also `info` of create/resume) carries `reasoning_effort` (`""` = provider default,
  `none` = off) and `usage`. A lazy session's info (create, cold resume) has only `model`, `cwd`, `tools`, `skills`,
  `lazy`… — usage and effort appear with the first built-agent `session.info`.
- **Usage**: `usage` objects (`message.complete.usage`, `session.usage {usage}`, `session.info.usage`, RPC
  `session.usage {session_id}` → the object itself) look like
  `{"model","input":15882,"output":4,"reasoning":0,"prompt":31498,"completion":4,"total":31502,"calls":2,"context_used":15754,"context_max":1000000,"context_percent":2,"context_source":"provider_usage","context_estimated":false,"compressions":0,"cache_hit_pct":50,"avg_latency_s":0.9,"avg_tps":2.3,"active_subagents":0}`.
  `total` is cumulative over the session; `context_*` is the current window occupancy. The `session.usage` event is a
  1 s ticker during a turn and only fires when counters change (a short turn may emit none).
  `session.context_breakdown {session_id}` → `{categories:[{id,label,tokens,color}], context_max, context_percent,
  context_used, estimated_total, context_estimated, context_source, context_files, model}` (not used by the app).
- **Compaction**: `session.compress {session_id, focus_topic?}` (idle-gated: 4009 while a turn runs) →
  `{status:"compressed"|"aborted"|"pending", removed, before_messages, after_messages, before_tokens, after_tokens,
  summary:{noop, aborted, refused_would_grow, headline, token_line, note}, usage, info, messages}`; a lock held
  elsewhere answers `{compressed:false, lock_held:true, message}`. A refused compression is still
  `status:"compressed"` with `summary.noop:true` and a headline saying why ("Compression refused (summary would grow
  the conversation): 8 messages preserved"). During it the server emits `status.update {kind:"compressing"|"compacting"|"warn"|"status", text}`
  and `sessions.changed`. `info.stored_session_id` names the stored id after compaction (it can rotate). The summary
  is a model call: the app gives the RPC 11 min (`compression.context_total_ceiling_seconds` is 600 s).
- **`subagent.*`** (`spawn_requested`, `start`, `progress`, `thinking`, `tool`, `complete`; `text` is for watch
  windows): `{goal, task_count, task_index, subagent_id, parent_id?, child_session_id, delegation_id, depth, model,
  tool_count, toolsets[], text?, tool_preview?, status?, summary?, input_tokens?, output_tokens?, duration_seconds?}`;
  `complete.status` ∈ `completed`/`failed`/`error`/`timeout`/`interrupted`. Observed: a background delegation's
  `tool.complete` (`result.status:"dispatched"`) and the turn's `message.complete` came **before** the child's
  `subagent.start/thinking/complete`, then `status.update {kind:"process", text:"Subagent Task Completed: …"}`.
- **`tool.output_risk`** `{tool_id, name, risk:"low"|…, findings:[str], redacted:bool}` (only with
  `display.tool_progress` on; not observed live). **`session.resume_progress`** `{phase:"history",
  status:"loading"|"complete"|"failed", message_count?, message?}` — only for deferred resumes (`defer_history`), which
  the app does not use. **`notification.show`** `{text, level:"info"|"warn"|"error"|"success", kind:"sticky"|"ttl"|"agent",
  ttl_ms, key, id}`, withdrawn by `notification.clear {key}`.
- **Global change events**: `sessions.changed {}` (twice per turn in the probe, and on compaction) and
  `platforms.changed {}`, both with `session_id:""`.
- **Archived sessions**: `PATCH /api/sessions/{id} {"archived":true}` → `{"ok":true,"title":…,"archived":true}`; an
  archived session still resumes normally (`session.resume` works and does not unarchive it); `{"archived":false}`
  restores it.
- **Toolset toggle side effect**: the first `PUT /api/tools/toolsets/{name}` rewrites
  `platform_toolsets.<platform>` from the preset (`["hermes-cli"]`) into an explicit list of the enabled toolsets (and
  records `known_*_toolsets`), so later Hermes releases' new default toolsets are not auto-enabled for that
  platform. Toggling back on does not restore the preset.

- **Cron-run sessions** (verified 2026-10-20): a job run is stored as a normal session with `source: "cron"`, id `cron_<jobid>_<YYYYMMDD_HHMMSS>`, title `"<job name> · Oct 20 15:02"`, preview starting `[IMPORTANT: You are running as a scheduled cron job…`. `GET /api/sessions` takes `source=`/`sources=`/`exclude_sources=`; the dashboard lists chats (`exclude_sources=cron`) and runs (`source=cron`) as two independent lists, each under the 100-row cap — the app does the same.

### v0.7.0 — audio relay (2026-10-20, verified live)

- **`POST /api/audio/transcribe`** (bearer like every `/api/*` call; not profile-scoped): body
  `{"data_url":"data:audio/wav;base64,<…>","mime_type":"audio/wav"}`; Hermes relays the upload to its configured STT
  provider (Melious `faster-whisper-large-v3` over the OpenAI wire on the test instance). Reply
  `{"ok":true,"transcript":"<text>","provider":"openai"}`. Silence and filtered Whisper hallucinations answer
  `{"ok":true,"transcript":"","provider":null}` (seen live for 1 s of 16 kHz silence: treat as "nothing heard").
  Accepted mimes include `audio/wav`, `audio/mpeg`, `audio/ogg`, `audio/webm`, `audio/m4a`. Errors: `413` over 25 MB,
  `400` with `detail` for a bad payload or a provider error, `500` when the relay fails. The request carries no
  language: the server's STT language setting applies (Whisper auto-detects when it is unset; English and Polish
  samples came back verbatim in about 1 s).
- **`GET /api/audio/voice-config`** → `{"ok":true,"stt":{"mode":"direct"|"relay","provider","model","streaming":bool,…},"tts":{…}}`;
  `stt.provider` `"none"` or a missing `stt` means no server STT. In `direct` mode the reply also carries the
  provider's `api_key`: the app reads only `stt.provider`, `stt.model` and `stt.streaming` (its DTO has no key field)
  and never stores the reply. `stt.streaming` is `false` for the OpenAI-wire provider: there are no live partials, so
  the app records one utterance, ends it with its own voice-activity detection and uploads it whole.
- The gateway's `voice.record` and `wake.*` RPCs capture on the **gateway host's own microphone**; they cannot serve
  a remote client and the app does not use them.

### v0.8.0 — rewind, side tasks, PDF, vault prompts (2026-10-21, verified live against 0.22.2)

- **`prompt.submit` flags**: `voice_turn: true` runs the turn on the server's `auxiliary.voice_chat` model/persona;
  `interrupted: true` (client-side barge-in) gives the model an "interrupted" note. The app sends each key only when
  true. Result: `{"status":"streaming","user_row_id":138}`; `user_row_id` is the persisted row of the accepted input.
  `message.complete` payload keys: `persisted_turn`, `status`, `text`, `usage`, with
  `persisted_turn = {"row_ids":[138,139],"complete":true,"user_row_id":138,"final_assistant_row_id":139}`.
  Transcript messages (`session.resume` / `session.history`) carry `row_id`.
- **Rewind (edit / regenerate)**: `prompt.submit {session_id, text, truncate_before_row_id: <user row>,
  confirm_truncate: true, confirm_deep_truncate: true}` archives the rows from that user row on, then runs the new
  turn. `confirm_deep_truncate` is required when the cut archives more than one user turn, `confirm_empty_truncate`
  when it archives the whole transcript; the app sends all three confirmations with every rewind (the user asked for
  exactly that cut).
  Result: `{"status":"streaming","survivor_user_row_ids":[138],"user_row_id":142}` (plus `survivor_row_id_map`). The
  RPC answers in about 20 ms; the first event after it is `session.info`, then the reply streams. Afterwards
  `session.history` holds only the surviving rows; archived rows are gone from the transcript.
  - Cutting before the **first** user row needs `confirm_empty_truncate: true`, else error **4028** "truncation
    would erase the entire session transcript; resubmit with confirm_empty_truncate=true"; with it →
    `{"status":"streaming","survivor_user_row_ids":[],"user_row_id":144}`.
  - A target row archived by an earlier rewind → error **4018** "target user message is no longer in session
    history", `data {user_turn_count, ordinal, segment_ordinal, prefix_user_count}` (the app restores the
    transcript; an edit stays in the composer to send as a new message). 4009 = a turn is still running; 5070–5072 =
    storage failed after the cut was written (the app reloads history).
- **Side questions / background tasks**: `prompt.btw {session_id, text}` (answered over a snapshot of the live
  conversation, not added to it) and `prompt.background {session_id, text}` → `{task_id}`. Results arrive later as
  events on the parent session: `btw.complete {task_id, text, question}` and `background.complete {task_id, text}`
  (`text` is `error: <exc>` when the side agent failed). Other clients' tasks arrive the same way; the app shows
  them as new cards.
- **`pdf.attach {session_id, content_base64, filename, first_page?, last_page?}`** →
  `{attached, filename, pages_attached, pages:[{path, page, width, height}], count, text}`. The server renders pages
  with `pdftoppm` at 150 dpi and queues them as images for the next turn (`count` = all images now queued;
  `image.detach {session_id, path}` drops one page). Without a range it renders pages 1–25 (`_PDF_ATTACH_MAX_PAGES`
  = 25 per call) and silently stops there; an explicit range over 25 → 4019. `text` is the server's note
  (`[User attached PDF: <name> (N page(s))]`). Errors: 4017 not a PDF (no `%PDF-` magic), 4018 over 50 MB, 4015 bad
  range, 5028 `pdftoppm` missing/failed.
- **`POST /api/audio/transcribe`** accepts `audio/ogg` (Opus, 24 kbps): a 5 s clip is 16 kB against 276 kB of WAV
  and came back verbatim.
- **`POST /api/audio/speak {text}`** → `{"ok":true,"data_url":"data:audio/mpeg;base64,…","mime_type":"audio/mpeg",
  "provider":"piper"}` (Piper on the test instance: mp3, 22050 Hz mono); `400` with `detail` when the provider
  fails. Speech follows Hermes' TTS settings (voice, language); the request carries neither.
- **`GET /api/audio/voice-config`** `tts`: `{mode, provider, …}`; the app reads only `tts.mode` and `tts.provider`
  (never `api_key`).
- **Vault prompts** (server requests, answered `{"value": …}`, `""` declines; see §2):
  `vault.code {site?, hint?}` → `{"value":"<code>"}`; `vault.save_login {origin, site}` →
  `{"value":"{\"identifier\":\"…\",\"password\":\"…\"}"}` (a JSON *string*). `site` is the host Hermes derives from
  `origin` (`tools/browser_vault_tool.py`), never agent text; the app shows the full `origin` too when it differs.
- **Reply images** (`![alt](url)`): "same origin" is scheme + host + port only, so a Hermes mounted under a path
  prefix on a shared host sends its bearer to sibling paths on that host too; a 401 on an image is final (no
  re-login, no sign-in prompt).

### v0.9.0 — connection requests (2026-10-21, verified live against 0.22.2)

- **Event `connection.request`** (on the session; `tui_gateway/agent_callbacks.py` `connection_callback`, payload
  `ConnectionOperation.request_payload`): `{op_id, seq, deadline_at (epoch s, server clock), timeout_seconds,
  tool_call_id?, display?{suggested[]}, targets[{name, kind, action, state, resolved?, detail?, instructions?,
  connect_url?, required_env?[{name, required, secret, default, prompt?}], hint?, display?, description?}]}`. It is an
  **event** (`method: "event"`), not a server request: the `manage_connections` tool thread blocks on the operation
  until it settles or `deadline_at` passes (~5 min), so an unanswered card hangs the turn. The same payload is
  `pending_connection` of a `session.resume` result while the operation is open; the app restores the card from it.
  `kind` ∈ connector | mcp | plugin | skill, `action` ∈ authorize | connect | enable | install | reconnect, `state` ∈
  pending | initiated | connected | skipped | failed | expired | not_connected (the app renders unknown values as
  given). `resolved` (else `state` ∈ connected | skipped) = the row needs nothing more.
- **Event `connection.update`** `{op_id, seq, deadline_at, settled, settled_at?, settled_by?, owner, targets[],
  target?, from?, to?, actor?, detail?}`: one transition (`actor` user | backend_watcher | clock) or the settlement
  (`settled_by` all_resolved | continue | deadline | interrupt), always with the full target snapshot. `seq` is the
  operation's own write counter (an older frame moves nothing). Session operations are emitted on the session with
  `owner {type:"session", session_id}`; account operations are broadcast globally with `owner {type:"account"}` —
  the app ignores an `op_id` it holds no card for.
- **RPC `connection.respond {owner: {type:"session", session_id: <live id>}, op_id, result: {targets[{name,
  status: approved|skipped, detail?, env?{NAME: value}}], settled_by?: "continue", said?}}`** →
  `{status:"ok", settled}`. `owner` is required (`ConnectionOperationParams`); an invalid `result` → 4002. Skip all =
  every target `skipped` with no `settled_by` (the operation settles `all_resolved`); Continue = `{targets:[],
  settled_by:"continue"}`; `approved` starts a pending row's backend work (an MCP install with its `env`), or retries
  a failed/expired one.
- **RPC `connectors.operation.wake {owner, op_id}`** → `{status:"ok"}`: the browser leg of a `connect_url` came back,
  so the backend reads the account now instead of at its next watcher tick. The app sends it when the chat comes
  back to the foreground after opening the link.

### v0.9.0 — todo checklists (2026-10-21, from the 0.22.2 source)

- **Event `todo.updated`** `{todos[{id, content, status, parent?}], revision}` (`tui_gateway/tool_progress.py`): the
  full snapshot after every `todo_list` (alias `todo`) call, sent regardless of `display.tool_progress`. `status` ∈
  pending | in_progress | completed | cancelled (the app reads anything else as pending); `parent` names another
  entry's `id`; `revision` is monotonic (the app replaces its one "Tasks" card at an equal or higher revision and
  ignores a lower one; `todos: []` at revision ≥ 1 clears the list). The todo tool's `tool.complete` carries the same
  `todos` + `revision` at the payload's top level; the `summary` rollup exists only in `tool.complete.result.summary`.
- **`todo_state {todos, revision}`** is on every `session.resume` result (and `session.activate`) when the session has
  a list (`_attach_todo_state`; omitted when it never had one — an empty list at revision 0 is not sent). There is no
  replay event: the app seeds the card from it (authoritative), and keeps its own card through a transcript rebuild
  when a resume carries none.

### v0.9.0 — vision_analyze images (2026-10-21, from the 0.22.2 source)

- **`vision_analyze` args carry `image_url`** (`tools/vision_tools.py`): an http(s) URL, a `data:` URL, or a path on
  the server (absolute, `~/…`, or `file://…`). The tool result is text only (the image is not in it), so the app shows
  the image from that arg on the tool row: http(s) by the reply-image rules (same origin loads, foreign waits for a
  tap), `data:` decoded in place, a path through **`GET /api/media?path=<path>`** (bearer; not profile-scoped;
  `hermes_cli/web_routers/files.py`) → `{"data_url": "data:image/<type>;base64,…"}`. Errors: `403` outside
  `~/.hermes/{images,screenshots,cache}`, `404` missing, `413` over 25 MiB, `415` a suffix other than
  png/jpg/jpeg/gif/webp/svg/bmp/ico, `400` an unresolvable path. The app treats every non-2xx (401 included, never a
  re-login) as final for that path ("image unavailable"); a dropped connection is retried.

### v0.9.0 — sends while a turn runs (2026-10-21, verified live against 0.22.2)

- **`prompt.submit {…, queued: true}`** while the session runs → `{status:"queued"}` with **no `user_row_id`**
  (`tui_gateway/session_auto_continue.py` `_handle_busy_submit`). The server persists the user row at accept, holds
  the prompt (with any images attached meanwhile) and runs it as its own turn after the live one
  (`_drain_queued_prompt`): the live turn's `message.complete`, `session.info`, then **`message.start`** of the queued
  turn, whose `message.complete.persisted_turn.user_row_id` is a fresh row (re-placed at the transcript end). Several
  queued prompts run one per turn, in order. Without `queued` the server applies `display.busy_input_mode` (default
  `interrupt` = redirect/kill the live turn), so the app always sends `queued: true` mid-turn (voice turns excepted).
  A send that finds the session idle runs at once (`{status:"streaming", user_row_id}`). Stop (`session.interrupt`)
  purges the queue: the turn ends with `message.complete {status:"interrupted"}`, the queued rows stay in the history
  but never run. A truncation mid-turn is still `4009`.
