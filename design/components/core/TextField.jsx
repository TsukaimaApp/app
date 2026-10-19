import React, { useState } from 'react';
export function TextField({ label, value, onChange, placeholder, helper, type = 'text', mono = false, multiline = false, rows = 4, style }) {
  const [focus, setFocus] = useState(false);
  const base = { font: mono ? 'var(--type-code)' : 'var(--type-body)', color: 'var(--text-body)', background: 'var(--surface-raised)', border: '1px solid var(--border-hairline)', borderRadius: 'var(--radius-input)', padding: '12px 14px', outline: 'none', minHeight: 44, boxSizing: 'border-box', boxShadow: focus ? 'var(--ring-focus)' : 'none', transition: 'box-shadow var(--dur-quick) var(--ease-quiet)', width: '100%', resize: 'vertical' };
  const handlers = { onChange: e => onChange && onChange(e.target.value), onFocus: () => setFocus(true), onBlur: () => setFocus(false) };
  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: 6, ...style }}>
      {label && <span style={{ font: 'var(--type-label)', letterSpacing: '.08em', textTransform: 'uppercase', color: 'var(--text-tertiary)' }}>{label}</span>}
      {multiline ? <textarea rows={rows} value={value} placeholder={placeholder} {...handlers} style={base} /> : <input type={type} value={value} placeholder={placeholder} {...handlers} style={base} />}
      {helper && <span style={{ font: 'var(--type-caption)', color: 'var(--text-tertiary)' }}>{helper}</span>}
    </label>
  );
}
