# Handoff: Tsukaima voice mode (in-app + Android assistant slot)

Scope of this package: the two voice surfaces only. The full design system and app spec live in the main Tsukaima handoff; this folder is self-contained enough to implement voice without it.

## What to build
1. **In-app voice mode** — full-screen, entered from the chat composer.
2. **Android assistant slot** — Tsukaima registered as the device assist app (`RoleManager.ROLE_ASSISTANT` / `VoiceInteractionService`), rendered as a sheet over the current app.

Stack assumed: Kotlin + Jetpack Compose, Material 3. 1 px in these files = 1 dp; font sizes are sp. Values are final — do not snap to a grid.

## Files
Reference JSX is saved as `.jsx.txt` (read-only source for values and behaviour; the runnable prototype uses the compiled bundle).
- `prototype/voice.html` — in-app voice, autoplaying demo (listening → thinking → speaking → approval card → approve/deny → spoken result). Open in a browser; serve the folder (`npx serve .`) so the bundle loads.
- `prototype/assist.html` — assistant-slot sheet over a stand-in app, one exchange.
- `prototype/voice-screen.jsx` — reference logic for both screens (`VoiceScreen`, `AssistOverlay`, the demo scripting, phase → status-line mapping).
- `components/VoiceIndicator.jsx.txt` + `.d.ts` + `.prompt.md` — the presence orb, exact geometry and timings.
- `components/Composer.jsx.txt` — mic ↔ send swap in the composer.
- `components/StatusLine.jsx.txt` — the serif status line incl. thinking flair verbs.
- `components/{ApprovalCard,UserBubble,AssistantMessage,ToolRow,IconButton,Chip,Button,Icon}.jsx.txt` — everything the two screens compose.
- `tokens/*.css`, `styles.css` — colors (light + dark), type, spacing, radii, shadows.
- `assets/mark.svg`, `mark-dark.svg` — the brand mark used in the assist sheet header.

## Entry point: composer mic
The composer's accent circle (44dp, `--accent` fill, white icon) shows **mic** while the text field is empty and swaps to **arrow_upward** (send) once there is text. Tapping mic opens voice mode. Still only two controls in the composer — attach and this one.

