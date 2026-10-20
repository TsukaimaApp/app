import React, { useState } from 'react';
import { Icon } from './Icon.jsx';
export function IconButton({ icon, label, accent = false, size = 44, onClick, style }) {
  const [press, setPress] = useState(false);
  return (
    <button aria-label={label} onClick={onClick}
      onPointerDown={() => setPress(true)} onPointerUp={() => setPress(false)} onPointerLeave={() => setPress(false)}
      style={{ width: size, height: size, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', border: 'none', borderRadius: 'var(--radius-pill)', cursor: 'pointer', WebkitTapHighlightColor: 'transparent', transition: 'background var(--dur-quick) var(--ease-quiet)', background: accent ? (press ? 'var(--accent-pressed)' : 'var(--accent)') : (press ? 'var(--wash-press)' : 'transparent'), color: accent ? 'var(--accent-on)' : 'var(--text-secondary)', ...style }}>
      <Icon name={icon} size={size < 44 ? 20 : 24} />
    </button>
  );
}
