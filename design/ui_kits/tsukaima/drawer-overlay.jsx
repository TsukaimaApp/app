// Sessions drawer: search, Status/Cron destinations, sessions grouped by profile, archived view, long-press → rename/archive/delete
function DrawerOverlay({ open, onClose, onSettings, onNew, onStatus, onCron, sessions, setSessions, active, onPick, persistent = false }) {
  const DS = window.TsukaimaDesignSystem_17565f;
  const [q, setQ] = React.useState('');
  const [menu, setMenu] = React.useState(null);
  const [ask, setAsk] = React.useState(null);
  const [rename, setRename] = React.useState(null);
  const [archivedView, setArchivedView] = React.useState(false);
  const match = s => s.title.toLowerCase().includes(q.toLowerCase());
  const archived = sessions.filter(s => s.archived);
  const groups = ['default', 'research', 'ops'].map(p => ({ p, items: sessions.filter(s => s.profile === p && !s.archived && match(s)) })).filter(g => g.items.length);
  const header = <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}><img src="../../assets/mark.svg" alt="" style={{ height: 24 }} /><span style={{ font: 'var(--type-wordmark)', fontSize: 22, color: 'var(--text-body)' }}>Tsukaima</span><span style={{ fontFamily: 'var(--font-kanji)', fontSize: 11, color: 'var(--text-tertiary)' }}>使い魔</span></div>
    <DS.TextField value={q} onChange={setQ} placeholder="Search sessions" />
  </div>;
  const drawer = (
    <DS.Drawer width={persistent ? 300 : 296} style={{ position: 'relative', flexShrink: 0 }} header={header} footer={<DS.ConversationItem icon="settings" title="Settings" onClick={onSettings} />}>
      {archivedView ? <>
        <DS.ConversationItem icon="arrow_back" title="Archived" onClick={() => setArchivedView(false)} />
        {archived.filter(match).map(s => <DS.ConversationItem key={s.id} title={s.title} meta={s.profile} onClick={() => onPick(s.id)} onLongPress={() => setMenu(s)} />)}
        {!archived.length && <div style={{ font: 'var(--type-caption)', color: 'var(--text-tertiary)', padding: '16px 12px' }}>Nothing archived.</div>}
      </> : <>
        <DS.ConversationItem icon="add" title="New chat" onClick={onNew || onClose} />
        <DS.ConversationItem icon="monitor_heart" title="Status" onClick={onStatus} />
        <DS.ConversationItem icon="schedule" title="Cron" onClick={onCron} />
        {groups.map(g => <React.Fragment key={g.p}>
          <DS.DrawerLabel>{g.p} profile</DS.DrawerLabel>
          {g.items.map(s => <DS.ConversationItem key={s.id} title={s.title} meta={s.meta} selected={active === s.id} onClick={() => onPick(s.id)} onLongPress={() => setMenu(s)} />)}
        </React.Fragment>)}
        {!groups.length && <div style={{ font: 'var(--type-caption)', color: 'var(--text-tertiary)', padding: '16px 12px' }}>No sessions match.</div>}
        <div style={{ height: 8 }}></div>
        <DS.ConversationItem icon="archive" title="Archived" meta={archived.length ? String(archived.length) : undefined} onClick={() => setArchivedView(true)} />
      </>}
      <DS.Menu open={!!menu} title={menu && menu.title} onClose={() => setMenu(null)} items={menu ? [
        { icon: 'edit', label: 'Rename', onClick: () => setRename({ ...menu }) },
        menu.archived ? { icon: 'unarchive', label: 'Unarchive', onClick: () => setSessions(ss => ss.map(x => x.id === menu.id ? { ...x, archived: false } : x)) }
                      : { icon: 'archive', label: 'Archive', onClick: () => setSessions(ss => ss.map(x => x.id === menu.id ? { ...x, archived: true } : x)) },
        { icon: 'delete', label: 'Delete', danger: true, onClick: () => setAsk(menu) },
      ] : []} />
      <DS.Dialog open={!!ask} danger title="Delete session?" body="Messages and tool output are removed from Hermes. This can’t be undone." confirmLabel="Delete" onConfirm={() => { setSessions(ss => ss.filter(x => x.id !== ask.id)); setAsk(null); }} onCancel={() => setAsk(null)} />
      <DS.Dialog open={!!rename} title="Rename session" confirmLabel="Save" onConfirm={() => { setSessions(ss => ss.map(x => x.id === rename.id ? { ...x, title: rename.title } : x)); setRename(null); }} onCancel={() => setRename(null)}
        body={rename && <DS.TextField value={rename.title} onChange={v => setRename(r => ({ ...r, title: v }))} />} />
    </DS.Drawer>
  );
  if (persistent) return drawer;
  return (
    <div style={{ position: 'absolute', inset: 0, pointerEvents: open ? 'auto' : 'none', zIndex: 20, overflow: 'hidden', borderRadius: 'inherit' }}>
      <div onClick={onClose} style={{ position: 'absolute', inset: 0, background: 'rgba(39,34,32,.28)', opacity: open ? 1 : 0, transition: 'opacity var(--dur-base) var(--ease-quiet)' }}></div>
      <div style={{ position: 'absolute', top: 0, bottom: 0, left: 0, transform: open ? 'none' : 'translateX(-104%)', transition: 'transform var(--dur-base) var(--ease-quiet)' }}>{drawer}</div>
    </div>
  );
}
Object.assign(window, { DrawerOverlay });
