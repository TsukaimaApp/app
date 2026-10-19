import React from 'react';
export function Drawer({ header, children, footer, width = 300, style }) {
  return (
    <div style={{ width, height: '100%', boxSizing: 'border-box', background: 'var(--surface-raised)', borderRight: '1px solid var(--border-hairline)', display: 'flex', flexDirection: 'column', ...style }}>
      {header && <div style={{ padding: '20px 20px 10px' }}>{header}</div>}
      <div style={{ flex: 1, overflowY: 'auto', padding: '0 8px' }}>{children}</div>
      {footer && <div style={{ borderTop: '1px solid var(--border-hairline)', padding: 8 }}>{footer}</div>}
    </div>
  );
}
export function DrawerLabel({ children }) {
  return <div style={{ font: 'var(--type-label)', letterSpacing: '.08em', textTransform: 'uppercase', color: 'var(--text-tertiary)', padding: '16px 12px 6px' }}>{children}</div>;
}
