import React, { useState } from 'react';
import { Icon } from '../core/Icon.jsx';
export function CodeBlock({ code, lang, style }) {
  const [copied, setCopied] = useState(false);
  const copy = () => { try { navigator.clipboard.writeText(code); } catch (e) {} setCopied(true); setTimeout(() => setCopied(false), 1500); };
  return (
    <div style={{ position: 'relative', background: 'var(--code-bg)', borderRadius: 'var(--radius-code)', margin: '8px 0', ...style }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '6px 6px 0 14px' }}>
        <span style={{ font: 'var(--type-label)', letterSpacing: '.08em', textTransform: 'uppercase', color: 'rgba(216,211,232,.55)' }}>{lang || 'code'}</span>
        <button onClick={copy} aria-label="Copy code" style={{ display: 'inline-flex', alignItems: 'center', gap: 5, background: 'none', border: 'none', cursor: 'pointer', color: 'rgba(216,211,232,.75)', font: 'var(--type-caption)', padding: '6px 8px' }}>
          <Icon name={copied ? 'check' : 'content_copy'} size={16} />{copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <pre style={{ margin: 0, padding: '4px 14px 12px', font: 'var(--type-code)', color: 'var(--code-ink)', overflowX: 'auto' }}>{code}</pre>
    </div>
  );
}
