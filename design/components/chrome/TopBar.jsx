import React from 'react';
import { IconButton } from '../core/IconButton.jsx';
import { Chip } from '../core/Chip.jsx';
export function TopBar({ model = 'Hermes 4', showMenu = true, onMenu, onModel, onOverflow, style }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '8px 8px 2px', background: 'var(--surface-page)', ...style }}>
      {showMenu && <IconButton icon="menu" label="Conversations" onClick={onMenu} />}
      <Chip label={model} trailingIcon="expand_more" onClick={onModel} style={{ border: 'none' }} />
      <div style={{ flex: 1 }}></div>
      <IconButton icon="more_vert" label="More" onClick={onOverflow} />
    </div>
  );
}
