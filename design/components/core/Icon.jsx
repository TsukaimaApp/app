import React from 'react';
export function Icon({ name, size = 24, color, filled = false, style }) {
  return <span className="ms" aria-hidden="true" style={{ fontSize: size, color, fontVariationSettings: `'opsz' ${size >= 24 ? 24 : 20},'wght' 300,'FILL' ${filled ? 1 : 0},'GRAD' 0`, ...style }}>{name}</span>;
}
