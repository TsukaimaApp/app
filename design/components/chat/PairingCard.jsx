import React from 'react';
import { Button } from '../core/Button.jsx';
import { Icon } from '../core/Icon.jsx';
export function PairingCard({ platform = 'Telegram', who, code, onApprove, onRevoke, style }) {
  return (
    <div style={{ margin: '8px var(--gutter-page)', background: 'var(--surface-raised)', border: '1px solid var(--border-hairline)', borderRadius: 'var(--radius-card)', boxShadow: 'var(--shadow-float)', padding: 16, ...style }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <Icon name="link" size={20} color="var(--status-warn)" />
        <span style={{ font: 'var(--type-status)', color: 'var(--text-body)' }}>Pairing request from {platform}</span>
      </div>
      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 12, marginBottom: 14 }}>
        <span style={{ font: 'var(--type-ui)', color: 'var(--text-secondary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{who}</span>
        <span style={{ font: 'var(--type-code)', fontSize: 20, letterSpacing: '.18em', color: 'var(--text-body)' }}>{code}</span>
      </div>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <Button danger onClick={onRevoke}>Revoke</Button>
        <Button variant="primary" icon="check" onClick={onApprove}>Approve</Button>
      </div>
    </div>
  );
}
