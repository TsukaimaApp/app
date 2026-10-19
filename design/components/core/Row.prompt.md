Two-line list row with optional icon, meta, trailing control, and long-press (480ms / right-click) for context menus. Used for settings, cron jobs and status facts.

```jsx
<Row icon="schedule" title="Nightly backup" subtitle="0 3 * * * · next 03:00" meta="paused" onLongPress={openMenu} />
<Row title="Dynamic color" trailing={<Switch checked={on} onChange={setOn} />} />
```
