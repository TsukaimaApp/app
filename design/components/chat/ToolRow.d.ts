/** Slim collapsible row for tool calls and thinking blocks. */
export interface ToolRowProps {
  /** default "tool" */
  kind?: 'tool' | 'thinking';
  /** row title, e.g. "shell" or "Thought for 12s" */
  name: string;
  /** muted suffix, e.g. the command */
  detail?: string;
  /** default "done" */
  status?: 'running' | 'done' | 'error';
  defaultOpen?: boolean;
  /** expanded content */
  children?: React.ReactNode;
  style?: React.CSSProperties;
}
