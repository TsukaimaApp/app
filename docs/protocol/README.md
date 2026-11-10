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

**Tool results and row identity** [live, recorded 2026-11-10 on 0.22.2; fixture
`app/src/test/resources/chat/recorded-tool-turn*`]:

- `tool.complete.result` is the tool's own JSON, not always `{output, exit_code, error}`: `read_file` answers
  `{content, total_lines, file_size, truncated, …}`, `web_search` data only, and a result that is not JSON arrives as
  a bare string. The app shows the first text field it knows, else the whole result (`ToolOutput.text`).
- `tool_id` of `tool.start` / `tool.complete` is the provider's tool-call id, the same value as a history tool
  row's `tool_call_id`. The app keys a tool row by it on both paths. A provider may reuse an id in a later turn.
- History tool rows (`session.history`, `session.resume`) carry **no `row_id` and no result**: `{role:"tool", name,
  context, tool_call_id, timestamp, args}`; `content` only for `write_file`, `patch` and `skill_manage`. User and
  assistant rows carry `row_id`. The assistant row before each tool call has `text:""`.
- The stored results are in `GET /api/sessions/{id}/messages?limit=500&offset=&order=oldest&inline_images=false`
  → `{session_id, messages[{id, role, content, tool_call_id, tool_name, …}], pagination{limit, offset, order,
  returned}}` (at most 500 a page). A tool row's `content` is the result as JSON text. The app reads it when a
  history tool row is first opened.
- `message.complete.persisted_turn` is `{row_ids[], complete, user_row_id, final_assistant_row_id}`; `row_ids` lists
  every row of the turn in order. `message.complete.usage` also carries `model`.
- A model that streams no reasoning sends `message.start`, then the tool events, then the first `message.delta`:
  the reply opened by `message.start` is still empty when the first tool row arrives.
- History assistant rows carry whichever reasoning keys the stored row has: `reasoning`, `reasoning_content`,
  `reasoning_details`, `codex_reasoning_items`, `codex_message_items` (`_HISTORY_ASSISTANT_DETAIL_KEYS`). A row can
  have `reasoning_content` and no `reasoning` [production store, 2026-11-10: 417 such rows]. The app reads
  `reasoning`, then a text `reasoning_content` (`TranscriptMessage.thought`).
- `/api/sessions/{id}/messages` returns active rows only [source]; the store can hold a second, inactive copy of
  a tool row (rewound or compacted) with the same `tool_call_id`. No provider id was reused inside one session in
  the production store [2026-11-10].
- **A dropped socket parks the live session for `dashboard.ws_orphan_reap_grace_s`, default 20 s** [source, 0.23.0,
  as reported by the server side]; after it the session is interrupted if it runs, then reaped, and the next
  `session.resume` answers a new live id, so the app rebuilds the transcript from history. An idle session lives
  6 hours (`HERMES_TUI_SESSION_TTL_S`).

**A fallback turn** [live, recorded 2026-11-10 on 0.23.1; fixture
`app/src/test/resources/chat/recorded-fallback-turns.jsonl`: two turns of a session whose model does not exist,
with `fallback_model` set]:

- `status.update {kind:"lifecycle", text:"⚠️ Model fallback: <primary> via <provider> unavailable (model not
  found); using <fallback> via <provider>."}` arrives during the turn, before `message.complete`.
- **`message.complete.usage.model` and the turn's `session.usage.usage.model` name the fallback model.** The
  `session.info` after the turn also names it in `model` (and `usage.model`), so the header label changes to the
  fallback model after a fallback turn.
- The next turn starts with `status.update {kind:"lifecycle", text:"✅ Primary model restored: <primary> via
  <provider>; fallback <fallback> via <provider> is no longer active."}`. No `session.info` follows it, so nothing
  tells the header the primary is back; if that turn falls back again, the ⚠️ line repeats.
- So a per-reply model label needs no Hermes change: read `usage.model` at `message.complete`.

