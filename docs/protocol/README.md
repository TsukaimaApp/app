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
  - `vault.save_login` / `vault.code` exist too. Since 0.5.1 the app declines `vault.save_login` with 4404 (a desktop
    window attached to the session can still answer; once every client declined, Hermes resolves it with a
    `{"success":false}` refusal, which the vault reads as "not saved"), and answers `vault.code` `{"value":""}` ("no
    code entered"). Not 4404 for the code: with the phone as the only client, Hermes would pass the refusal JSON to
    the browser tool as the one-time code (`tui_gateway/server_requests.py` `_decline`, `server._ask`,
    `tools/browser_vault_tool.py`).
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

