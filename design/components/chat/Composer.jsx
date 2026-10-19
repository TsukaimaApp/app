import React, { useState } from 'react';
import { IconButton } from '../core/IconButton.jsx';
export function Composer({ value, onChange, onSend, onAttach, placeholder = 'Message your agent', disabled = false, style }) {
  const [inner, setInner] = useState('');
  const v = value !== undefined ? value : inner;
  const set = onChange || setInner;
  const send = () => { if (disabled) return; if (v.trim() && onSend) onSend(v); if (value === undefined) setInner(''); };
  return (
    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 4, background: 'var(--surface-raised)', border: '1px solid var(--border-hairline)', borderRadius: 'var(--radius-composer)', boxShadow: 'var(--shadow-float)', padding: 6, boxSizing: 'border-box', opacity: disabled ? .55 : 1, ...style }}>
      <IconButton icon="add" label="Attach" onClick={disabled ? undefined : onAttach} />
      <textarea rows={1} value={v} disabled={disabled} placeholder={disabled ? 'Hermes is unreachable' : placeholder} onChange={e => set(e.target.value)}
        onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
        style={{ flex: 1, resize: 'none', border: 'none', outline: 'none', background: 'transparent', font: 'var(--type-body)', color: 'var(--text-body)', padding: '11px 4px', maxHeight: 120 }} />
      <IconButton icon="arrow_upward" label="Send" accent={!disabled} onClick={send} />
    </div>
  );
}
