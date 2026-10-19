import React from 'react';
import { Button } from './Button.jsx';
export function Dialog({ open, title, body, confirmLabel = 'Confirm', cancelLabel = 'Cancel', danger, onConfirm, onCancel }) {
  if (!open) return null;
  return (
    <div onClick={onCancel} style={{ position: 'absolute', inset: 0, zIndex: 40, background: 'rgba(39,34,32,.32)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 28 }}>
      <div onClick={e => e.stopPropagation()} role="alertdialog" style={{ width: '100%', maxWidth: 340, background: 'var(--surface-raised)', borderRadius: 'var(--radius-card)', padding: 22, boxShadow: 'var(--shadow-float)' }}>
        <div style={{ font: 'var(--type-title)', fontSize: 19, color: 'var(--text-body)', marginBottom: 8 }}>{title}</div>
        {body && <div style={{ font: 'var(--type-body)', fontSize: 14.5, color: 'var(--text-secondary)', marginBottom: 20 }}>{body}</div>}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <Button variant="ghost" onClick={onCancel}>{cancelLabel}</Button>
          <Button variant={danger ? 'secondary' : 'primary'} danger={danger} onClick={onConfirm}>{confirmLabel}</Button>
        </div>
      </div>
    </div>
  );
}
