import React, { useState, useRef } from 'react';
import { Icon } from '../core/Icon.jsx';
export function ConversationItem({ title, meta, icon, selected, onClick, onLongPress, style }) {
  const [press, setPress] = useState(false);
  const timer = useRef(null);
  const down = () => { setPress(true); if (onLongPress) timer.current = setTimeout(() => { timer.current = null; onLongPress(); }, 480); };
  const up = (fire) => { setPress(false); if (onLongPress) { if (timer.current) { clearTimeout(timer.current); timer.current = null; if (fire && onClick) onClick(); } } else if (fire && onClick) onClick(); };
  return (
    <button onPointerDown={down} onPointerUp={() => up(true)} onPointerLeave={() => up(false)} onContextMenu={e => { if (onLongPress) { e.preventDefault(); onLongPress(); } }}
      style={{ display: 'flex', alignItems: 'center', gap: 10, width: '100%', minHeight: 44, padding: '10px 12px', boxSizing: 'border-box', border: 'none', borderRadius: 'var(--radius-row)', cursor: 'pointer', textAlign: 'left', font: 'var(--type-ui)', transition: 'background var(--dur-quick) var(--ease-quiet)', WebkitTapHighlightColor: 'transparent', userSelect: 'none', background: selected ? 'var(--accent-selected)' : (press ? 'var(--wash-press)' : 'transparent'), color: selected ? 'var(--ink-1)' : 'var(--text-body)', ...style }}>
      {icon && <Icon name={icon} size={20} color="var(--text-secondary)" />}
      <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{title}</span>
      {meta && <span style={{ font: 'var(--type-caption)', color: 'var(--text-tertiary)', flexShrink: 0 }}>{meta}</span>}
    </button>
  );
}
