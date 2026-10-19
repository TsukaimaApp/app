/** Drawer row — conversation, or utility row (settings) with an icon. Selected = lavender tint. */
export interface ConversationItemProps {
  title: string;
  /** trailing caption, e.g. "2h" */
  meta?: string;
  /** optional leading Material Symbols name */
  icon?: string;
  selected?: boolean;
  onClick?: () => void;
  /** 480ms hold or right-click → rename/archive/delete menu */
  onLongPress?: () => void;
  style?: React.CSSProperties;
}
