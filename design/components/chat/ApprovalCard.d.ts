/** Inline tool-approval request — the one place a filled accent surface is allowed. @startingPoint section="Chat" subtitle="Inline approve/deny card" viewport="700x260" */
export interface ApprovalCardProps {
  /** serif heading, default "Hermes wants to run a tool" */
  title?: string;
  /** tiny uppercase tool label */
  toolName?: string;
  /** the command/payload shown in a code well */
  command?: string;
  onApprove?: () => void;
  onDeny?: () => void;
  style?: React.CSSProperties;
}
