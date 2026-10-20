import React, { useState } from 'react';
import { Icon } from '../core/Icon.jsx';
export function ToolRow({ kind = 'tool', name, detail, status = 'done', defaultOpen = false, children, style }) {
  const [open, setOpen] = useState(defaultOpen);
  const icon = kind === 'thinking' ? 'psychology' : 'terminal';
  const statusIcon = status === 'running' ? null : status === 'error' ? 'error' : 'check';
  return (
    <div style={{ margin: '6px var(--gutter-page)', border: '1px solid var(--border-hairline)', borderRadius: 'var(--radius-row)', background: 'var(--surface-raised)', overflow: 'hidden', ...style }}>
      <button onClick={() => setOpen(!open)} style={{ display: 'flex', alignItems: 'center', gap: 10, width: '100%', minHeight: 44, padding: '8px 12px', background: 'none', border: 'none', cursor: 'pointer', font: 'var(--type-ui)', color: 'var(--text-secondary)', textAlign: 'left', boxSizing: 'border-box' }}>
        <Icon name={icon} size={18} color={status === 'running' ? 'var(--accent)' : 'var(--text-tertiary)'} />
        <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{name}{detail && <span style={{ color: 'var(--text-tertiary)' }}> · {detail}</span>}</span>
        {statusIcon && <Icon name={statusIcon} size={16} color={status === 'error' ? 'var(--status-danger)' : 'var(--text-tertiary)'} />}
        <Icon name="expand_more" size={18} style={{ transform: open ? 'rotate(180deg)' : 'none', transition: 'transform var(--dur-base) var(--ease-quiet)' }} />
      </button>
      {open && <div style={{ padding: '0 12px 12px', font: 'var(--type-caption)', color: 'var(--text-secondary)' }}>{children}</div>}
    </div>
  );
}
