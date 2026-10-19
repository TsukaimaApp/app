/** Pill chip — model/profile picker, selections. Selected = pale lavender tint. */
export interface ChipProps {
  label: string;
  /** leading Material Symbols name */
  icon?: string;
  /** trailing Material Symbols name, e.g. "expand_more" */
  trailingIcon?: string;
  /** pale lavender tint fill — the only use of the tint */
  selected?: boolean;
  onClick?: () => void;
  style?: React.CSSProperties;
}
