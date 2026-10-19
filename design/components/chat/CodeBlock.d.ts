/** Dark code block with copy action, rendered inline in chat prose. */
export interface CodeBlockProps {
  code: string;
  /** language label, e.g. "bash" */
  lang?: string;
  style?: React.CSSProperties;
}
