# Handoff: Tsukaima — Android client for Hermes Agent

## Overview
Tsukaima (使い魔, "familiar") is a native Android app for talking to a self-hosted Hermes Agent instance (Nous Research): chat, watch the agent's live status, approve tool calls, manage cron jobs, switch models/profiles, start/stop the gateway. Single user, one Hermes, no account, no middleman. This project folder is the handoff package: the complete design system (tokens, components, screens) plus an interactive HTML prototype of every screen.

## About the design files
Everything here is a **design reference built in HTML/React** — it shows intended look and behaviour, it is not production code. The task is to **recreate these screens in the Android codebase** (Kotlin + Jetpack Compose, Material 3 baseline is assumed; choose otherwise if the project already has a stack). The web build (`ui_kits/tsukaima/web.html`) shows the same design at desktop width should a web client follow.

## Fidelity
**High-fidelity.** Colors, type, spacing, radii, copy and interactions are final. Recreate pixel-for-pixel (1 px here = 1 dp; font sizes are sp). Values are intentionally off-grid where stated (e.g. 15.5sp body, 14dp input radius, 28dp composer) — do not snap them.

## Where to look
- `readme.md` — brand, content voice, visual foundations, IA, implementation decisions. **Read first.**
- `styles.css` + `tokens/*.css` — every color, type role, spacing, radius, shadow as CSS custom properties (light + `[data-theme="dark"]`).
- `components/**` — each primitive as `Name.jsx` (reference implementation), `Name.d.ts` (props contract), `Name.prompt.md` (usage + rules).
- `ui_kits/tsukaima/` — the interactive prototype. Open `index.html` (phone), `dark.html`, `foldable.html` (Z Fold inner, 904dp), `web.html` (1280). `README.md` there maps every screen to the Hermes dashboard API endpoints.
- `ui_kits/site/index.html` — project landing page (web, optional).
- `assets/` — mark (`mark.svg` + dark/on-accent/ink variants, `app-icon.svg/.png`), empty-state illustration (`resting-wisp.svg`).
- `guidelines/*.html` — specimen cards for tokens/type/brand.

