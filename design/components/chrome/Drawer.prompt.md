History/profiles/settings drawer. Groups by Hermes profile (not just date) — use the exported `DrawerLabel` for profile headings.

```jsx
<Drawer header={<Wordmark />} footer={<ConversationItem icon="settings" title="Settings" />}>
  <DrawerLabel>Default profile</DrawerLabel>
  <ConversationItem title="Fix the cron job" selected />
</Drawer>
```
