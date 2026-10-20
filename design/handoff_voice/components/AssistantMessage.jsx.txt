import React from 'react';
export function AssistantMessage({ children, streaming = false, style }) {
  return (
    <div style={{ padding: '4px var(--gutter-page)', font: 'var(--type-body)', color: 'var(--text-body)', maxWidth: 640, ...style }}>{children}{streaming && <span aria-hidden="true" style={{ display: 'inline-block', width: 2, height: '1em', background: 'var(--accent)', marginLeft: 2, verticalAlign: '-0.15em', animation: 'tsk-caret 1s steps(2) infinite' }}><style>{'@keyframes tsk-caret{0%,100%{opacity:1}50%{opacity:0}}'}</style></span>}</div>
  );
}
