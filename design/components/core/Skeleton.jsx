import React from 'react';
export function Skeleton({ width = '100%', height = 14, radius = 6, style }) {
  return (
    <span style={{ display: 'block', width, height, borderRadius: radius, background: 'var(--surface-sunken)', animation: 'tsk-pulse 1.6s infinite', ...style }}>
      <style>{'@keyframes tsk-pulse{0%,100%{opacity:1}50%{opacity:.45}}'}</style>
    </span>
  );
}