## Screens
All screens share: page background `--surface-page` (#F7F4ED light / #1B1917 dark), 20dp page gutters, no tab bar, drawer as the only navigation, 44dp minimum hit targets.

### 1. Onboarding / empty state
- Centered column, 36dp side padding. Mark 72dp high → 14dp → wordmark "Tsukaima" Source Serif 4 600 38sp → "Your familiar is listening." Source Serif 4 italic 500 14sp `--text-secondary` → caption 12.5sp `--text-tertiary` max 260dp wide → 22dp → **Connect** primary pill → 40dp → resting-wisp illustration 180dp wide, 80% opacity. Kanji 使い魔 vertical at top-right (14sp, letter-spacing .2em, tertiary).
- Connect → Settings (first-run should become URL + token + "Test connection" — flagged as a to-do, not yet in the prototype).

### 2. Status (home)
- Top row: menu icon (44dp) · mark 20dp + "Tsukaima" serif 20sp · spacer · settings icon.
- **StatusLine** beneath: dot 7dp + serif italic 14sp. States: idle grey #746B61 "Hermes is listening"; stopped red #B3564E "Gateway stopped"; offline red "Can't reach Hermes"; tool/thinking lavender pulsing (2s opacity 1→.35).
- Buttons row (8dp side pad): **New chat** primary (icon add) + **Cron** secondary, equal width, 10dp gap.
- Row "Continue last session" (icon history, chevron_right).
- PairingCard if a request is pending.
- Label "STATUS" (11sp 600 uppercase .08em tertiary) then 5 Rows (52dp min, icon 22dp secondary, title 14sp 500, subtitle 12.5sp tertiary): version/host, gateway state (red when stopped), platforms, active sessions, memory/disk.
- Actions: running → **Restart gateway** secondary (icon restart_alt) + **Stop** ghost red; stopped → **Start gateway** primary. Restart/Stop confirm via Dialog.
- Offline variant: Card (20dp pad) "Can't reach Hermes" title 19sp + caption + **Retry** primary (icon refresh) + Settings ghost; "Open cached copy" row; last-known facts at 55% opacity under label "LAST KNOWN STATUS · 14 MIN AGO".

### 3. Chat
- **TopBar**: 8dp pad; menu icon (hidden ≥720dp) · model Chip (borderless, label 14sp 500 + expand_more) · spacer · more_vert. Model chip → **Menu** sheet listing `/api/model/options` (check icon on current).
- StatusLine under it; tapping it returns to Status.
- Messages (6dp top pad): 
  - **UserBubble**: right-aligned, max 82% width, `--bubble-user` #EAE6F8 (dark #322E48), 16dp radius with 6dp bottom-right, 10×14dp padding, body 15.5sp/1.55 Instrument Sans.
  - **AssistantMessage**: flush left on page bg, 20dp gutters, max 640dp, no bubble. `streaming` shows a 2dp×1em lavender caret blinking 1s steps(2).
  - **ToolRow**: 1dp hairline, 12dp radius, raised bg, 44dp header: icon (terminal / psychology) 18dp, name 14sp 500 secondary, " · detail" tertiary, status icon 16dp (check / error red), expand_more rotating 180° over 200ms. Expanded body: 12dp pad, caption 12.5sp.
  - **CodeBlock**: #272233 bg (dark #141218), 10dp radius, header row lang label 11sp uppercase at 55% + "Copy" 12.5sp (content_copy → check "Copied" 1.5s), pre IBM Plex Mono 13sp/1.6 #D8D3E8, 14dp side pad.
  - **ApprovalCard**: raised bg, hairline, 16dp radius, shadow `0 2px 12px rgba(60,50,40,.10)`, 16dp pad. Header gpp_maybe 20dp amber + serif italic 14sp "Hermes wants to run a tool". Tool label uppercase 11sp. Command in code well (10×12dp). Buttons right-aligned 10dp gap: **Deny** secondary red ink, **Approve** primary (icon check). *The only filled-accent surface in chat.*
  - **PairingCard**: same tier; link icon amber; "Pairing request from Telegram"; who (14sp secondary) left, code mono 20sp letter-spacing .18em right; **Revoke** red + **Approve** primary.
- Empty new chat: "New chat" serif italic 16sp, caption "Working directory", Chip (icon folder, path, expand_more) → Menu sheet of `/api/chat/workspaces`.
- Loading history: skeletons (bubble 56%×40 r16, lines 92/78/40%, row 44 r12), sunken bg, 1.6s pulse.
- **Composer** (6dp/12dp/14dp padding around): raised bg, hairline, 28dp radius, shadow, 6dp inner pad; attach IconButton 44 (add) · textarea body 15.5sp, 11dp vertical pad, max 120dp · send IconButton 44 **accent filled** (arrow_upward, white). Enter sends, Shift+Enter newline. Disabled (offline): 55% opacity, send loses fill, placeholder "Hermes is unreachable".
- Overflow Menu: Rename (Dialog with TextField) · Archive · Copy session ID (toast) · Delete session (red, Dialog danger).
- Toast: `--ink-1` bg, cream text 14sp, 12×16dp pad, 12dp radius, 1.6s.

### 4. Sessions drawer
- 296dp (overlay, phone) / 300dp persistent pane (≥720dp). Raised bg, right hairline. Scrim rgba(39,34,32,.28); slide 200ms `cubic-bezier(.25,.1,.25,1)`.
- Header 20dp pad: mark 24dp + wordmark 22sp + kanji 11sp; TextField "Search sessions".
- Rows (ConversationItem, 44dp, 12dp radius): New chat (add) · Status (monitor_heart) · Cron (schedule); then per profile a DrawerLabel ("DEFAULT PROFILE") + sessions with trailing age caption; selected = `--accent-selected` #C9C2F0 fill (dark #4A4370) + ink text; then "Archived (n)".
- Long-press 480ms (or right-click) → Menu: Rename / Archive (or Unarchive) / Delete (red → Dialog).
- Archived view replaces the list: back row "Archived" + archived sessions with profile as meta.
- Footer (top hairline, 8dp pad): Settings row.

### 5. Cron
- Header: back · "Cron" serif 22sp · spacer · **New job** sm secondary (icon add).
- Rows: icon schedule / pause_circle, name, "human · cron expr" subtitle, next-run meta; paused rows at 60% opacity. Tap or long-press → Menu: Run now (toast) / Pause-Resume / Edit / Delete (red → Dialog).
- Helper caption under list.
- **Edit (full screen)**: close · "Edit job"/"New job" · **Save** sm primary (disabled until name+prompt). Fields 18dp gap: Name, Prompt (textarea 5 rows), Schedule (mono, helper "Cron expression, server time…"), Deliver to chips: Session only / Telegram / Discord.

### 6. Settings
- Header back · "Settings". Content 20dp pad, 28dp between sections; section title serif 17sp 600, optional hint caption.
- Hermes instance: TextField Server URL (mono), Token (mono, helper). 
- Profile chips (default / research / ops) — selection sets `?profile=` everywhere.
- Model chips. Skills + Toolsets: Rows with Switch (44×26 track, 18 thumb, accent when on).
- Pairing: pending PairingCard + paired rows with **Revoke** ghost red sm.
- Diagnostics: Logs → (chevron), Usage card row, Open admin dashboard (open_in_new), *kit-only* "Simulate Hermes unreachable" switch.
- Theme chips Warm / Dark / System (default System) + Material You switch row.

### 7. Logs
- Header back · "Logs". Chips: gateway.log / agent.log · divider · All / Warn+. CodeBlock fills remaining height, 20dp margins, read-only.

### 8. Wide layout (≥720dp — unfolded Z Fold, tablet, web)
- Drawer becomes a persistent 300dp pane; menu icon hidden; content column centered, max 760dp. Nothing else changes.

## Interactions & motion
- Durations: quick 150ms, base 200ms, screen fade 220ms; easing `cubic-bezier(.25,.1,.25,1)`. Status dot pulse 2s. Skeleton pulse 1.6s. No bounces, no scale-on-press.
- Press: 8% ink wash (`rgba(39,34,32,.08)`; dark `rgba(237,232,223,.1)`); hover (desktop) 4%.
- Focus: 2dp accent ring (`0 0 0 2px var(--accent)`).
- Long-press 480ms → bottom-sheet Menu (20dp top radius, 32×4 handle). Destructive → Dialog (absolute within frame, scrim .32, 340dp max, 22dp pad, serif title 19sp).
- Fake agent flow in prototype: send → thinking 1.4s → "Running shell command" 1.2s → ToolRow + streamed reply (70ms/word) → idle.

## State
- `screen`: onboarding | status | chat | cron (+ editJob) | settings | logs. `drawer` open (phone). `wide` from container width.
- `status`: idle | thinking | tool | approval | stopped | offline (+ optional label). `gateway`: running | stopped | restarting.
- `sessions[]` {id, sid, profile, title, meta, archived}; `active`; `messages[]` roles user | assistant(streaming) | tool | thinking | approval | pairing.
- `jobs[]` {id, name, expr, human, next, deliver, prompt, paused}. `pairing` pending request or null. `profile`, `model`, `workspace`, `theme` (Warm | Dark | System), `offline`, `loading`.
- API mapping per screen: see `ui_kits/tsukaima/README.md`.

## Design tokens (light → dark)
Surfaces: page #F7F4ED → #1B1917 · raised #FFFDF7 → #242120 · sunken #EFEAE0 → #141210 · hairline #E6E0D4 → #38332E.
Ink: body #272220 → #EDE8DF · secondary #5D564E → #B5ACA0 · tertiary #746B61 → #978E84.
Accent: #7C6FCF → #A89CFF · pressed #6A5EBF → #BCB2FF · on-accent #FFFFFF → #1B1917 · selected tint #C9C2F0 → #4A4370 · wash/user bubble #EAE6F8 → #322E48.
Semantic: ok #5B8A68 · danger #B3564E · warn #A8823E. Code: bg #272233 → #141218, ink #D8D3E8 → #CFC9E2.
Type: Source Serif 4 (wordmark 28/600, title 22/600, status italic 14/500); Instrument Sans (body 15.5/1.55 400, ui 14/1.4 500, label 11/600 uppercase .08em, caption 12.5/1.4); IBM Plex Mono 13/1.6; Noto Serif JP for 使い魔 only.
Spacing: 4 8 12 16 20 24 32 40 48; page gutter 20. Radii: row 12, input 14, card 16, composer 28, code 10, pill 999. Hit min 44.
Shadow: `0 2px 12px rgba(60,50,40,.10)` (dark `0 2px 14px rgba(0,0,0,.45)`) — composer, approval/pairing cards, menus, dialogs only.

## Assets
- `assets/mark.svg` (+ `-dark`, `-on-accent`, `-ink`), `app-icon.svg/.png` 1024 — the Tsukaima mark (provided by the product owner).
- `assets/resting-wisp.svg` (+ `-dark`) — empty-state line illustration (provided).
- Icons: Material Symbols Rounded, weight 300, 18–24dp. Names used: menu, more_vert, expand_more, add, arrow_upward, arrow_back, close, check, content_copy, terminal, psychology, error, gpp_maybe, link, settings, monitor_heart, schedule, pause_circle, play_circle, play_arrow, restart_alt, refresh, history, chevron_right, dns, stop_circle, forum, chat, memory, folder, edit, archive, unarchive, delete, radio_button_unchecked, receipt_long, bar_chart, open_in_new, cloud_off, send, tag.
- Fonts: Google Fonts stand-ins, all SIL OFL — bundle as TTF.

## Not in the prototype (decided, to implement)
Attach = upload to workspace; push notifications for approvals (actionable), pairing (actionable), long-task done, gateway stopped/unreachable, cron failed; first-run connection test; archived-session read-only banner.

## Files
Prototype: `ui_kits/tsukaima/{index,dark,foldable,web}.html`, `app.jsx`, `*-screen.jsx`, `drawer-overlay.jsx`. Components: `components/{core,chat,chrome}/*`. Tokens: `styles.css`, `tokens/*.css`. Site: `ui_kits/site/index.html`. Note: the kit HTML loads the compiled `_ds_bundle.js` at the project root; serve the whole folder (e.g. `npx serve .`) and open `ui_kits/tsukaima/index.html`.
