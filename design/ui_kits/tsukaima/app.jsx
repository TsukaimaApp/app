const seed = [
  { role: 'user', text: 'The nightly backup cron is failing. Can you take a look?' },
  { role: 'thinking', text: 'Thought for 9s', body: 'Check the cron log first, then the service token.' },
  { role: 'tool', name: 'shell', detail: 'tail -n 50 /var/log/cron.log', status: 'done', output: 'Oct 19 03:00:01 CRON[8812]: backup.sh exited 401\nOct 19 03:00:01 CRON[8812]: token rejected' },
  { role: 'assistant', text: 'The backup script is getting a 401 — the storage token expired on the 16th. I can rotate it and restart the agent, but the restart needs your sign-off:' },
  { role: 'approval', name: 'shell', command: 'systemctl restart hermes-agent' },
];
const sessionSeed = [
  { id: 1, profile: 'default', title: 'Fix the cron job', meta: '2h', sid: 'ses_8f2a19' },
  { id: 2, profile: 'default', title: 'Summarize server logs', meta: '1d', sid: 'ses_c71e04' },
  { id: 3, profile: 'ops', title: 'Backup rotation plan', meta: '3d', sid: 'ses_2b9d77' },
  { id: 4, profile: 'research', title: 'Paper triage', meta: '4d', sid: 'ses_e40a12' },
  { id: 5, profile: 'research', title: 'Dataset licence check', meta: '1w', sid: 'ses_91f0c3' },
  { id: 6, profile: 'ops', title: 'Old migration notes', meta: '2mo', sid: 'ses_0a1b2c', archived: true },
];
const pairingSeed = { platform: 'Telegram', who: '@kana · 184 229 310', code: '4F2-91K' };
// ≥720dp (unfolded Z Fold, tablet, desktop web) → persistent two-pane; below → phone with drawer overlay
function useWide(ref) {
  const [wide, setWide] = React.useState(false);
  React.useEffect(() => {
    const el = ref.current; if (!el) return;
    const ro = new ResizeObserver(([e]) => setWide(e.contentRect.width >= 720));
    ro.observe(el); return () => ro.disconnect();
  }, []);
  return wide;
}
function App({ start = 'onboarding', forceTheme }) {
  const ref = React.useRef(null);
  const wide = useWide(ref);
  const [screen, setScreen] = React.useState(start);
  const [editJob, setEditJob] = React.useState(undefined); // undefined = closed, null = new, obj = edit
  const [drawer, setDrawer] = React.useState(false);
  const [sessions, setSessions] = React.useState(sessionSeed);
  const [active, setActive] = React.useState(1);
  const [messages, setMessages] = React.useState(seed);
  const [status, setStatus] = React.useState('approval');
  const [statusLabel, setStatusLabel] = React.useState(null);
  const [gateway, setGateway] = React.useState('running');
  const [pairing, setPairing] = React.useState(pairingSeed);
  const [jobs, setJobs] = React.useState(cronSeed);
  const [profile, setProfile] = React.useState('default');
  const [workspace, setWorkspace] = React.useState('~/projects/hermes');
  const [model, setModel] = React.useState(MODELS[0]);
  const [theme, setTheme] = React.useState(forceTheme || 'System');
  const [offline, setOffline] = React.useState(false);
  const [loading, setLoading] = React.useState(false);
  const [sysDark, setSysDark] = React.useState(() => window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
  React.useEffect(() => { const mq = window.matchMedia('(prefers-color-scheme: dark)'); const h = e => setSysDark(e.matches); mq.addEventListener('change', h); return () => mq.removeEventListener('change', h); }, []);
  const dark = theme === 'Dark' || (theme === 'System' && sysDark);
  const timers = React.useRef([]);
  React.useEffect(() => () => timers.current.forEach(clearTimeout), []);
  const later = (ms, fn) => timers.current.push(setTimeout(fn, ms));
  const screenRef = React.useRef(screen); screenRef.current = screen;
  // ?demo=1 — autoplay for embeds
  const demo = React.useMemo(() => new URLSearchParams(location.search).get('demo') === '1', []);
  const [fade, setFade] = React.useState(false);
  React.useEffect(() => {
    if (!demo) return;
    const steps = [['onboarding', false, 3200], ['status', false, 3800], ['chat', false, 4200], ['chat', true, 3000], ['cron', false, 3200], ['settings', false, 3400]];
    let i = 0, t;
    const go = () => {
      const [s, d, ms] = steps[i];
      const switching = s !== screenRef.current;
      if (switching) setFade(true);
      setTimeout(() => { setScreen(s); setDrawer(d); setFade(false); }, switching ? 220 : 0);
      i = (i + 1) % steps.length; t = setTimeout(go, ms);
    };
    go(); return () => clearTimeout(t);
  }, [demo]);
  const send = text => {
    if (offline) return;
    setMessages(m => [...m, { role: 'user', text }]);
    setStatus('thinking'); setStatusLabel(null);
    later(1400, () => { setStatus('tool'); setStatusLabel('Running shell command'); });
    later(2600, () => {
      setMessages(m => [...m, { role: 'tool', name: 'shell', detail: 'uptime', status: 'done', output: ' 14:02:11 up 41 days,  load average: 0.12' }, { role: 'assistant', text: '', streaming: true }]);
      setStatus('thinking'); setStatusLabel('Replying…');
      const words = 'Done — the box has been up 41 days and load is flat. Everything looks healthy from here.'.split(' ');
      words.forEach((w, i) => later(2700 + i * 70, () => setMessages(m => { const c = m.slice(); const last = { ...c[c.length - 1] }; last.text = words.slice(0, i + 1).join(' '); last.streaming = i < words.length - 1; c[c.length - 1] = last; return c; })));
      later(2700 + words.length * 70 + 100, () => { setStatus('idle'); setStatusLabel(null); });
    });
  };
  const resolveApproval = approved => {
    setMessages(m => m.filter(x => x.role !== 'approval').concat(approved
      ? [{ role: 'tool', name: 'shell', detail: 'systemctl restart hermes-agent', status: 'done', output: 'hermes-agent.service: restarted (pid 4182)' }, { role: 'assistant', text: 'Restarted with the new token. The 3am run should succeed — I\u2019ll flag it if not.' }]
      : [{ role: 'assistant', text: 'Understood — I left the agent running. The backup will keep failing until the token is rotated.' }]));
    setStatus('idle'); setStatusLabel(null);
  };
  const resolvePairing = ok => { setPairing(null); setMessages(m => m.filter(x => x.role !== 'pairing').concat([{ role: 'assistant', text: ok ? 'Paired @kana on Telegram.' : 'Revoked the pairing request from @kana.' }])); };
  const onGateway = action => {
    if (action === 'stop') { setGateway('stopped'); setStatus('stopped'); return; }
    setGateway('restarting'); setStatusLabel(null); setStatus('tool'); setStatusLabel(action === 'start' ? 'Starting gateway' : 'Restarting gateway');
    later(1600, () => { setGateway('running'); setStatus('idle'); setStatusLabel(null); });
  };
  const newChat = () => { setActive(null); setMessages([]); setStatus(gateway === 'running' ? 'idle' : 'stopped'); setStatusLabel(null); setDrawer(false); setScreen('chat'); };
  const pick = id => { setActive(id); setDrawer(false); setScreen('chat'); setLoading(true); later(650, () => { setLoading(false); setMessages(id === 1 ? seed : [{ role: 'assistant', text: 'Picking up where we left off.' }]); setStatus(id === 1 ? 'approval' : 'idle'); }); };
  const session = sessions.find(s => s.id === active);
  const sessionActions = {
    onRename: t => setSessions(ss => ss.map(x => x.id === active ? { ...x, title: t } : x)),
    onArchive: () => { setSessions(ss => ss.map(x => x.id === active ? { ...x, archived: true } : x)); setScreen('status'); },
    onDelete: () => { setSessions(ss => ss.filter(x => x.id !== active)); setActive(null); setMessages([]); setScreen('status'); },
  };
  const go = s => () => { setDrawer(false); setScreen(s); };
  const drawerProps = { sessions, setSessions, active, onPick: pick, onNew: newChat, onStatus: go('status'), onCron: go('cron'), onSettings: go('settings') };
  const activeTitle = (sessions.find(s => s.id === active) || {}).title;
  const main = <>
    {screen === 'onboarding' && <OnboardingScreen onConnect={() => setScreen('settings')} />}
    {screen === 'status' && <StatusScreen showMenu={!wide} onMenu={() => setDrawer(true)} gateway={gateway} onGateway={onGateway} pairing={pairing} onPairing={resolvePairing} lastSession={activeTitle} onContinue={() => setScreen('chat')} onNew={newChat} onCron={go('cron')} onSettings={go('settings')} offline={offline} onRetry={() => setOffline(false)} />}
    {screen === 'chat' && <ChatScreen showMenu={!wide} onMenu={() => setDrawer(true)} status={status} statusLabel={statusLabel} loading={loading} messages={pairing && active === 1 ? [...messages.slice(0, 1), { role: 'pairing', ...pairing }, ...messages.slice(1)] : messages} onSend={send} onApprove={() => resolveApproval(true)} onDeny={() => resolveApproval(false)} onPairing={resolvePairing} workspace={workspace} onWorkspace={setWorkspace} onStatusTap={go('status')} model={model} onModel={setModel} offline={offline} session={session} {...sessionActions} />}
    {screen === 'cron' && editJob === undefined && <CronScreen onBack={go('status')} jobs={jobs} setJobs={setJobs} onEdit={j => setEditJob(j)} onNew={() => setEditJob(null)} />}
    {screen === 'cron' && editJob !== undefined && <CronEditScreen job={editJob} onBack={() => setEditJob(undefined)} onSave={f => { setJobs(js => f.id ? js.map(x => x.id === f.id ? { ...x, ...f } : x) : [...js, { ...f, id: Date.now(), human: 'Custom schedule', next: 'in 1d', paused: false }]); setEditJob(undefined); }} />}
    {screen === 'settings' && <SettingsScreen onBack={go('status')} onLogs={go('logs')} profile={profile} setProfile={setProfile} pairing={pairing} onPairing={resolvePairing} theme={theme} setTheme={setTheme} offline={offline} setOffline={setOffline} />}
    {screen === 'logs' && <LogsScreen onBack={go('settings')} />}
  </>;
  const showPane = wide && screen !== 'onboarding';
  return (
    <div ref={ref} className="app" data-theme={dark ? 'dark' : undefined} style={{ flexDirection: 'row', colorScheme: dark ? 'dark' : 'light' }}>
      {showPane && <DrawerOverlay persistent {...drawerProps} />}
      <div style={{ flex: 1, minWidth: 0, height: '100%', position: 'relative', display: 'flex', flexDirection: 'column' }}>
        <div style={{ flex: 1, minHeight: 0, width: '100%', maxWidth: wide ? 760 : 'none', margin: '0 auto', position: 'relative', opacity: fade ? 0 : 1, transition: 'opacity 220ms var(--ease-quiet)' }}>{main}</div>
      </div>
      {!wide && <DrawerOverlay open={drawer} onClose={() => setDrawer(false)} {...drawerProps} />}
    </div>
  );
}
Object.assign(window, { App });
