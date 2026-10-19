/** Material Symbols Rounded glyph. @startingPoint section="Core" subtitle="Material Symbols Rounded glyph wrapper" viewport="700x150" */
export interface IconProps {
  /** Material Symbols name, e.g. "arrow_upward" */
  name: string;
  /** px size, default 24 */
  size?: number;
  /** CSS color; defaults to inherit */
  color?: string;
  /** filled cut, default false */
  filled?: boolean;
  style?: React.CSSProperties;
}
