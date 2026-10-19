import React from 'react';
export function UserBubble({ children, style }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'flex-end', padding: '4px var(--gutter-page)' }}>
      <div style={{ maxWidth: '82%', background: 'var(--bubble-user)', color: 'var(--bubble-user-ink)', font: 'var(--type-body)', borderRadius: 'var(--radius-card)', borderBottomRightRadius: 6, padding: '10px 14px', ...style }}>{children}</div>
    </div>
  );
}
