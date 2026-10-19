/** Navigation drawer shell; groups conversations by Hermes profile via DrawerLabel. */
export interface DrawerProps {
  /** wordmark / title area */
  header?: React.ReactNode;
  /** scrolling list — DrawerLabel + ConversationItem rows */
  children?: React.ReactNode;
  /** pinned bottom area (settings row) */
  footer?: React.ReactNode;
  /** px, default 300 */
  width?: number;
  style?: React.CSSProperties;
}
