// In-app voice mode: full-screen, serif status, transcript as normal chat semantics, approval still a card
function useVoiceDemo(script, autoplay) {
  const [phase, setPhase] = React.useState('listening');
  const [turns, setTurns] = React.useState([]);
  const timers = React.useRef([]);
  const later = (ms, fn) => timers.current.push(setTimeout(fn, ms));
  const speak = (role, text, start, speed) => {
    const words = text.split(' ');
    later(start, () => setTurns(t => [...t, { role, text: words[0], live: words.length > 1 }]));
    words.forEach((w, i) => i > 0 && later(start + i * speed, () => setTurns(t => { const c = t.slice(); const last = { ...c[c.length - 1] }; last.text = words.slice(0, i + 1).join(' '); last.live = i < words.length - 1; c[c.length - 1] = last; return c; })));
    return start + words.length * speed + 240;
  };
  React.useEffect(() => { if (autoplay) script({ later, speak, setPhase, setTurns }); return () => timers.current.forEach(clearTimeout); }, []);
  return { phase, setPhase, turns, setTurns, later, speak };
}
const PHASE_LINE = { listening: ['tool', 'Listening…'], thinking: ['thinking', null], speaking: ['tool', 'Speaking'], approval: ['approval', null], muted: ['idle', 'Muted'], idle: ['idle', 'Tap to speak'] };

function VoiceScreen({ onClose, onKeyboard, model = 'Hermes 4 · 405B', autoplay = true }) {
  const DS = window.TsukaimaDesignSystem_17565f;
  const endRef = React.useRef(null);
  const [muted, setMuted] = React.useState(false);
  const v = useVoiceDemo(({ later, speak, setPhase, setTurns }) => {
    let t = 900;
    t = speak('user', 'What happened with the nightly backup?', t, 150);
    later(t, () => setPhase('thinking')); t += 1700;
    later(t, () => setPhase('speaking'));
    t = speak('assistant', 'It failed at 3am with a 401 — the storage token expired on the 16th. I can rotate it and restart the agent. Want me to?', t, 85);
    later(t, () => setPhase('listening')); t += 1500;
    t = speak('user', 'Yes, go ahead.', t, 150);
    later(t, () => { setPhase('approval'); setTurns(x => [...x, { role: 'approval' }]); });
  }, autoplay);
  React.useEffect(() => { const el = endRef.current; if (el) { let p = el.parentNode; while (p && getComputedStyle(p).overflowY !== 'auto') p = p.parentNode; if (p) p.scrollTop = p.scrollHeight; } }, [v.turns, v.phase]);
  const resolve = ok => {
    v.setTurns(t => t.filter(x => x.role !== 'approval').concat(ok ? [{ role: 'tool', name: 'shell', detail: 'systemctl restart hermes-agent' }] : []));
    v.setPhase('speaking');
    const end = v.speak('assistant', ok ? 'Done — restarted with the new token. The 3am run should succeed.' : 'Okay, I left it alone.', 300, 85);
    v.later(end, () => v.setPhase('listening'));
  };
  const phase = muted && v.phase === 'listening' ? 'muted' : v.phase;
  const [lineState, lineLabel] = PHASE_LINE[phase] || PHASE_LINE.idle;
  const big = { width: 56, height: 56, background: 'var(--surface-raised)', border: '1px solid var(--border-hairline)', color: 'var(--text-body)' };
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '8px 8px 2px' }}>
        <DS.IconButton icon="expand_more" label="Back to chat" onClick={onClose} />
        <div style={{ flex: 1 }}></div>
        <DS.Chip label={model} trailingIcon="expand_more" style={{ border: 'none' }} />
        <div style={{ flex: 1 }}></div>
        <div style={{ width: 44 }}></div>
      </div>
      <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '4vh 0 12px' }}>
        <DS.VoiceIndicator state={phase === 'approval' ? 'idle' : phase} size={180} />
        <DS.StatusLine state={lineState} label={lineLabel} style={{ fontSize: 16, padding: '2px 20px 0', justifyContent: 'center' }} />
        <div style={{ width: '100%', marginTop: 22 }}>
          {v.turns.map((m, i) => {
            if (m.role === 'user') return <DS.UserBubble key={i}>{m.text}</DS.UserBubble>;
            if (m.role === 'tool') return <DS.ToolRow key={i} name={m.name} detail={m.detail} status="done" />;
            if (m.role === 'approval') return <DS.ApprovalCard key={i} toolName="shell" command="systemctl restart hermes-agent" onApprove={() => resolve(true)} onDeny={() => resolve(false)} />;
            return <DS.AssistantMessage key={i} streaming={m.live}>{m.text}</DS.AssistantMessage>;
          })}
          <div ref={endRef}></div>
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 28, padding: '12px 20px 28px' }}>
        <DS.IconButton icon={muted ? 'mic_off' : 'mic'} label={muted ? 'Unmute' : 'Mute'} size={56} onClick={() => setMuted(m => !m)} style={big} />
        <DS.IconButton icon="close" label="End voice" size={64} onClick={onClose} style={{ width: 64, height: 64, background: 'var(--ink-1)', color: 'var(--cream-0)' }} />
        <DS.IconButton icon="keyboard" label="Type instead" size={56} onClick={onKeyboard || onClose} style={big} />
      </div>
    </div>
  );
}

