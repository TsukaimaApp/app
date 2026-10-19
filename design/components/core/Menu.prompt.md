Bottom-sheet context menu opened by long-press on a Row/ConversationItem. Destructive items in red ink, last.

```jsx
<Menu open={!!target} title={target?.title} onClose={() => setTarget(null)}
  items={[{ icon: 'edit', label: 'Rename', onClick: rename }, { icon: 'archive', label: 'Archive' }, { icon: 'delete', label: 'Delete', danger: true, onClick: askDelete }]} />
```
