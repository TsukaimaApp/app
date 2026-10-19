import React from 'react';
import { Row } from './Row.jsx';
export function Menu({ open, title, items = [], onClose }) {
  if (!open) return null;
  return (
    <div onClick={onClose} style={{ position: 'absolute', inset: 0, zIndex: 40, background: 'rgba(39,34,32,.24)', display: 'flex', alignItems: 'flex-end' }}>
      <div onClick={e => e.stopPropagation()} role="menu" style={{ width: '100%', background: 'var(--surface-raised)', borderRadius: '20px 20px 0 0', padding: '10px 8px 18px', boxShadow: 'var(--shadow-float)' }}>
        <div style={{ width: 32, height: 4, borderRadius: 99, background: 'var(--border-hairline)', margin: '0 auto 10px' }}></div>
        {title && <div style={{ font: 'var(--type-label)', letterSpacing: '.08em', textTransform: 'uppercase', color: 'var(--text-tertiary)', padding: '4px 12px 8px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{title}</div>}
        {items.map((it, i) => <Row key={i} icon={it.icon} title={it.label} danger={it.danger} onClick={() => { onClose && onClose(); it.onClick && it.onClick(); }} />)}
      </div>
    </div>
  );
}