**The server's description of the app** (`platform_hints.tsukaima` in the operator's Hermes config, appended to the
system prompt of every Tsukaima session; text as of 2026-11-10). It must change together with
`domain/markdown/` and the media cards:

> You are chatting inside Tsukaima, the user's Android phone app for Hermes (a graphical chat surface on a small screen). Keep replies compact for a phone: short paragraphs, no ASCII layouts. Markdown in replies is rendered: headings, bold, italic, strikethrough, inline code, links, block quotes, horizontal rules, bullet and numbered lists nested up to four levels, task lists, and GFM tables. Tables are fine; a wide one scrolls sideways, so prefer a few short columns. Keep lists tight: do not put blank lines between items or a code block inside an item, and do not nest emphasis (no \*\*\*text\*\*\*). Fenced code blocks show a language label and a copy button but no syntax highlighting, and long lines scroll sideways. LaTeX math is rendered: `$...$` or `\(...\)` inline, `$$...$$` or `\[...\]` on its own lines; do not define macros. Do not use raw HTML, footnotes, or Mermaid and other diagram blocks; they appear as plain source text. To show a remote picture, put `![alt](https://...)` alone on its own line (images from hosts other than this server load only after the user taps them); never write a local file path as a Markdown image or as prose and expect it to show. To deliver any local file, write `MEDIA:/absolute/path` alone on its own line, outside code fences and list items: it becomes a download card, with a preview for PNG, JPEG, GIF, WebP and BMP images. Audio and video are delivered as download cards and do not play inside the chat, so say in words what the file contains. In voice mode the app reads your text aloud and skips code blocks, math and MEDIA lines.

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

### v0.9.0 — connections, todos, media, queue, interrupted tools (2026-10-21, verified live against 0.22.2)

Each item says how it was established: **[live]** = probed against the test instance (0.22.2) during this release,
**[source]** = read from the 0.22.2 source only.

**Connection requests**

- **Event `connection.request`** [source + live] (on the session; `tui_gateway/agent_callbacks.py`
  `connection_callback`, payload `ConnectionOperation.request_payload`): `{op_id, seq, deadline_at (epoch s, server
  clock), timeout_seconds, tool_call_id?, display?{suggested[]}, targets[{name, kind, action, state, resolved?,
  detail?, instructions?, connect_url?, required_env?[{name, required, secret, default, prompt?}], hint?, display?,
  description?}]}`. It is an **event** (`method: "event"`), not a server request: the `manage_connections` tool
  thread blocks on the operation until it settles or `deadline_at` passes (~5 min), so an unanswered card hangs the
  turn. `kind` ∈ connector | mcp | plugin | skill, `action` ∈ authorize | connect | enable | install | reconnect,
  `state` ∈ pending | initiated | connected | skipped | failed | expired | not_connected (the app renders unknown
  values as given). `resolved` (else `state` ∈ connected | skipped) = the row needs nothing more.
  - [live] With `client.capabilities {server_requests: true}`, "connect linear" arrived after `tool.start
    manage_connections` with keys `deadline_at, op_id, seq, targets, timeout_seconds, tool_call_id` — **no `owner`,
    no `display`** — and the connector row already `state: "initiated"` with a `connect_url` (`seq: 1`, deadline
    ~298 s ahead).
  - [source] The same payload is `pending_connection` of a `session.resume` result while the operation is open; the
    app restores the card from it. A card that settled while the app was away does not come back (only open
    operations do); the tool row stays in history.
