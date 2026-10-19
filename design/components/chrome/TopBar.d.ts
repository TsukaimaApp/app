/** Top bar: menu, model/profile chip left, overflow right. No title, no tabs. */
export interface TopBarProps {
  /** chip label, default "Hermes 4" */
  model?: string;
  /** hide the drawer button when a persistent list pane is shown (wide layouts). default true */
  showMenu?: boolean;
  onMenu?: () => void;
  onModel?: () => void;
  onOverflow?: () => void;
  style?: React.CSSProperties;
}
