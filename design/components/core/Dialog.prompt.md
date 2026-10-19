Confirm dialog for destructive or gateway actions. Serif title, sans body, ghost cancel + primary (or red-ink `danger`) confirm.

```jsx
<Dialog open={ask} danger title="Delete session?" body="This can't be undone." confirmLabel="Delete" onConfirm={del} onCancel={() => setAsk(false)} />
```