## Screen A — in-app voice (`VoiceScreen`)
Page background `--surface-page` (#F7F4ED light / #1B1917 dark). Column layout.

**Top row** (8dp pad, 4dp gap): IconButton `expand_more` 44dp (collapse back to chat) · flexible spacer · borderless model Chip (label 14sp/500 + `expand_more` 18dp) · spacer · 44dp blank to keep the chip centered.

**Center column** (scrolls, items centered, 4vh top pad):
- `VoiceIndicator` size 180.
- `StatusLine` centered, 16sp Source Serif 4 italic 500, `--text-secondary`, 7dp dot 8dp before text. Phase → line:
  - listening → lavender dot pulsing (2s, opacity 1→.35), "Listening…"
  - thinking → lavender pulsing, label cycles the flair verbs every 2.6s with a 180ms fade (list in `StatusLine.jsx`: `THINKING_FLAIR` + `THINKING_FLAIR_WATCHLIST`)
  - speaking → lavender pulsing, "Speaking"
  - approval → amber #A8823E dot, static, "Waiting for your approval"
  - muted → grey dot, "Muted"
- Transcript, 22dp below, full width, **same components as chat**:
  - recognised speech → `UserBubble` (right, max 82%, `--bubble-user` #EAE6F8 / dark #322E48, 16dp radius with 6dp bottom-right, 10×14dp pad, 15.5sp/1.55 sans). Text appears word by word as recognition finalises.
  - spoken reply → `AssistantMessage` (flush left, 20dp gutters, max 640dp, no bubble) with `streaming` caret: 2dp × 1em `--accent`, blink 1s steps(2). Text is appended as TTS speaks it.
  - tool call → `ToolRow` (hairline, 12dp radius, 44dp header).
  - approval → `ApprovalCard` (raised, hairline, 16dp radius, shadow `0 2px 12px rgba(60,50,40,.10)`, 16dp pad; `gpp_maybe` amber + serif italic title; command in code well; **Deny** outlined red ink, **Approve** filled accent with check). **Voice never bypasses approval** — the agent asks aloud ("Want me to?"), the user's "yes" is recognised, then the card appears and waits for a tap. Phase = approval until resolved.
- Keep the newest item in view (scroll the overflow container to bottom on every transcript/phase change).

**Controls row** (12dp top / 20dp sides / 28dp bottom; 28dp gaps, centered):
- Mute: 56dp circle, `--surface-raised`, 1dp `--border-hairline`, icon `mic` / `mic_off` 24dp `--text-body`. Muted while listening → indicator shows muted state, line says "Muted".
- End: 64dp circle, `--ink-1` #272220 fill (dark: `--night-ink-1`), `close` icon in `--cream-0`. Returns to chat; transcript is already in the session.
- Keyboard: 56dp like Mute, icon `keyboard`. Back to chat with the composer focused.

## Screen B — Android assistant slot (`AssistOverlay`)
Shown over whatever app is in front; the host app is dimmed with scrim rgba(39,34,32,.38).

**Sheet** anchored bottom, full width: `--surface-raised`, top radii 28dp, shadow `0 2px 12px rgba(60,50,40,.10)` (dark `0 2px 14px rgba(0,0,0,.45)`), padding 10dp top / 20dp sides / 10dp bottom, 10dp vertical gaps between blocks.
1. Handle 32×4dp, `--border-hairline`, centered.
2. Header row (8dp gap): mark 18dp (`mark.svg` / `mark-dark.svg`) · "Tsukaima" Source Serif 4 600 17sp `--text-body` · spacer · IconButton `keyboard` 40dp · IconButton `open_in_new` 40dp (opens the full app on this session).
3. Body row (6dp gap): `VoiceIndicator` size 72 · column: `StatusLine` (no padding, 14sp) over the latest recognised utterance (15.5sp sans `--text-body`, 4dp top margin).
4. Results, in order: `ToolRow` (margin 0) for any tool the agent ran, `AssistantMessage` (padding 0, streaming while spoken).
5. Footer, right-aligned: ghost small Button "Continue in Tsukaima" (36dp min height, 13sp/600).
6. Gesture bar 108×4dp, `--ink-1` at 40%, centered, 2dp top margin.

Behaviour: one exchange per invocation. After the reply finishes, phase → idle (grey dot, "Tap to speak"; indicator shows a lavender `mic` glyph). Tapping the indicator or sheet body listens again. Anything that needs approval is **not** handled in the sheet — say "That needs your approval — I've opened it in Tsukaima" and launch the app on the session. Swipe down or tap outside dismisses.

## VoiceIndicator geometry (size S; disc = 0.62·S)
- Disc: circle, `--accent-wash` (#EAE6F8 / dark #322E48); muted → `--surface-sunken`.
- listening / speaking: 5 bars, width max(3, 0.04·S), radius pill, `--accent`, gap max(3, 0.035·S); heights = disc × (0.18 + a·0.34) for a = [.3, .65, 1, .65, .3]; each animates scaleY .35→1→.35 (ease-in-out, infinite), period 1.4s listening / 0.9s speaking, stagger 130ms per bar. Drive amplitude from the mic/TTS level in production if cheap; the loop is the fallback.
- speaking adds 2 rings: disc-sized, 1.5dp `--accent-selected` (#C9C2F0 / dark #4A4370) stroke, scale .72→1.3 while opacity .7→0 over 2.4s, second ring offset 1.2s.
- thinking: single dot 0.07·S `--accent`, opacity pulse 2s; disc breathes scale .96↔1.04 over 2.4s.
- idle: `mic` glyph 0.2·S `--accent`. muted: `mic_off` 0.2·S `--text-tertiary`.
- Flat color only. No gradients, glow, blur or shadow on the indicator.

## Motion & easing
`cubic-bezier(.25,.1,.25,1)`; screen enter/exit 220ms fade; nothing bounces or scales on press. Press feedback is an 8% ink wash.

## Tokens used (light → dark)
page #F7F4ED→#1B1917 · raised #FFFDF7→#242120 · sunken #EFEAE0→#141210 · hairline #E6E0D4→#38332E · ink-1 #272220→#EDE8DF · ink-2 #5D564E→#B5ACA0 · ink-3 #746B61→#978E84 · accent #7C6FCF→#A89CFF · accent-wash/user bubble #EAE6F8→#322E48 · accent-selected #C9C2F0→#4A4370 · warn #A8823E · danger #B3564E · cream-0 #FFFDF7.
Fonts: Source Serif 4 (status, wordmark, approval title), Instrument Sans (everything else), Material Symbols Rounded weight 300 for icons (`mic`, `mic_off`, `keyboard`, `close`, `expand_more`, `open_in_new`, `gpp_maybe`, `check`, `terminal`, `arrow_upward`).

## Not in the prototype (decide in implementation)
Hotword/wake handling, STT/TTS engine choice, barge-in (user interrupting speech), haptics on phase change, assistant-slot behaviour on lock screen.