- **Event `connection.update`** [source + live] `{op_id, seq, deadline_at, settled, settled_at?, settled_by?, owner,
  targets[], target?, from?, to?, actor?, detail?}`: one transition (`actor` user | backend_watcher | clock) or the
  settlement (`settled_by` all_resolved | continue | deadline | interrupt), always with the full target snapshot.
  `seq` is the operation's own write counter; a frame with `seq <=` the card's moves nothing (the desktop's rule).
  Session operations are emitted on the session with `owner {type:"session", session_id}`; account operations are
  broadcast globally with `owner {type:"account"}` — the app ignores an `op_id` it holds no card for.
  - [live] After a Skip all: seq 1 (backend_watcher, initiated→initiated, `settled:false` — a no-op with the
    request's own seq, dropped), seq 2 (actor user, to skipped), seq 3 (`settled:true, settled_by:"all_resolved"`).
    The first frame can arrive **before** the RPC reply; the app also settles the card from the reply's `settled`.
- **RPC `connection.respond`** [live] params `{owner: {type:"session", session_id: <live sid>}, op_id, result:
  {targets[{name, status: approved|skipped, detail?, env?{NAME: value}}], settled_by?: "continue", said?}}` →
  `{status:"ok", settled}`. `owner` is required (`ConnectionOperationParams`; the desktop builds it from the session
  id, `apps/desktop/src/store/connection-request.ts:494`) — the request event carries none, so the app uses the
  card's update-borne owner, else `{type:"session", session_id: <live id>}`. Skip all = every target `skipped` with
  no `settled_by` (settles `all_resolved`); Continue = `{targets:[], settled_by:"continue"}`; `approved` starts a
  pending row's backend work (an MCP install with its `env`) or retries a failed/expired one. Errors [source]: an
  invalid `result` → 4002 `invalid_answer`; no open operation for that owner + `op_id` → 4004 (the app toasts it —
  after a reconnect to a new live session the turn that owned the operation has ended).
  - [live] Skip all settled in 0.01 s; `tool.complete manage_connections` followed 0.58 s later with
    `result {op_id, seq:3, settled_by:"all_resolved", targets[…state:"skipped"], status:"settled", note}`, then the
    turn completed.
- **RPC `connectors.operation.wake {owner, op_id}`** → `{status:"ok"}` [source]: the browser leg of a `connect_url`
  came back, so the backend reads the account now instead of at its next watcher tick. The app sends it once per
  opened link when the chat returns to the foreground (as the desktop does on `hermes://connections/done`), not
  when the browser opens. [live] The same owner envelope is accepted; a wake after the operation settled →
  `4004 {"reason":"UNKNOWN_OPERATION"}`, which the app ignores.

**Todo checklists**

- **Event `todo.updated`** [source + live] `{todos[{id, content, status, parent?}], revision}`
  (`tui_gateway/tool_progress.py`): the full snapshot after every `todo_list` (alias `todo`) call, sent regardless
  of `display.tool_progress`. `status` ∈ pending | in_progress | completed | cancelled (the app reads anything else
  as pending); `parent` names another entry's `id`; `revision` is monotonic (the app replaces its one "Tasks" card at
  an equal or higher revision and ignores a lower one; `todos: []` at revision ≥ 1 clears the list). The todo tool's
  `tool.complete` carries the same `todos` + `revision` at the payload's top level (it is emitted first); the
  `summary` rollup exists only in `tool.complete.result.summary`.
  - [live] `scripts/e2e_smoke.py` `todo_part`: "Create a two-item todo list with todo_list" → one `todo.updated`
    with 2 entries (each with `id` and `status`) at `revision: 1` before `message.complete`.
- **`todo_state {todos, revision}`** [source] is on every `session.resume` result (and `session.activate`) when the
  session has a list (`_attach_todo_state`; omitted when it never had one — an empty list at revision 0 is not
  sent; `session.create` carries none). There is no replay event: the app seeds the card from it (authoritative)
  and keeps its own card through a transcript rebuild when a resume carries none. `TodoStore.restore` keeps the
  revision, so revisions stay comparable across rebuilds.

**vision_analyze images**

- **`vision_analyze` args carry `image_url`** [source] (`tools/vision_tools.py`): an http(s) URL, a `data:` URL, or
  a path on the server (absolute, `~/…`, or `file://…`). The tool result is text only, so the app shows the image
  from that arg on the tool row (also for rows rebuilt from history, which carry `args`): http(s) by the
  reply-image rules (same origin loads with the bearer and no redirects; a foreign host waits for a tap), `data:`
  decoded in place (refused unread above 25 MiB), a path through **`GET /api/media?path=<path>`** (bearer; not
  profile-scoped; `hermes_cli/web_routers/files.py`) → `{"data_url": "data:image/<type>;base64,…"}`. Errors: `403`
  outside `~/.hermes/{images,screenshots,cache}`, `404` missing, `413` over 25 MiB, `415` a suffix other than
  png/jpg/jpeg/gif/webp/svg/bmp/ico, `400` an unresolvable path. The app treats every non-2xx (401 included, never a
  re-login) as final for that path ("image unavailable"); a dropped connection or 5xx offers tap to retry. A
  relative path gets no thumbnail (the server would resolve it against its own cwd).

**Sends while a turn runs**

- **`prompt.submit {…, queued: true}`** [live] while the session runs → `{status:"queued"}` with **no
  `user_row_id`** (`tui_gateway/session_auto_continue.py` `_handle_busy_submit`). The server persists the user row at
  accept, holds the prompt (with any images attached meanwhile) and runs it as its own turn after the live one
  (`_drain_queued_prompt`): the live turn's `message.complete`, `session.info`, then **`message.start`** of the
  queued turn, whose `message.complete.persisted_turn.user_row_id` is a fresh row (the accept-time row is re-placed
  at the transcript end, `_replace_queued_user_row_for_turn`). Several queued prompts run one per turn, in order
  [source]. Without `queued` the server applies `display.busy_input_mode` (default `interrupt` = redirect/kill the
  live turn), so the app always sends `queued: true` mid-turn (voice turns excepted). "Mid-turn" includes a turn
  paused on an approval, question, masked prompt or connection, and the gap between the live `message.complete` and
  the queued turn's `message.start`. A send that finds the session idle runs at once (`{status:"streaming",
  user_row_id}`).
- **Stop purges the queue** [live]: `session.interrupt` → `{status:"interrupted"}` purges the queue at once
  (`_interrupt_session_turn`) and emits no turn end itself; the turn then ends with `message.complete
  {status:"interrupted"}` — or, when it was still waiting for its agent build, with a bare `error` ("Turn cancelled
  before the agent was ready") — and the queued prompt never runs, but its accept-time row **stays in
  `session.history`** (user, user (queued), assistant). Only the app's Stop purges: the app marks it and shows the
  queued sends "Not sent" with Retry on the first turn end of any kind that follows. Every other turn end leaves the
  queue, which drains next (`_drain_queued_prompt`; source reading): a failed turn (`message.complete
  {status:"error"}`, a failed agent build included), a bare `error` / `turn.error` (model switch, compression, a
  queued prompt that failed), and the server's own interrupt — a voice barge-in (a non-queued submit mid-turn)
  redirects the turn, or queues itself behind the waiting sends and interrupts the turn without purging them — so the
  app keeps those sends "Queued". A truncation mid-turn is still `4009`.

**Interrupted tools and orphan notices**

- **A killed terminal command** [source] completes normally: `tool.complete.result = {output: "<partial>\n[Command
  interrupted]", exit_code: 130, error: null}`. The app shows it **Interrupted** (amber), not failed, with the partial
  output minus that final marker line; exit 130 without the marker as the last line (and the `[execution
  interrupted]` / exit -1 variants) stays a failure. A tool of the live turn still running when it ends with
  `message.complete {status:"interrupted"}` (no `tool.complete` came) is Interrupted with no output. The turn-end
  reasons (`interrupted_by_user`, `interrupted_during_api_call`) are **not** on the wire.
