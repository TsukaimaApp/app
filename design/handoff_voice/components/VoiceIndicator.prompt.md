The voice-mode counterpart of the StatusLine dot: a pale-lavender disc that shows five accent bars while listening or speaking (speaking adds two expanding rings), a breathing dot while thinking, and a mic glyph when idle/muted. Flat color only — no gradients, no glow.

```jsx
<VoiceIndicator state="listening" size={180} />
<VoiceIndicator state="speaking" size={72} />
```

Pair it with `StatusLine` for the serif label ("Listening…", flair while thinking, "Speaking").
