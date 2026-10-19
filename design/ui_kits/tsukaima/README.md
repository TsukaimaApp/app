# Tsukaima UI kit

Interactive recreation of the Tsukaima app. Three entries share one app (`app.jsx`) and switch layout by container width:

- `index.html` — phone, 412dp: onboarding → settings → status home → chat / drawer / cron / logs
- `dark.html` — phone, forced dark theme
- `foldable.html` — Z Fold inner screen, 904dp: persistent sessions pane + content column (max 760dp)
- `web.html` — desktop web, 1280dp: same two-pane layout

Breakpoint: ≥720dp → two-pane, drawer button hidden; below → phone layout with the drawer as an overlay.

`?demo=1` autoplays onboarding → status → chat → drawer → cron → settings with a soft fade (used by the landing page embed).

## Information architecture (from the Hermes dashboard API)
Primary, one tap away:
- **Status** (home) — `GET /api/status`; gateway start/stop/restart with confirm; pending pairing card; continue last session / new chat.
- **Chat** — `/api/ws`; new chat shows a workspace (cwd) chip → picker sheet from `/api/chat/workspaces`. Model chip → picker sheet from `/api/model/options`. Overflow → Rename / Archive / Copy session ID / Delete. Tapping the status line returns to Status. Replies stream with a caret; history loads behind skeletons.
- **Sessions** — the drawer: search, grouped by profile, Archived view (unarchive/delete), long-press → rename / archive / delete (confirm).
- **Cron** — `/api/cron/jobs`: tap/long-press → run now, pause/resume, edit (full screen), delete (confirm).

Secondary, in Settings: profile selector (sets `?profile=`), model picker, skills + toolsets toggles, pairing list with approve/revoke, logs tail (file/level chips), one usage card, "Open admin dashboard" link, theme (Warm / Dark / System — wired to `data-theme`), and a kit-only "Simulate Hermes unreachable" switch to preview the offline state.

Deliberately absent: config editor, env/secrets, MCP, messaging credentials, webhooks, credential pool, memory reset, ops maintenance, bulk session deletes, PTY.

## Files
- `app.jsx` — state, fake agent, routing, width-based layout switch, demo autoplay
- `onboarding-screen.jsx`, `status-screen.jsx`, `chat-screen.jsx`, `drawer-overlay.jsx`, `cron-screen.jsx` (list + edit), `settings-screen.jsx`, `logs-screen.jsx`

Screens compose the bundled components (`window.TsukaimaDesignSystem_17565f`); they don't re-implement primitives.
