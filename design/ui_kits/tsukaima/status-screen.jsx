// Home: GET /api/status + POST /api/gateway/start|stop|restart, pending pairing, continue/new chat
function StatusScreen({ onMenu, showMenu = true, gateway, onGateway, pairing, onPairing, lastSession, onContinue, onNew, onCron, onSettings, offline, onRetry }) {
  const DS = window.TsukaimaDesignSystem_17565f;
  const [ask, setAsk] = React.useState(null);
  const [retrying, setRetrying] = React.useState(false);
  const retry = () => { setRetrying(true); setTimeout(() => { setRetrying(false); onRetry && onRetry(); }, 1200); };
  const running = gateway === 'running';
  const facts = [
    { icon: 'dns', title: 'Hermes 0.9.4', subtitle: 'hermes.local:8000 · profile default' },
    { icon: running ? 'play_circle' : 'stop_circle', title: running ? 'Gateway running' : 'Gateway stopped', subtitle: running ? 'up 41 days' : 'start it to receive messages', danger: !running },
    { icon: 'forum', title: '2 platforms connected', subtitle: 'Telegram · Discord' },
    { icon: 'chat', title: '3 active sessions', subtitle: '1 waiting for approval' },
    { icon: 'memory', title: 'Memory 62% · Disk 41%', subtitle: '5.0 GB / 8 GB · 82 GB free' },
  ];
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', position: 'relative' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '8px 8px 2px' }}>
        {showMenu && <DS.IconButton icon="menu" label="Menu" onClick={onMenu} />}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, paddingLeft: showMenu ? 2 : 12 }}><img src="../../assets/mark.svg" alt="" style={{ height: 20 }} /><span style={{ font: 'var(--type-wordmark)', fontSize: 20, color: 'var(--text-body)' }}>Tsukaima</span></div>
        <div style={{ flex: 1 }}></div>
        <DS.IconButton icon="settings" label="Settings" onClick={onSettings} />
      </div>
      <DS.StatusLine state={offline ? 'offline' : (retrying ? 'tool' : (running ? 'idle' : 'stopped'))} label={offline ? undefined : retrying ? 'Reconnecting' : running ? 'Hermes is listening' : undefined} />
      {offline ? <div style={{ flex: 1, overflowY: 'auto', padding: '8px 20px 20px', display: 'flex', flexDirection: 'column', gap: 16 }}>
        <DS.Card padding={20}>
          <div style={{ font: 'var(--type-title)', fontSize: 19, marginBottom: 6 }}>Can’t reach Hermes</div>
          <div style={{ font: 'var(--type-caption)', fontSize: 13.5, color: 'var(--text-secondary)', marginBottom: 16 }}>hermes.local:8000 didn’t answer. Check the tunnel or that the box is awake. Cached sessions still open read-only.</div>
          <div style={{ display: 'flex', gap: 10 }}><DS.Button variant="primary" icon="refresh" onClick={retry} disabled={retrying}>{retrying ? 'Retrying…' : 'Retry'}</DS.Button><DS.Button variant="ghost" onClick={onSettings}>Settings</DS.Button></div>
        </DS.Card>
        {lastSession && <DS.Row icon="history" title={lastSession} subtitle="Open cached copy" trailingIcon="chevron_right" onClick={onContinue} style={{ margin: '0 -8px' }} />}
        <DS.DrawerLabel>Last known status · 14 min ago</DS.DrawerLabel>
        <div style={{ opacity: .55, margin: '0 -8px' }}>{facts.slice(0, 3).map(f => <DS.Row key={f.title} icon={f.icon} title={f.title} subtitle={f.subtitle} />)}</div>
      </div> :
      <div style={{ flex: 1, overflowY: 'auto', padding: '8px 12px 20px', display: 'flex', flexDirection: 'column', gap: 6 }}>
        <div style={{ padding: '4px 8px 2px', display: 'flex', gap: 10 }}>
          <DS.Button variant="primary" icon="add" onClick={onNew} style={{ flex: 1 }}>New chat</DS.Button>
          <DS.Button icon="schedule" onClick={onCron} style={{ flex: 1 }}>Cron</DS.Button>
        </div>
        {lastSession && <DS.Row icon="history" title={lastSession} subtitle="Continue last session" trailingIcon="chevron_right" onClick={onContinue} />}
        {pairing && <DS.PairingCard platform={pairing.platform} who={pairing.who} code={pairing.code} onApprove={() => onPairing(true)} onRevoke={() => onPairing(false)} style={{ margin: '6px 0' }} />}
        <DS.DrawerLabel>Status</DS.DrawerLabel>
        {facts.map(f => <DS.Row key={f.title} icon={f.icon} title={f.title} subtitle={f.subtitle} danger={f.danger} />)}
        <div style={{ display: 'flex', gap: 8, padding: '8px 8px 0', flexWrap: 'wrap' }}>
          {running
            ? <><DS.Button icon="restart_alt" onClick={() => setAsk('restart')}>Restart gateway</DS.Button><DS.Button variant="ghost" danger onClick={() => setAsk('stop')}>Stop</DS.Button></>
            : <DS.Button variant="primary" icon="play_arrow" onClick={() => onGateway('start')}>Start gateway</DS.Button>}
        </div>
      </div>}
      <DS.Dialog open={!!ask} danger={ask === 'stop'} title={ask === 'stop' ? 'Stop the gateway?' : 'Restart the gateway?'} body={ask === 'stop' ? 'Telegram and Discord will stop answering until you start it again.' : 'Platforms reconnect in a few seconds. Running sessions are kept.'} confirmLabel={ask === 'stop' ? 'Stop' : 'Restart'} onConfirm={() => { onGateway(ask); setAsk(null); }} onCancel={() => setAsk(null)} />
    </div>
  );
}
Object.assign(window, { StatusScreen });
