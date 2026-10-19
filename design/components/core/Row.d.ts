/** General list row — settings, cron jobs, status facts. Long-press opens context menus. */
export interface RowProps {
  /** leading Material Symbols name */
  icon?: string;
  title: string;
  subtitle?: string;
  /** trailing caption */
  meta?: string;
  /** trailing element, e.g. <Switch /> */
  trailing?: React.ReactNode;
  /** trailing Material Symbols name, e.g. "chevron_right" */
  trailingIcon?: string;
  onClick?: () => void;
  /** 480ms hold or right-click */
  onLongPress?: () => void;
  selected?: boolean;
  danger?: boolean;
  style?: React.CSSProperties;
}
