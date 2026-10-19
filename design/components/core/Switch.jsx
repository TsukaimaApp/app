import React from 'react';
export function Switch({ checked, onChange, label, style }) {
  return (
    <label style={{ display: 'inline-flex', alignItems: 'center', gap: 12, cursor: 'pointer', font: 'var(--type-ui)', color: 'var(--text-body)', minHeight: 44, ...style }}>
      {label}
      <span role="switch" aria-checked={!!checked} onClick={() => onChange && onChange(!checked)}
        style={{ width: 44, height: 26, borderRadius: 99, padding: 3, boxSizing: 'border-box', background: checked ? 'var(--accent)' : 'var(--surface-sunken)', border: '1px solid ' + (checked ? 'var(--accent)' : 'var(--border-hairline)'), transition: 'background var(--dur-base) var(--ease-quiet)', display: 'inline-flex', justifyContent: checked ? 'flex-end' : 'flex-start' }}>
        <span style={{ width: 18, height: 18, borderRadius: 99, background: checked ? 'var(--accent-on)' : 'var(--text-tertiary)', transition: 'all var(--dur-base) var(--ease-quiet)' }}></span>
      </span>
    </label>
  );
}
