/** Raised cream surface, 16dp radius, hairline border. */
export interface CardProps {
  /** soft shadow — approval card / composer tier only */
  floating?: boolean;
  /** px padding, default 16 */
  padding?: number | string;
  children?: React.ReactNode;
  style?: React.CSSProperties;
}
