import React, { useState } from 'react';
import { Icon } from './Icon.jsx';
export function Row({ icon, title, subtitle, meta, trailing, trailingIcon, onClick, onLongPress, selected, danger, style }) {
  const [press, setPress] = useState(false);
  const timer = React.useRef(null);
  const down = () => { setPress(true); if (onLongPress) timer.current = setTimeout(() => { timer.current = null; onLongPress(); }, 480); };
  const up = (fire) => { setPress(false); if (timer.current) { clearTimeout(timer.current); timer.current = null; if (fire && onClick) onClick(); } else if (!onLongPress && fire && onClick) onClick(); };
  const Tag = onClick || onLongPress ? 'button' : 'div';
  return (
    <Tag onPointerDown={down} onPointerUp={() => up(true)} onPointerLeave={() => up(false)} onContextMenu={e => { if (onLongPress) { e.preventDefault(); onLongPress(); } }}
      style={{ display: 'flex', alignItems: 'center', gap: 12, width: '100%', minHeight: 52, padding: '10px 12px', boxSizing: 'border-box', border: 'none', borderRadius: 'var(--radius-row)', cursor: onClick || onLongPress ? 'pointer' : 'default', textAlign: 'left', font: 'var(--type-ui)', fontWeight: 500, transition: 'background var(--dur-quick) var(--ease-quiet)', WebkitTapHighlightColor: 'transparent', userSelect: 'none', background: selected ? 'var(--accent-selected)' : (press ? 'var(--wash-press)' : 'transparent'), color: danger ? 'var(--status-danger)' : 'var(--text-body)', ...style }}>
      {icon && <Icon name={icon} size={22} color={danger ? 'var(--status-danger)' : 'var(--text-secondary)'} />}
      <span style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 2 }}>
        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{title}</span>
        {subtitle && <span style={{ font: 'var(--type-caption)', color: 'var(--text-tertiary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{subtitle}</span>}
      </span>
      {meta && <span style={{ font: 'var(--type-caption)', color: 'var(--text-tertiary)', flexShrink: 0 }}>{meta}</span>}
      {trailing}
      {trailingIcon && <Icon name={trailingIcon} size={20} color="var(--text-tertiary)" />}
    </Tag>
  );
}