- **Failure predicate** (unchanged, the union): failed ⇔ `result.error` truthy (Python truthiness) **or**
  `result.success == false` **or** `result.exit_code` non-zero — except the interrupted shape above.
- **Orphan-recovery notices** [source] (`agent/replay_cleanup.py`): on resume Hermes rewrites an interrupted
  side-effecting block's tool results (and answers a dangling side-effecting tail) with one of
  `[Orphan recovery: interrupted side-effecting tool may have executed; its effect is UNKNOWN. Inspect state before
  retrying.]`, `[Orphan recovery: interrupted read-only tool did not complete.]`, `[Orphan recovery: this tool may
  have executed before Hermes stopped; …]`, `[Orphan recovery: this read-only tool did not complete and had no
  effect.]`. Read-only-only blocks are dropped. They are tool-row results, and the history projection
  (`tui_gateway/session_history.py` `_history_to_messages`) forwards a tool row's `content` only for `write_file`,
  `patch` and `skill_manage` — so for other tools (`terminal`…) the notice never reaches a client (Hermes side). The
  app marks such a tool row Interrupted with "May have partially run — check before retrying" (UNKNOWN wording) or
  "Did not complete" (read-only). Only a tool row's `content` counts: a user or assistant message starting with
  `[Orphan recovery:` is an ordinary bubble (Hermes writes the notices nowhere else).

