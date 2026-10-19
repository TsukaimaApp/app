/** Single-line input with meta label and helper. */
export interface TextFieldProps {
  /** tiny uppercase meta label above */
  label?: string;
  value?: string;
  onChange?: (v: string) => void;
  placeholder?: string;
  /** caption under the field */
  helper?: string;
  /** html input type, default "text" */
  type?: string;
  /** mono font for URLs/tokens */
  mono?: boolean;
  /** textarea instead of input */
  multiline?: boolean;
  /** textarea rows, default 4 */
  rows?: number;
  style?: React.CSSProperties;
}
