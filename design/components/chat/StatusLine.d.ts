/** Agent presence line — serif italic + lavender dot, sits under the top bar. @startingPoint section="Chat" subtitle="First-class agent presence line" viewport="700x120" */
export interface StatusLineProps {
  /** default "idle" */
  state?: 'idle' | 'thinking' | 'tool' | 'approval' | 'stopped' | 'offline';
  /** overrides the default state label, e.g. "Running shell command" */
  label?: string;
  /** cycle through THINKING_FLAIR verbs while thinking (default true); false = plain "Thinking…" */
  flair?: boolean;
  style?: React.CSSProperties;
}