**Client-side only (no wire change)**

- **Math in replies**: `$…$`, `$$…$$`, `\(…\)` and display `\[…\]` render through
  `ru.noties:jlatexmath-android:0.2.0`. TeX is agent-controlled, so `MathSafety` refuses formulas over 2000 chars or
  with commands that load files, define macros or change global library state (`\includegraphics`, `\input`,
  `\newcommand`, `\def`, `\DeclareMathOperator`, `\definecolor`, …); those, and any formula that fails to parse,
  show as inline code with the raw TeX (never a crash). Math is never spoken (`SpeechText` drops it).

### v0.9.2 — verified facts

Tagged as in v0.9.0: **[live]** probed, **[source]** read from the 0.22.2 source only.

- **`session.interrupt` purges the queue even when nothing runs** [source] (`tui_gateway/session_lifecycle.py`
  `_interrupt_session_turn`): the queue is cleared unconditionally and the result is `"interrupted"` even when idle;
  `not_interrupted` comes only from the hosted-room `expected_hosted_task_id` path. A Stop between turns therefore
  purges a waiting queued send with no turn end to follow, and "Nothing to stop" is effectively never returned.
- **`session.resume` retires undrained queued rows** [source] (`methods_session.read_history` calls
  `retire_undrained_queue_rows` on every resume): a queued send that never ran is gone from the resumed history, so a
  client must not match a purged send to a history row after a resume.
- **The todo revision is restored from history when the agent is rebuilt** [source] (`run_agent.py` ~1032-1039:
  restored only when the history carries a newer revision), so revisions stay monotonic within a session.

### Voice-live (GPT-Live) — routes and `prompt.submit` params (2026-10-24)

- `GET /api/audio/voice-live/status?profile=<id>` → `{ok, mode:"chained"|"gpt-live", available, reason:string|null,
  model, voice}`. `404` on a Hermes without the route. `available: true` is a hint, not a promise.
- `POST /api/audio/voice-live/session?profile=<id> {sdp, history?}` → `200 {ok, session:{id}, transport:{type:
  "webrtc", sdp}}` (the app adds `?profile=` for a non-default profile, as on the status call; the answer SDP is
  the vendor’s, so the media then flows between the phone and the vendor, not through Hermes [source:
  `tools/voice_live.py` `create_webrtc_session`]).
  `400` blank SDP, `503` no API key, `502` vendor rejected (errors carry `{"detail": "…"}`). The SDP goes out
  byte-exact incl. trailing CRLF; allow ~45 s.
- `prompt.submit` takes two more optional params for delegated voice turns: `surface` (`"voice-live"`) and
  `voice_context` (recent spoken transcript; Hermes keeps it only for that surface, capped at 6,000 chars).

### `status.update` — what the app shows (recorded from Hermes v0.23.1, 2026-11-10)

`status.update {kind, text}` (`tui_gateway/server.py::_status_update`) is Hermes’ transient status line. The shapes
below are from recordings (`app/src/test/resources/chat/recorded-compaction.jsonl`,
`recorded-compaction-refused.jsonl`, `recorded-fallback-turns.jsonl`), not from reading the source; the 0.10.1
handling was written from the source and got four of them wrong.

