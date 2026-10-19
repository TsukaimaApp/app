import React, { useState } from 'react';
import { Icon } from './Icon.jsx';
export function Chip({ label, icon, trailingIcon, selected, onClick, style }) {
  const [press, setPress] = useState(false);
  return (
    <button onClick={onClick}
      onPointerDown={() => setPress(true)} onPointerUp={() => setPress(false)} onPointerLeave={() => setPress(false)}
      style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 12px', minHeight: 36, borderRadius: 'var(--radius-pill)', font: 'var(--type-ui)', cursor: 'pointer', WebkitTapHighlightColor: 'transparent', transition: 'background var(--dur-quick) var(--ease-quiet)', background: selected ? 'var(--accent-selected)' : (press ? 'var(--wash-press)' : 'transparent'), color: selected ? 'var(--ink-1)' : 'var(--text-secondary)', border: '1px solid ' + (selected ? 'transparent' : 'var(--border-hairline)'), ...style }}>
      {icon && <Icon name={icon} size={18} />}{label}{trailingIcon && <Icon name={trailingIcon} size={18} />}
    </button>
  );
}
