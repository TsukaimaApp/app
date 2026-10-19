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
  (`HERMES_DASHBOARD_BASIC_AUTH_TTL_SECONDS`). On `401` the app re-runs the password login.
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

| Method | Params | Result |
|---|---|---|
| `client.capabilities` | `server_requests:bool` | `server_requests:[…]` |
| `gateway.ping` | – | `{}` |
| `session.create` | `source`, `cwd`, `model`, `provider`, `close_on_disconnect:bool` | `session_id*` (live id, 8 hex), `stored_session_id*` (`YYYYMMDD_HHMMSS_xxxxxx`), `messages*`, `info*` |
| `session.resume` | `session_id*` (stored id), `source`, `lazy`, `defer_history` | same + `resumed`, `running`, `pending_approval`, `open_requests` |
| `session.history` | `session_id*` | `count*`, `messages*` (TranscriptMessage[]) |
| `session.list` | `limit`, `include_hidden` | `sessions*` (`id`, `title`, `preview`, `started_at`, `message_count`, `source`) |
| `session.most_recent` | – | `session_id` (nullable) |
| `prompt.submit` | `session_id*`, `text*` | `status` (`"streaming"`), `user_row_id` |
| `session.interrupt` | `session_id*` | `status` (`interrupted`/`not_interrupted`) |
| `session.cwd.set` | `session_id*`, `cwd*` | session info |
| `session.title` | `session_id*`, `title` | – |
| `session.archive` | `session_id`, `archived:bool` | `archived*` |
| `session.delete` | `session_id*` | `deleted*` |
| `session.close` | `session_id*` | `closed*` |
| `model.options` | `session_id` | `providers*` (`slug`, `name`, `models[]`, `is_current`, `authenticated`), `model`, `provider` |
| `session.events.since` | `session_id*`, `last_seen` | `events*`, `latest_seq*`, `truncated*`, `open_requests*` |

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

Also: `turn.error {message,…}`, `message.interim {text, already_streamed}`, `approval.cancelled`,
`request.cancel {id, method, reason}`, `platforms.changed {}`, `gateway.ready {heartbeat:true}` (server-side pong).

`message.delta`, `reasoning.delta`, `thinking.delta` are coalesced server-side (~30 fps).

### Server → client requests

- `approval` params: `session_id`, `request_id`, `command`, `choices`, `tool_name`, … (extra keys allowed).
  Reply result: `{"choice":"once"|"session"|"always"|"deny","all":bool?}`.
  The REST fallback `approval.respond {session_id*, choice, request_id}` exists for stale sids.
- `clarify` params: `session_id`, `questions[]` (`ClarifyQuestion`). Reply `{"answers":{id:text}}` or `{"answers":null}`.

TranscriptMessage (history): `role` (`user`|`assistant`|`tool`), `text`, `reasoning`, `name`, `context`,
`args`, `tool_call_id`, `timestamp`, `row_id`, `display_kind`.

## 3. REST endpoints the app uses

All need the bearer header unless marked public. Management endpoints accept `?profile=<name>`.

| Endpoint | Notes |
|---|---|
| `GET /api/status` | public. Keys: `version`, `gateway_running`, `gateway_state` (`running`/`stopped`/null), `gateway_platforms{}`, `active_sessions`, `active_agents`, `profiles[]`, `memory{}`, `disk{total_mb,free_mb,used_percent}`, `overall`, `auth_required`, `auth_providers[]`. |
| `GET /api/system/stats` | `hostname`, `os`, `hermes_version`, `cpu_count`, `memory{total,available,…}`, … |
| `POST /api/gateway/start` / `stop` / `restart` | `{"ok":true,"pid":…,"name":"gateway-start"}`; state flips within ~5 s. |
| `GET /api/sessions?limit=N` | `{"sessions":[{id,title,preview,source,started_at,ended_at,message_count,model,archived,profile,…}]}` |
| `GET /api/sessions/search?q=` | FTS search. |
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