| Frame | Sent for | App |
|---|---|---|
| `{kind:"compacting", text:"📦 Preflight compression: …"}`, `{kind:"compacting", text:"🗜️ Compacting context — …"}` | An automatic compaction at the start of a turn (also during a manual one) | `ChatState.compacting` → status "Compacting context…" |
| `{kind:"compacted", text:"✓ Context compaction complete — continuing turn..."}` | It finished | Clears the state; quiet notice "Context compacted" |
| no frame | It was refused or changed nothing | The turn’s next output (`message.delta`, `reasoning.delta`, `tool.*`) clears the state |
| `{kind:"compressing", text:"⠋ compressing 8 messages (~17,911 tok)…"}` | A manual `session.compress` started | Sets the state |
| `{kind:"status", text:"ready"}` | A manual compress ended. A bare status word travels as kind `status` with the word as its text; there is no kind `ready` | Clears the state |
| `{kind:"compacting", text:"⚠️  Session compressed 2 times — accuracy may degrade. …"}` | A warning the gateway re-tags because its text mentions compression | Warning notice; never sets the state |
| `{kind:"warn", text:"⚠️ Compression refused: …"}`, any `warn` | Degraded paths | Warning notice |
| `{kind:"lifecycle", text:"⚠️ Model fallback: <model> via <provider> unavailable (<reason>); using <model> via <provider>."}` | The fallback served this turn; sent before `message.complete` | Warning notice; sets `ChatState.fallback` |
| `{kind:"lifecycle", text:"✅ Primary model restored: …; fallback … is no longer active."}` | Start of the next turn | Quiet notice; clears `fallback` |
| `{kind:"lifecycle", text:"❌ …"}` | Terminal failures (retries exhausted, credits gone); from the source, not recorded | Error notice |
| `{kind:"process", text:"Subagent Task Interrupted: …"}`, `goal`, `loop`, `heartbeat`, other `lifecycle` | Background chatter | Dropped |

Diagnostics respect `display.suppress_warning_notifications`. The state also clears at the turn end and when the
connection is lost.

**The model that answered.** `message.complete.usage.model`, `session.usage.usage.model` and the `session.info`
after the turn all name the model the agent was on when the turn ended; on a fallback turn that is the fallback
model. The next turn starts with the "Primary model restored" line and **no** `session.info`, so a client that
copies `session.info.model` into its header shows the fallback model until the turn after. The app keeps the
session’s own model apart (`ChatState.model`, not updated from `session.info` while `fallback` is set) and labels a
reply with `usage.model` when the two differ. `TranscriptMessage` has no model field, so the label is lost when the
chat is loaded again.

`source: "tsukaima"` is stored verbatim and unvalidated. It is not in `agent/session_source.py`
`UI_TRANSPORT_SOURCES` (`tui`, `desktop`), so Tsukaima sessions are hidden from the TUI/dashboard resume pickers and
`hermes -c`; toolsets fall back to `platform_toolsets.cli`. The surface description the model gets is the operator’s
`platform_hints.tsukaima` config entry (`hermes config get platform_hints.tsukaima`), which must track
`domain/markdown/Markdown.kt`’s block types (it renders GFM tables, so a hint saying otherwise is stale).

### v0.11.0 — steer, subagents, slash commands, branch, session edits (verified live against v0.23.1, 2026-11-10)

Recordings for each are in `app/src/test/resources/chat/` (`recorded-steer-*`, `recorded-subagent*`,
`recorded-slash-commands.json`, `recorded-branch.json`).

**Steer.** `session.steer {session_id*, text*}` → `{status, text}`. `queued` = taken: the agent reads it after its
current tool batch, before the next model call, as a user row of its own. `rejected` = no turn is running (send it
as a normal message). While a compression runs the text is queued as a next-turn prompt instead, and the answer is
also `queued`. No event marks the delivery. History: the row is `{role:"user", text:<the user's words>,
display_kind:"steer", row_id}` right after the tool row it followed; `message.complete.persisted_turn.user_row_ids`
is `[<the turn's row>, <one per steer>]`. A steer the turn ends without reading (`result.pending_steer`) is put at
the end of the server's prompt queue and runs as its own turn; Stop (hard interrupt) drops it.
`session.redirect` exists beside it and is not used.