// Android assistant slot: Tsukaima as the system assist app, a sheet over whatever is on screen
function AssistOverlay({ autoplay = true, onOpenApp }) {
  const DS = window.TsukaimaDesignSystem_17565f;
  const v = useVoiceDemo(({ later, speak, setPhase, setTurns }) => {
    let t = 800;
    t = speak('user', 'Pause the nightly backup job.', t, 150);
    later(t, () => setPhase('thinking')); t += 1500;
    later(t, () => { setTurns(x => [...x, { role: 'tool', name: 'cron', detail: 'pause nightly-backup' }]); setPhase('speaking'); });
    t = speak('assistant', 'Paused. It won’t run at 3am — say resume when you want it back.', t + 500, 85);
    later(t, () => setPhase('idle'));
  }, autoplay);
  const [lineState, lineLabel] = PHASE_LINE[v.phase] || PHASE_LINE.idle;
  const line = { height: 10, borderRadius: 5, background: '#D8D4CC' };
  return (
    <div style={{ height: '100%', position: 'relative', overflow: 'hidden', background: '#E9E6E0', fontFamily: 'var(--font-sans)' }}>
      <div aria-hidden="true" style={{ position: 'absolute', inset: 0, padding: '0 20px', color: '#8E8A84' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', font: '500 13px/1 var(--font-sans)', padding: '14px 4px 0' }}><span>9:41</span><span>▲ ◆ ▮</span></div>
        <div style={{ margin: '28px 0 22px', height: 22, width: '48%', borderRadius: 6, background: '#CFCBC3' }}></div>
        {[92, 76, 84, 60, 88, 70, 80, 52].map((w, i) => <div key={i} style={{ ...line, width: w + '%', marginBottom: 14 }}></div>)}
        <div style={{ margin: '26px 0 22px', height: 22, width: '36%', borderRadius: 6, background: '#CFCBC3' }}></div>
        {[88, 64, 90, 72].map((w, i) => <div key={i} style={{ ...line, width: w + '%', marginBottom: 14 }}></div>)}
      </div>
      <div style={{ position: 'absolute', inset: 0, background: 'rgba(39,34,32,.38)' }}></div>
      <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, background: 'var(--surface-raised)', borderRadius: '28px 28px 0 0', boxShadow: 'var(--shadow-float)', padding: '10px 20px 10px', display: 'flex', flexDirection: 'column', gap: 10 }}>
        <div style={{ width: 32, height: 4, borderRadius: 99, background: 'var(--border-hairline)', alignSelf: 'center' }}></div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <img src="../assets/mark.svg" alt="" style={{ height: 18 }} />
          <span style={{ font: 'var(--type-wordmark)', fontSize: 17, color: 'var(--text-body)' }}>Tsukaima</span>
          <div style={{ flex: 1 }}></div>
          <DS.IconButton icon="keyboard" label="Type" size={40} />
          <DS.IconButton icon="open_in_new" label="Open Tsukaima" size={40} onClick={onOpenApp} />
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <DS.VoiceIndicator state={v.phase} size={72} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <DS.StatusLine state={lineState} label={lineLabel} style={{ padding: 0 }} />
            {v.turns.filter(t => t.role === 'user').slice(-1).map((m, i) => <div key={i} style={{ font: 'var(--type-body)', color: 'var(--text-body)', marginTop: 4 }}>{m.text}</div>)}
          </div>
        </div>
        {v.turns.filter(t => t.role !== 'user').map((m, i) => m.role === 'tool'
          ? <DS.ToolRow key={i} name={m.name} detail={m.detail} status="done" style={{ margin: 0 }} />
          : <DS.AssistantMessage key={i} streaming={m.live} style={{ padding: 0 }}>{m.text}</DS.AssistantMessage>)}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8 }}>
          <DS.Button variant="ghost" size="sm" onClick={onOpenApp}>Continue in Tsukaima</DS.Button>
        </div>
        <div style={{ width: 108, height: 4, borderRadius: 99, background: 'var(--ink-1)', opacity: .4, alignSelf: 'center', marginTop: 2 }}></div>
      </div>
    </div>
  );
}
Object.assign(window, { VoiceScreen, AssistOverlay });
