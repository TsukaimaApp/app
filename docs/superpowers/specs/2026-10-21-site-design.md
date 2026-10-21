# Tsukaima project site — design

**Date:** 2026-10-21. **Status:** approved in chat (the user: "Update the site"; the three links are theirs, the rest are the author's calls, marked below).

## Intent

Ship tsukaima.app: the single-page project site that the design handoff already specifies (`design/ui_kits/site/index.html`), brought in line with the app as shipped (0.9.2) and made deployable. The site tells someone who runs a Hermes Agent what Tsukaima is, shows them how it feels, and gives them three ways in: download the APK, add it to Obtainium, read the source.

The kit page predates the build. It is the design, not the content: its layout, voice and look stay; its copy, demos and plumbing change.

## Who it is for

A person who already self-hosts Hermes Agent (Nous Research) and wants it on their phone. They are technical, they distrust middlemen, and they read. The page should earn their trust in one scroll: what it does, what it does not do (no account, no server, no telemetry), and where the code is.

## Goals

1. Accurate: every claim on the page is true of Tsukaima 0.9.2 as described in `README.md` and `CHANGELOG.md`. Nothing invented, nothing promised.
2. Faithful to the design: same structure, tokens, type, mark, resting-wisp, voice (sentence case, no exclamation marks, no emoji, serif where the kit uses serif).
3. Self-contained: no runtime framework, no CDN, no third-party request of any kind (fonts and the Obtainium badge are hosted with the page). The site says "no third-party service"; it must be one.
4. Deployable from the app repo to GitHub Pages at `tsukaima.app`, by a workflow, with nothing manual beyond enabling Pages and DNS when the repo goes public.
5. Works on a phone: readable at 360 px wide, no horizontal scroll, 44 px targets.

## Non-goals

- Dark mode (the kit page is light-only, the brand statement is cream; deferred). *(author's call)*
- Docs, changelog page, blog, screenshots gallery, localisation, analytics.
- A release workflow for the APK (the download link points at GitHub Releases; publishing releases is a separate change to the release recipe).
- Re-touching the design kit under `design/` — it stays the reference; the site is a new tree.

## Anti-goals (what would make it a failure even if it renders)

- Copy that describes features the app does not have (the kit's SSE, token sign-in, web build, foldable pane, "Hermes 4 · 405B").
- A page that loads React, Babel, Google Fonts or Material Symbols from a CDN.
- A page that only works with the three links clickable — the links point at a repo that is private today; the page must not break (no fetches of release data, no badges from shields.io).
- Losing the design's restraint: gradients, imagery, bounces, exclamation marks, emoji.

## Constraints

- Location: `site/` at the repo root. Published as-is (static files, no build step for HTML/CSS; fonts are prepared once and committed).
- Links (the user's decision):
  - **Download APK** → `https://github.com/TsukaimaApp/app/releases/latest`
  - **View source** → `https://github.com/TsukaimaApp/app`
  - **Get it on Obtainium** → `obtainium://add/https://github.com/TsukaimaApp/app`, rendered with the official badge (`assets/graphics/badge_obtainium.png` from the Obtainium repo, hosted in `site/`, shown at ~161×48, its own aspect ratio). Its caption: "Opens in Obtainium on your phone" with a plain link to `https://github.com/ImranR98/Obtainium` for people who do not have it.
  - Footer: Source · Releases · Issues → the repo, `/releases`, `/issues`. Hermes Agent → `https://github.com/NousResearch/hermes-agent`.
- Facts to use verbatim: Android 10+ (minSdk 29); APK "about 6 MB" (0.9.2 release build is 6.1 MB); version 0.9.2 where a version is named; sign-in is the Hermes dashboard's username and password; transport is Hermes' JSON-RPC WebSocket (`/api/ws`); Kotlin + Jetpack Compose, Material 3; light and dark themes with Material You optional; tablet/foldable wide layout exists (two-pane); there is no web build and no iOS version.
- Fonts: Source Serif 4 (regular + italic), Instrument Sans, IBM Plex Mono, Noto Serif JP (the 3-glyph subset) from `app/src/main/res/font/`, subset to the characters the page uses (Latin + punctuation + the glyphs in the page, e.g. "…", "·", "—", "→") and converted to woff2, served from `site/fonts/` with `font-display: swap` and the SIL OFL licence text alongside. No `@import` of Google Fonts. *(author's call: subset, not full files — the serif alone is 2 MB as TTF)*
- Icons: inline SVG, a handful (menu, expand, check, terminal, psychology/thinking, shield for approval). No icon font.
- Scripts: plain vanilla JS, inline, under ~3 kB, only for (a) the hero status line cycling through thinking-flair verbs, paused under `prefers-reduced-motion`, and (b) the approval-card demo's Approve/Deny toggle. The page is complete without JS.
- CI: `.github/workflows/pages.yml` deploys `site/` with `actions/upload-pages-artifact` + `actions/deploy-pages` on pushes to `main` that touch `site/**` (and on manual dispatch). `.github/workflows/android.yml` gets `paths-ignore: [site/**, docs/**, design/**]` so site commits do not rebuild the app. `site/CNAME` contains `tsukaima.app`. The workflow will fail with a clear error until Pages is enabled; that is expected and documented in `site/README.md`.
- Head: title "Tsukaima — a familiar for Hermes Agent", meta description, canonical `https://tsukaima.app/`, Open Graph + Twitter card with a 1200×630 `og.png` (cream, mark lockup, one line) and the app icon as favicon (`favicon.svg` from `design/assets/app-icon.svg`, plus a 180 px PNG for Apple), `theme-color` `#F7F4ED`, `lang="en"`.
- Accessibility: landmarks (`header`, `main`, `section` with headings, `footer`), one `h1`, alt text on the mark ("Tsukaima") and empty alt on decoration, visible focus rings (`--ring-focus`), contrast as per tokens, `prefers-reduced-motion` stops the dot pulse and the verb cycling.

## The page, section by section

Structure as the kit, with these changes. Copy below is the direction; the builder writes final lines in the kit's voice and keeps the kit's lines wherever they are still true.

1. **Nav**: mark + "Tsukaima" + 使い魔 lockup; links How it works · Features · Voice · Self-hosted · FAQ · GitHub.
2. **Hero**: status line cycling flair verbs ("Summoning…", "Charging ki…", "Reading the scroll…", "Steeping matcha…", "Folding cranes…", "Binding the contract…" — from the kit's list, never the watchlist set) with the pulsing dot; `h1` "A familiar for your Hermes Agent."; lede as the kit but ending "…from your pocket, talking straight to your own instance." CTAs: Download APK (primary) · View source (secondary) · the Obtainium badge; small print "Android 10+ · about 6 MB · no account". Right: a **static phone mock** of the chat screen (status line "Running shell command", a user bubble, a collapsed tool row, an assistant reply with a short code block, the Tasks card with two items, the composer pill) built from the site's CSS — not an iframe, not a screenshot (none exist; nothing has run on a device yet).
3. **How it works** — three steps: (01) *Point it at your instance.* Enter the server URL and sign in with your Hermes dashboard username and password, over LAN, Tailscale or any tunnel you already trust. (02) *Pick a profile and a model.* Every Hermes profile shows up with its own chats; switch the model from the chip in the top bar, mid-conversation if you like. (03) *Talk, watch, approve.* As the kit.
4. **Features** (intro as the kit), four alternating blocks with static demos:
   - *The status line is the app.* As the kit, plus the context meter ("42% ctx · 12.3k tok"). Demo: four status lines (idle, thinking, running a tool, waiting for approval).
   - *Nothing runs without you.* As the kit, plus "Approve for this session or always, when your server allows it", and actionable notifications. Demo: the approval card, Approve/Deny toggling to a tool row + reply (vanilla JS).
   - *Prose, not bubbles.* As the kit, plus tables, task-list checkboxes and math, and the agent's todo list as a live Tasks card. Demo: user bubble, thinking row, tool row with a log line, reply with a code block, a small Tasks card.
   - *One agent, many hats.* As the kit (profiles in the drawer; no tab bar), plus server-side search, archive, cron jobs. Demo: the drawer with two profile groups.
5. **Voice** (replaces the kit's "Unfolded · tablet · web" section): label "Voice", `h2` "Say it instead.", sub: voice mode listens, sends what it hears, reads replies aloud sentence by sentence as they stream; tap the orb to interrupt; set Tsukaima as the device assistant and long-press home for one exchange; speech can be processed on the phone or on Hermes. Approvals still need a tap. Three "fact" cards: On the phone / On Hermes / Device assistant.
6. **Self-hosted**: `h2` "No account. No middleman."; paragraph as the kit but: the sign-in is the dashboard account; credentials live in Android's encrypted storage and go only to your Hermes. The terminal snippet becomes a Settings mock (Server URL `https://hermes.your-tailnet.ts.net` · Username · Password ········ · "Connected · Hermes 0.22"). Facts: Single user · Live over the WebSocket ("Replies, tool events and approval requests stream over Hermes' own JSON-RPC WebSocket — nothing is polled.") · Material 3, warm by default (light and dark, Material You optional) · Open source (Kotlin + Jetpack Compose; build it yourself or install the signed release from GitHub).
7. **FAQ**: keep the kit's six, with: "Will there be an iOS version?" → "Not planned. Tsukaima is Android-first and leans on Material 3." (drop the web-build sentence); add "How do I get updates?" → Obtainium watches the GitHub releases; or download the next APK from Releases; the app does not update itself. Add "Which Android versions?" → Android 10 and newer.
8. **Haiku** block with the resting-wisp, as the kit.
9. **Final CTA**: "Bind your familiar." with Download APK · Get it on Obtainium.
10. **Footer**: as the kit ("not affiliated with Nous"), links Source · Releases · Issues.

## Rulings from the builder check (author's calls unless marked)

- **Voice wording.** On-phone recognition uses Android's platform recogniser and text-to-speech (on-device where the system supports it, otherwise the device's default recogniser, which may be a vendor service). The page says exactly that and never claims the phone path is offline or vendor-free; the "no telemetry, no third-party service" claim is about Tsukaima itself (the app and this site make no calls of their own). Card copy: *On the phone* — "Android's own speech recogniser and text-to-speech, on-device where the system supports it." *On Hermes* — "One utterance at a time, uploaded as Opus to your Hermes, transcribed and spoken with the engines you configured there." *Device assistant* — "Long-press home for one exchange; anything that needs approval opens in the app."
- **Links while the repo is private.** The three links are the final URLs, with no interim state: the site cannot be served from Pages until the repo is public, and by then Releases exist. The page assumes each release carries the signed APK as an asset; `site/README.md` says so, next to the Pages/DNS steps.
- **Branch.** `main` already contains the app (merged 2026-10-21). The site is built on `feat/site` off `main`; the Pages workflow deploys from `main` after the branch is integrated.
- **Hermes version in the Settings mock.** "Connected · Hermes 0.22.2" — the version the app was built and tested against.
- **Obtainium badge.** Committed under `site/img/` as published by the Obtainium project for this purpose; attribution (project, repo URL, GPL-3.0) in `site/README.md` and in an HTML comment next to the `<img>`. No footer line.
- **FAQ.** Every kit answer is audited against `README.md` and `CHANGELOG.md`; any false sentence is rewritten in the kit's voice (the token/SSE/web-build mentions go), the true ones stay verbatim.

## What is left to the builder

- Final copy lines within the direction above; keep the kit's sentences where true.
- Exact CSS for the static demos and the phone mock (use the kit's component CSS as the reference: `design/components/**/*.jsx` and `design/ui_kits/tsukaima/*.jsx` carry the values).
- Font subsetting command and character set; woff2 only is fine (every Android 10+/modern browser supports it).
- The og.png and PNG favicon production (render from HTML with the headless Chromium installed on the VM, or any equivalent).
- Verification: an HTML validator pass, a check that no `http(s)://` host other than github.com (TsukaimaApp, ImranR98, NousResearch) and tsukaima.app appears in `site/`, and screenshots at 1280 and 390 px widths for the user to look at.

## Open to the user (not blocking)

- The repo has no `LICENSE` file. The site says "open source"; it does not name a licence. Pick one before the repo goes public.
- Pages and DNS: when the repo goes public, enable Pages (Source: GitHub Actions) and point `tsukaima.app` at GitHub Pages (A/AAAA records or a CNAME to `tsukaimaapp.github.io`); the workflow then deploys on the next push to `site/`.