**Subagents.** A `delegate_task` call can return at once (`result.mode: "background"`); the child's `subagent.*`
events then continue after the turn's `message.complete`, and its completion starts a new turn by itself (no user
row from this client). Every `subagent.*` payload carries `subagent_id`, `child_session_id`, `model`, `tool_count`.
- `subagent.tail {session_id*, subagent_id*}` → `{available, text, truncated}`: the last 16 KB of the child's live
  transcript (plain text; a file header up to a `=====` line, then `HH:MM:SS kind | text` lines). `available: false`
  before the first line and after the child ended.
- `subagent.interrupt {session_id*, subagent_id*}` → `{found}`. The child ends with `subagent.complete {status:
  "interrupted", summary}` and a `status.update {kind:"process", text:"Subagent Task Interrupted: …"}`.
- `subagent.steer {session_id*, subagent_id*, text*}` → `{status: "queued" | "rejected"}`.
- `subagent.list {session_id*}` → `{subagents[], delegations[]}` (not used).

**Slash commands.** `commands.catalog {session_id}` works for `source: "tsukaima"` (152 `pairs`, 54 skills on the
test server): `pairs` is `[name, description]`, `canon` maps every accepted name to its command, `skills` and
`plugin_commands` are keyed by name, `commands[name].desktop` is `terminal` / `hidden` / `settings` /
`composer-voice` / `advanced` / null. Run: `slash.exec {session_id*, command*}` → `{output, warning?}` or a
directive; error 4018 "use command.dispatch" for skills and pending-input commands → `command.dispatch {name*, arg,
session_id}` → `{type, output?, message?, display?, notice?, target?}` with `type` `exec` / `plugin` (show
`output`), `send` / `skill` (submit `message` as a turn; show `display` or the typed command), `prefill`, `alias`.
A 4018 from both calls is the command's own refusal ("no user messages to undo"). `/undo`, `/retry`, `/compress`
rewrite the stored transcript. An unknown command answers `{output: "Unknown command /x — nothing was sent. …"}`.

**Branch.** `session.branch {session_id* (live), count?, name?}` → `{session_id, stored_session_id, title, parent,
message_count, messages[], info}`. The child holds the first `count` rows of the parent's user and assistant rows
that have visible text; tool rows and reasoning-only rows are not in that list and are **not copied**, so the
child's model reads what was said but not the tool results (in the probe it said so itself and ran the tool again).
The parent is untouched. The list row of a child carries `parent_session_id`.

**Session edits need an identity.** Since 0.23, `PATCH /api/sessions/{id}` (`title`, `archived`, `pinned`,
`hidden`, `unread`) and `DELETE /api/sessions/{id}` answer **409 `client_upgrade_required`** without `request_id`
(any unique string) and `expected_revision` (the row's `runtime_revision`, from `GET /api/sessions/{id}` or the
list). `DELETE` takes them as query parameters and also needs `expected_generation` (`runtime_generation`), else
400 `invalid_params`. A stale revision answers 409 `revision_conflict`. The answer is `{ok, revision, …}`. App
versions up to 0.10.3 sent bare edits, so rename, archive and delete failed against 0.23.

**Session list.** `GET /api/sessions` takes `offset` and `order=recent` (latest activity first; default
`created`) and answers `{sessions, total, limit, offset}`. Rows carry `pinned`, `is_active` (not ended and active
in the last minutes), `last_active`, `parent_session_id`.

**Other events.** `tool.progress {tool_id, name, progress, total?, percent?, message?}`: an MCP tool's progress, at
most one per 500 ms per call (contract only; not recorded). `reasoning.available {text}`: the reasoning of a
response whose provider did not stream it, cut to 500 characters; not sent when `reasoning.delta` carried it
(0.22 sent the answer text here). `session.reclaimed {session_id, stored_session_id, reason}`: a broadcast when the
server drops a live session (`idle_timeout`, `lru_evict`, `ws_orphan_reap`).
