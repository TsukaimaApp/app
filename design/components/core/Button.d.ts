/** Pill button. Primary (filled accent) is rare — approval, connect. @startingPoint section="Core" subtitle="Pill button — primary accent is rare" viewport="700x180" */
export interface ButtonProps {
  /** default "secondary" */
  variant?: 'primary' | 'secondary' | 'ghost';
  /** default "md" (44dp) */
  size?: 'sm' | 'md';
  /** Material Symbols icon name, leading */
  icon?: string;
  disabled?: boolean;
  /** red ink for destructive secondary/ghost */
  danger?: boolean;
  onClick?: () => void;
  children?: React.ReactNode;
  style?: React.CSSProperties;
}
