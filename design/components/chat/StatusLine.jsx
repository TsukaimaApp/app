import React, { useEffect, useState } from 'react';
export const THINKING_FLAIR = [
  'Thinking…', 'Summoning…', 'Charging ki…', 'Powering up…', 'Channeling…', 'Reading the scroll…', 'Consulting the shrine…',
  'Steeping matcha…', 'Folding cranes…', 'Sharpening the blade…', 'Drawing the seal…', 'Chanting…', 'Gathering spirit energy…',
  'Training arc…', 'Unlocking the next form…', 'Meditating under the waterfall…', 'Rolling the gacha…', 'Inking the next panel…',
  'Grinding XP…', 'Binding the contract…',
];
// one line per title rated 9+ on the maintainer's MAL — allusions, never quotes
export const THINKING_FLAIR_WATCHLIST = [
  'Weighing the oddity…', 'Checking if it\u2019s a fake…', 'Letting the cat out…', 'Taking the long way home…', 'Checking the mirror…',
  'Solving the classroom riddle…', 'Finishing the story…', 'Bleeding a little iron…', 'Reclaiming what was taken…', 'Collecting twelve blades…',
  'Reading the fine print on the wish…', 'Tuning the phone microwave…', 'Lingering in déjà vu…', 'Making a move on the board…', 'Playing the final move…',
  'Walking the tiger home…', 'Suspecting a conspiracy…', 'Holding the line, Sergeant…', 'Guarding the classroom…', 'Granting a five-yen wish…',
  'Settling an old debt…', 'Swapping places for a second…', 'Running the cargo…', 'Reloading both pistols…', 'Following the blood trail…',
  'Polishing the gems…', 'Betting it all on a game…', 'Practicing the broom flight…', 'Serving tea, dragon-style…', 'Casting Explosion, once a day…',
  'Paying off the party\u2019s debt…', 'Filing a complaint with Being X…', 'Crossing through the gate…', 'Starting over from the save point…', 'Keeping a secret hobby…',
  'Finishing the life consultation…', 'Transforming, reluctantly…', 'Rebooting the undead…', 'Tracing the blade…', 'Diving into the net…',
];
const ALL_FLAIR = THINKING_FLAIR.concat(THINKING_FLAIR_WATCHLIST);
const STATES = {
  idle: { label: 'Idle', color: 'var(--text-tertiary)', pulse: false },
  thinking: { label: 'Thinking…', color: 'var(--accent)', pulse: true },
  tool: { label: 'Running tool', color: 'var(--accent)', pulse: true },
  approval: { label: 'Waiting for your approval', color: 'var(--status-warn)', pulse: false },
  stopped: { label: 'Gateway stopped', color: 'var(--status-danger)', pulse: false },
  offline: { label: 'Can\u2019t reach Hermes', color: 'var(--status-danger)', pulse: false },
};
export function StatusLine({ state = 'idle', label, flair = true, style }) {
  const s = STATES[state] || STATES.idle;
  const cycling = state === 'thinking' && !label && flair;
  const [i, setI] = useState(0);
  const [show, setShow] = useState(true);
  useEffect(() => {
    if (!cycling) return;
    setI(0); setShow(true);
    const t = setInterval(() => { setShow(false); setTimeout(() => { setI(n => (n + 1) % ALL_FLAIR.length); setShow(true); }, 180); }, 2600);
    return () => clearInterval(t);
  }, [cycling]);
  const text = label || (cycling ? ALL_FLAIR[i] : s.label);
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, font: 'var(--type-status)', color: 'var(--text-secondary)', padding: '6px var(--gutter-page)', ...style }}>
      <style>{'@keyframes tsk-pulse{0%,100%{opacity:1}50%{opacity:.35}}'}</style>
      <span style={{ width: 7, height: 7, borderRadius: 99, background: s.color, animation: s.pulse ? 'tsk-pulse 2s infinite' : 'none', flexShrink: 0 }}></span>
      <span style={{ opacity: show ? 1 : 0, transform: show ? 'none' : 'translateY(2px)', transition: 'opacity 180ms var(--ease-quiet), transform 180ms var(--ease-quiet)' }}>{text}</span>
    </div>
  );
}
