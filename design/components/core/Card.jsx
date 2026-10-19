import React from 'react';
export function Card({ floating = false, padding = 16, children, style }) {
  return (
    <div style={{ background: 'var(--surface-raised)', borderRadius: 'var(--radius-card)', border: '1px solid var(--border-hairline)', boxShadow: floating ? 'var(--shadow-float)' : 'none', padding, boxSizing: 'border-box', ...style }}>
      {children}
    </div>
  );
}
