function OnboardingScreen({ onConnect }) {
  const DS = window.TsukaimaDesignSystem_17565f;
  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 10, padding: '0 36px', textAlign: 'center', position: 'relative' }}>
      <div style={{ fontFamily: 'var(--font-kanji)', fontSize: 14, color: 'var(--text-tertiary)', letterSpacing: '.2em', writingMode: 'vertical-rl', position: 'absolute', top: 48, right: 32 }}>使い魔</div>
      <img src="../../assets/mark.svg" alt="" style={{ height: 72, marginBottom: 14 }} />
      <div style={{ font: 'var(--type-wordmark)', fontSize: 38, color: 'var(--text-body)' }}>Tsukaima</div>
      <div style={{ font: 'var(--type-status)', color: 'var(--text-secondary)', marginBottom: 6 }}>Your familiar is listening.</div>
      <div style={{ font: 'var(--type-caption)', color: 'var(--text-tertiary)', maxWidth: 260 }}>Bind this app to your Hermes instance to chat with your agent from anywhere.</div>
      <DS.Button variant="primary" style={{ marginTop: 22 }} onClick={onConnect}>Connect</DS.Button>
      <img src="../../assets/resting-wisp.svg" alt="" style={{ width: 180, maxHeight: '18vh', marginTop: 40, opacity: .8 }} />
    </div>
  );
}
Object.assign(window, { OnboardingScreen });
