import React, { useState } from 'react';
import { Icon } from './Icon.jsx';
export function Button({ variant = 'secondary', size = 'md', icon, disabled, danger, children, onClick, style }) {
  const [press, setPress] = useState(false);
  const pad = size === 'sm' ? '8px 16px' : '12px 22px';
  const font = size === 'sm' ? '600 13px/1 var(--font-sans)' : '600 14.5px/1 var(--font-sans)';
  const base = {
    display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 8,
    padding: pad, minHeight: size === 'sm' ? 36 : 44, boxSizing: 'border-box',
    borderRadius: 'var(--radius-pill)', font, cursor: disabled ? 'default' : 'pointer',
    border: '1px solid transparent', transition: 'background var(--dur-quick) var(--ease-quiet)',
    opacity: disabled ? 0.45 : 1, userSelect: 'none', WebkitTapHighlightColor: 'transparent',
  };
  const variants = {
    primary: { background: press ? 'var(--accent-pressed)' : 'var(--accent)', color: 'var(--accent-on)' },
    secondary: { background: press ? 'var(--wash-press)' : 'var(--surface-raised)', color: danger ? 'var(--status-danger)' : 'var(--text-body)', borderColor: 'var(--border-hairline)' },
    ghost: { background: press ? 'var(--wash-press)' : 'transparent', color: danger ? 'var(--status-danger)' : 'var(--text-secondary)' },
  };
  return (
    <button disabled={disabled} onClick={onClick} style={{ ...base, ...variants[variant], ...style }}
      onPointerDown={() => setPress(true)} onPointerUp={() => setPress(false)} onPointerLeave={() => setPress(false)}>
      {icon && <Icon name={icon} size={size === 'sm' ? 18 : 20} />}{children}
    </button>
  );
}
