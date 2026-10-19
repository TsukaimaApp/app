const MODELS = ['Hermes 4 · 405B', 'Hermes 4 · 70B', 'Hermes 3 · 8B'];
const WORKSPACES = ['~/projects/hermes', '~/notes', '~/sites/blog', '/srv/backups'];
function ChatScreen({ onMenu, showMenu = true, status, statusLabel, messages, loading, onSend, onApprove, onDeny, onPairing, workspace, onWorkspace, onStatusTap, model, onModel, offline, session, onRename, onArchive, onDelete }) {
  const DS = window.TsukaimaDesignSystem_17565f;
  const endRef = React.useRef(null);
  const [sheet, setSheet] = React.useState(null); // 'model' | 'ws' | 'more'
  const [ask, setAsk] = React.useState(null); // 'rename' | 'delete'
  const [name, setName] = React.useState('');
  const [toast, setToast] = React.useState(null);
  const say = t => { setToast(t); setTimeout(() => setToast(null), 1600); };
  React.useEffect(() => { const el = endRef.current; if (el && el.parentNode) el.parentNode.scrollTop = el.parentNode.scrollHeight; }, [messages, status]);
  const fresh = messages.length === 0 && !loading;
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', position: 'relative' }}>
      <DS.TopBar model={model} onMenu={onMenu} showMenu={showMenu} onModel={() => setSheet('model')} onOverflow={() => session && setSheet('more')} />
      <div onClick={onStatusTap} style={{ cursor: onStatusTap ? 'pointer' : 'default' }}><DS.StatusLine state={offline ? 'offline' : status} label={offline ? undefined : statusLabel} /></div>
      <div style={{ flex: 1, overflowY: 'auto', padding: '6px 0 10px' }}>
        {loading && <div style={{ padding: '12px 20px', display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}><DS.Skeleton width="56%" height={40} radius={16} /></div>
          <DS.Skeleton width="92%" /><DS.Skeleton width="78%" /><DS.Skeleton width="40%" />
          <DS.Skeleton width="100%" height={44} radius={12} />
        </div>}
        {fresh && <div style={{ padding: '18vh 20px 0', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 14, textAlign: 'center' }}>
          <div style={{ font: 'var(--type-status)', fontSize: 16, color: 'var(--text-secondary)' }}>New chat</div>
          <div style={{ font: 'var(--type-caption)', color: 'var(--text-tertiary)' }}>Working directory</div>
          <DS.Chip icon="folder" label={workspace} trailingIcon="expand_more" onClick={() => setSheet('ws')} />
        </div>}
        {!loading && messages.map((m, i) => {
          if (m.role === 'user') return <DS.UserBubble key={i}>{m.text}</DS.UserBubble>;
          if (m.role === 'tool') return <DS.ToolRow key={i} name={m.name} detail={m.detail} status={m.status}>{m.output && <DS.CodeBlock lang="log" code={m.output} />}</DS.ToolRow>;
          if (m.role === 'thinking') return <DS.ToolRow key={i} kind="thinking" name={m.text} status="done">{m.body}</DS.ToolRow>;
          if (m.role === 'approval') return <DS.ApprovalCard key={i} toolName={m.name} command={m.command} onApprove={onApprove} onDeny={onDeny} />;
          if (m.role === 'pairing') return <DS.PairingCard key={i} platform={m.platform} who={m.who} code={m.code} onApprove={() => onPairing(true)} onRevoke={() => onPairing(false)} />;
          return <DS.AssistantMessage key={i} streaming={m.streaming}>{m.text}{m.code && <DS.CodeBlock lang={m.lang} code={m.code} />}</DS.AssistantMessage>;
        })}
        <div ref={endRef}></div>
      </div>
      <div style={{ padding: '6px 12px 14px' }}><DS.Composer onSend={onSend} disabled={offline} onAttach={() => say('Attach: files and images upload into ' + workspace)} /></div>
      {toast && <div style={{ position: 'absolute', left: 16, right: 16, bottom: 84, background: 'var(--ink-1)', color: 'var(--cream-0)', font: 'var(--type-ui)', padding: '12px 16px', borderRadius: 'var(--radius-row)', zIndex: 30 }}>{toast}</div>}
      <DS.Menu open={sheet === 'model'} title="Model" onClose={() => setSheet(null)} items={MODELS.map(m => ({ icon: m === model ? 'check' : 'radio_button_unchecked', label: m, onClick: () => onModel(m) }))} />
      <DS.Menu open={sheet === 'ws'} title="Working directory" onClose={() => setSheet(null)} items={WORKSPACES.map(w => ({ icon: w === workspace ? 'check' : 'folder', label: w, onClick: () => onWorkspace(w) }))} />
      <DS.Menu open={sheet === 'more'} title={session && session.title} onClose={() => setSheet(null)} items={[
        { icon: 'edit', label: 'Rename', onClick: () => { setName(session.title); setAsk('rename'); } },
        { icon: 'archive', label: 'Archive', onClick: onArchive },
        { icon: 'content_copy', label: 'Copy session ID', onClick: () => say('Copied ' + (session && session.sid)) },
        { icon: 'delete', label: 'Delete session', danger: true, onClick: () => setAsk('delete') },
      ]} />
      <DS.Dialog open={ask === 'rename'} title="Rename session" confirmLabel="Save" onConfirm={() => { onRename(name); setAsk(null); }} onCancel={() => setAsk(null)} body={<DS.TextField value={name} onChange={setName} />} />
      <DS.Dialog open={ask === 'delete'} danger title="Delete session?" body="Messages and tool output are removed from Hermes. This can’t be undone." confirmLabel="Delete" onConfirm={() => { onDelete(); setAsk(null); }} onCancel={() => setAsk(null)} />
    </div>
  );
}
Object.assign(window, { ChatScreen, MODELS, WORKSPACES });
