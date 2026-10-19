/** M3-style confirm dialog, positioned absolute within the app frame. */
export interface DialogProps {
  open: boolean;
  title: string;
  body?: React.ReactNode;
  /** default "Confirm" */
  confirmLabel?: string;
  /** default "Cancel" */
  cancelLabel?: string;
  /** red-ink outlined confirm instead of filled accent */
  danger?: boolean;
  onConfirm?: () => void;
  onCancel?: () => void;
}
