// /api/cron/jobs — list, pause/resume, trigger, delete; edit is full-screen
const cronSeed = [
  { id: 1, name: 'Nightly backup', expr: '0 3 * * *', human: 'Every day at 03:00', next: 'in 9h', deliver: 'telegram', prompt: 'Run the backup script and report size and duration.', paused: false },
  { id: 2, name: 'Inbox triage', expr: '*/30 8-20 * * 1-5', human: 'Every 30 min, weekdays 8–20', next: 'in 12m', deliver: 'none', prompt: 'Summarize unread mail; flag anything from clients.', paused: false },
  { id: 3, name: 'Paper digest', expr: '0 7 * * 1', human: 'Mondays at 07:00', next: 'paused', deliver: 'discord', prompt: 'Pull new arXiv papers matching my topics and write a digest.', paused: true },
];
function CronScreen({ onBack, jobs, setJobs, onEdit, onNew }) {
  const DS = window.TsukaimaDesignSystem_17565f;
  const [menu, setMenu] = React.useState(null);
  const [ask, setAsk] = React.useState(null);
  const [toast, setToast] = React.useState(null);
  const say = t => { setToast(t); setTimeout(() => setToast(null), 1800); };
  const toggle = j => setJobs(js => js.map(x => x.id === j.id ? { ...x, paused: !x.paused, next: x.paused ? 'in 2h' : 'paused' } : x));
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', position: 'relative' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '8px 8px 2px' }}>
        <DS.IconButton icon="arrow_back" label="Back" onClick={onBack} />
        <span style={{ font: 'var(--type-title)', color: 'var(--text-body)' }}>Cron</span>
        <div style={{ flex: 1 }}></div>
        <DS.Button size="sm" icon="add" onClick={onNew}>New job</DS.Button>
      </div>
      <div style={{ flex: 1, overflowY: 'auto', padding: '10px 12px 20px', display: 'flex', flexDirection: 'column', gap: 4 }}>
        {jobs.map(j => <DS.Row key={j.id} icon={j.paused ? 'pause_circle' : 'schedule'} title={j.name} subtitle={j.human + ' · ' + j.expr} meta={j.next} onClick={() => setMenu(j)} onLongPress={() => setMenu(j)} style={{ opacity: j.paused ? .6 : 1 }} />)}
        <div style={{ font: 'var(--type-caption)', color: 'var(--text-tertiary)', padding: '12px 12px 0' }}>Tap a job for actions. Delivery goes to the job's platform, or stays in a session.</div>
      </div>
      {toast && <div style={{ position: 'absolute', left: 16, right: 16, bottom: 20, background: 'var(--ink-1)', color: 'var(--cream-0)', font: 'var(--type-ui)', padding: '12px 16px', borderRadius: 'var(--radius-row)' }}>{toast}</div>}
      <DS.Menu open={!!menu} title={menu && menu.name} onClose={() => setMenu(null)} items={menu ? [
        { icon: 'play_arrow', label: 'Run now', onClick: () => say('Triggered ' + menu.name) },
        { icon: menu.paused ? 'play_circle' : 'pause_circle', label: menu.paused ? 'Resume' : 'Pause', onClick: () => toggle(menu) },
        { icon: 'edit', label: 'Edit', onClick: () => onEdit(menu) },
        { icon: 'delete', label: 'Delete', danger: true, onClick: () => setAsk(menu) },
      ] : []} />
      <DS.Dialog open={!!ask} danger title="Delete this job?" body={ask && <span>“{ask.name}” stops running and can’t be restored.</span>} confirmLabel="Delete" onConfirm={() => { setJobs(js => js.filter(x => x.id !== ask.id)); setAsk(null); }} onCancel={() => setAsk(null)} />
    </div>
  );
}
function CronEditScreen({ job, onBack, onSave }) {
  const DS = window.TsukaimaDesignSystem_17565f;
  const [f, setF] = React.useState(job || { name: '', prompt: '', expr: '0 9 * * *', deliver: 'none' });
  const set = k => v => setF(x => ({ ...x, [k]: v }));
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, padding: '8px 8px 2px' }}>
        <DS.IconButton icon="close" label="Cancel" onClick={onBack} />
        <span style={{ font: 'var(--type-title)', color: 'var(--text-body)' }}>{job ? 'Edit job' : 'New job'}</span>
        <div style={{ flex: 1 }}></div>
        <DS.Button size="sm" variant="primary" onClick={() => onSave(f)} disabled={!f.name || !f.prompt}>Save</DS.Button>
      </div>
      <div style={{ flex: 1, overflowY: 'auto', padding: '14px 20px 24px', display: 'flex', flexDirection: 'column', gap: 18 }}>
        <DS.TextField label="Name" value={f.name} onChange={set('name')} placeholder="Nightly backup" />
        <DS.TextField label="Prompt" multiline rows={5} value={f.prompt} onChange={set('prompt')} placeholder="What should the agent do when this fires?" />
        <DS.TextField label="Schedule" mono value={f.expr} onChange={set('expr')} helper="Cron expression, server time. 0 3 * * * is every day at 03:00." />
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <span style={{ font: 'var(--type-label)', letterSpacing: '.08em', textTransform: 'uppercase', color: 'var(--text-tertiary)' }}>Deliver to</span>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>{['none', 'telegram', 'discord'].map(d => <DS.Chip key={d} label={d === 'none' ? 'Session only' : d[0].toUpperCase() + d.slice(1)} selected={f.deliver === d} onClick={() => set('deliver')(d)} />)}</div>
        </div>
      </div>
    </div>
  );
}
Object.assign(window, { CronScreen, CronEditScreen, cronSeed });
