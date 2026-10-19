/** Long-press context menu as a bottom sheet (phone) within the app frame. */
export interface MenuProps {
  open: boolean;
  /** tiny uppercase label naming the target, e.g. the session title */
  title?: string;
  items?: { icon?: string; label: string; danger?: boolean; onClick?: () => void }[];
  onClose?: () => void;
}
