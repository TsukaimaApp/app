/** Toggle switch, accent track when on. */
export interface SwitchProps {
  checked?: boolean;
  onChange?: (v: boolean) => void;
  /** optional inline label before the track */
  label?: React.ReactNode;
  style?: React.CSSProperties;
}
