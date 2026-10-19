function SettingsScreen({ onBack, onLogs, profile, setProfile, pairing, onPairing, theme, setTheme, offline, setOffline }) {
  const DS = window.TsukaimaDesignSystem_17565f;
  const [url, setUrl] = React.useState('https://hermes.local:8000');
  const [token, setToken] = React.useState('hm_••••••••••••');
  const [model, setModel] = React.useState('Hermes 4 · 405B');
  const [dyn, setDyn] = React.useState(false);
  const [skills, setSkills] = React.useState({ 'Web search': true, 'Shell': true, 'Browser': false, 'Memory': true });
  const [tools, setTools] = React.useState({ 'filesystem': true, 'git': true, 'docker': false });
  const Section = ({ title, children, hint }) => <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}><div style={{ font: 'var(--type-title)', fontSize: 17, color: 'var(--text-body)' }}>{title}</div>{hint && <div style={{ font: 'var(--type-caption)', color: 'var(--text-tertiary)', marginTop: -6 }}>{hint}</div>}{children}</div>;
  const toggles = (obj, set) => Object.keys(obj).map(k => <DS.Row key={k} title={k} trailing={<DS.Switch checked={obj[k]} onChange={v => set(o => ({ ...o, [k]: v }))} />} style={{ margin: '0 -12px', minHeight: 48 }} />);
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '8px 8px 2px' }}>
        <DS.IconButton icon="arrow_back" label="Back" onClick={onBack} />
        <span style={{ font: 'var(--type-title)', color: 'var(--text-body)' }}>Settings</span>
      </div>
      <div style={{ flex: 1, overflowY: 'auto', padding: '14px 20px 24px', display: 'flex', flexDirection: 'column', gap: 28 }}>
        <Section title="Hermes instance">
          <DS.TextField label="Server URL" mono value={url} onChange={setUrl} />
          <DS.TextField label="Token" mono value={token} onChange={setToken} helper="Paste the token from your Hermes instance." />
        </Section>
        <Section title="Profile" hint="Scopes sessions, model and skills. Switching here changes everything else.">
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>{['default', 'research', 'ops'].map(p => <DS.Chip key={p} label={p} selected={profile === p} onClick={() => setProfile(p)} />)}</div>
        </Section>
        <Section title="Model">
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>{['Hermes 4 · 405B', 'Hermes 4 · 70B', 'Hermes 3 · 8B'].map(m => <DS.Chip key={m} label={m} selected={model === m} onClick={() => setModel(m)} />)}</div>
        </Section>
        <Section title="Skills" hint="Toggle only. Installing new skills is a desktop task."><div>{toggles(skills, setSkills)}</div></Section>
        <Section title="Toolsets"><div>{toggles(tools, setTools)}</div></Section>
        <Section title="Pairing" hint="Who may talk to the bot.">
          {pairing && <DS.PairingCard platform={pairing.platform} who={pairing.who} code={pairing.code} onApprove={() => onPairing(true)} onRevoke={() => onPairing(false)} style={{ margin: 0 }} />}
          <div style={{ margin: '0 -12px' }}>
            <DS.Row icon="send" title="@mario" subtitle="Telegram · paired 3 weeks ago" trailing={<DS.Button size="sm" variant="ghost" danger>Revoke</DS.Button>} />
            <DS.Row icon="tag" title="mario#0420" subtitle="Discord · paired 2 months ago" trailing={<DS.Button size="sm" variant="ghost" danger>Revoke</DS.Button>} />
          </div>
        </Section>
        <Section title="Diagnostics">
          <div style={{ margin: '0 -12px' }}>
            <DS.Row icon="receipt_long" title="Logs" subtitle="gateway.log · agent.log" trailingIcon="chevron_right" onClick={onLogs} />
            <DS.Row icon="bar_chart" title="Usage this month" subtitle="1.2 M tokens · 318 sessions · 41 tool calls / day" />
            <DS.Row icon="open_in_new" title="Open admin dashboard" subtitle="Config, env, MCP, webhooks — in the browser" />
            <DS.Row icon="cloud_off" title="Simulate Hermes unreachable" subtitle="Design kit only — not in the real app" trailing={<DS.Switch checked={!!offline} onChange={setOffline} />} />
          </div>
        </Section>
        <Section title="Theme">
          <div style={{ display: 'flex', gap: 8 }}>{['Warm', 'Dark', 'System'].map(t => <DS.Chip key={t} label={t} selected={theme === t} onClick={() => setTheme(t)} />)}</div>
          <div style={{ font: 'var(--type-caption)', color: 'var(--text-tertiary)', marginTop: -2 }}>System is the default: warm in light, near-black in dark.</div>
          <div style={{ margin: '0 -12px' }}><DS.Row title="Material You dynamic color" subtitle="Off keeps the fixed warm palette." trailing={<DS.Switch checked={dyn} onChange={setDyn} />} /></div>
        </Section>
      </div>
    </div>
  );
}
Object.assign(window, { SettingsScreen });
