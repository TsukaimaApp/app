import React from 'react';
import { Button } from '../core/Button.jsx';
import { Icon } from '../core/Icon.jsx';
export function ApprovalCard({ title = 'Hermes wants to run a tool', toolName, command, onApprove, onDeny, style }) {
  return (
    <div style={{ margin: '8px var(--gutter-page)', background: 'var(--surface-raised)', border: '1px solid var(--border-hairline)', borderRadius: 'var(--radius-card)', boxShadow: 'var(--shadow-float)', padding: 16, ...style }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <Icon name="gpp_maybe" size={20} color="var(--status-warn)" />
        <span style={{ font: 'var(--type-status)', color: 'var(--text-body)' }}>{title}</span>
      </div>
      {toolName && <div style={{ font: 'var(--type-label)', letterSpacing: '.08em', textTransform: 'uppercase', color: 'var(--text-tertiary)', marginBottom: 6 }}>{toolName}</div>}
      {command && <pre style={{ margin: '0 0 14px', background: 'var(--code-bg)', color: 'var(--code-ink)', font: 'var(--type-code)', borderRadius: 'var(--radius-code)', padding: '10px 12px', overflowX: 'auto' }}>{command}</pre>}
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <Button danger onClick={onDeny}>Deny</Button>
        <Button variant="primary" icon="check" onClick={onApprove}>Approve</Button>
      </div>
    </div>
  );
}
