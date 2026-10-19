The agent's live status — a first-class element, not chrome. Serif italic line with a dot: lavender pulsing while thinking/running, amber while waiting for approval.

```jsx
<StatusLine state="thinking" />                              // cycles flair verbs every 2.6s
<StatusLine state="tool" label="Running shell command" />
<StatusLine state="thinking" flair={false} />                // plain "Thinking…"
```

A second set, `THINKING_FLAIR_WATCHLIST`, holds one allusive line per title the maintainer rated 9+ on MyAnimeList (Monogatari, Madoka, Steins;Gate, Code Geass, Toradora, Black Lagoon, Konosuba, …) — e.g. "Tuning the phone microwave…", "Walking the tiger home…", "Casting Explosion, once a day…". Both sets cycle by default.

Thinking flair (exported as `THINKING_FLAIR`, Japanese pop-culture energy, kept quiet in serif italic): Summoning…, Charging ki…, Powering up…, Channeling…, Reading the scroll…, Consulting the shrine…, Steeping matcha…, Folding cranes…, Sharpening the blade…, Drawing the seal…, Chanting…, Gathering spirit energy…, Training arc…, Unlocking the next form…, Meditating under the waterfall…, Rolling the gacha…, Inking the next panel…, Grinding XP…, Binding the contract…
