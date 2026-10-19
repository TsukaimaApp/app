/** Circular icon button. Accent fill only for send. */
export interface IconButtonProps {
  /** Material Symbols name */
  icon: string;
  /** accessible label */
  label: string;
  /** filled accent circle — send button only */
  accent?: boolean;
  /** px, default 44 */
  size?: number;
  onClick?: () => void;
  style?: React.CSSProperties;
}
