// GET /api/logs?file&level&lines — read-only tail
function LogsScreen({ onBack }) {
  const DS = window.TsukaimaDesignSystem_17565f;
  const [file, setFile] = React.useState('gateway');
  const [level, setLevel] = React.useState('info');
  const lines = {
    gateway: ['14:02:11 INFO  gateway: telegram poll ok (3 updates)', '14:02:14 INFO  session 8f2a: tool shell approved', '14:02:15 WARN  storage: token expires in 2 days', '14:02:19 INFO  gateway: discord heartbeat', '14:03:01 INFO  cron: inbox-triage fired', '14:03:04 INFO  session c71e: 1 842 tokens, 3.1s'],
    agent: ['14:02:14 INFO  agent: running shell `systemctl restart hermes-agent`', '14:02:15 INFO  agent: exit 0 (0.8s)', '14:02:16 INFO  agent: reply streamed 212 tokens'],
  }[file].filter(l => level === 'info' || !l.includes(' INFO '));
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '8px 8px 2px' }}>
        <DS.IconButton icon="arrow_back" label="Back" onClick={onBack} />
        <span style={{ font: 'var(--type-title)', color: 'var(--text-body)' }}>Logs</span>
      </div>
      <div style={{ display: 'flex', gap: 8, padding: '10px 20px 6px', flexWrap: 'wrap' }}>
        {['gateway', 'agent'].map(f => <DS.Chip key={f} label={f + '.log'} selected={file === f} onClick={() => setFile(f)} />)}
        <span style={{ width: 1, background: 'var(--border-hairline)', margin: '6px 2px' }}></span>
        {['info', 'warn'].map(l => <DS.Chip key={l} label={l === 'info' ? 'All' : 'Warn+'} selected={level === l} onClick={() => setLevel(l)} />)}
      </div>
      <div style={{ flex: 1, overflow: 'auto', margin: '8px 20px 20px' }}>
        <DS.CodeBlock lang={file + '.log · last 200'} code={lines.join('\n')} style={{ margin: 0 }} />
      </div>
    </div>
  );
}
Object.assign(window, { LogsScreen });
