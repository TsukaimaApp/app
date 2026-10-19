/** Full-width rounded composer — attach and send only. One of two shadowed surfaces. @startingPoint section="Chat" subtitle="Rounded composer pill" viewport="700x120" */
export interface ComposerProps {
  /** controlled value; omit for internal state */
  value?: string;
  onChange?: (v: string) => void;
  onSend?: (v: string) => void;
  onAttach?: () => void;
  /** default "Message your agent" */
  placeholder?: string;
  /** offline: greys out, send loses accent, placeholder says Hermes is unreachable */
  disabled?: boolean;
  style?: React.CSSProperties;
}
