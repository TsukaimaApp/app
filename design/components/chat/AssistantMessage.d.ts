/** Assistant message — flush-left prose on the page background, no bubble. */
export interface AssistantMessageProps {
  children?: React.ReactNode;
  /** show a blinking lavender caret while tokens stream in */
  streaming?: boolean;
  style?: React.CSSProperties;
}
