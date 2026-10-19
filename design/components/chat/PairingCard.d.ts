/** Inline pairing request (/api/pairing) — appears in chat and on the Status home. */
export interface PairingCardProps {
  /** messaging platform, default "Telegram" */
  platform?: string;
  /** who is asking, e.g. "@mario · 184 229 310" */
  who?: string;
  /** the pairing code shown to the requester */
  code?: string;
  onApprove?: () => void;
  onRevoke?: () => void;
  style?: React.CSSProperties;
}
