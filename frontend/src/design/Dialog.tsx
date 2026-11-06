import * as RadixDialog from '@radix-ui/react-dialog';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from './Button';

export interface DialogProps {
  /** Element that opens the dialog (rendered as the trigger; must accept a ref). Omit when controlling `open`. */
  trigger?: ReactNode;
  title: string;
  description?: string;
  children?: ReactNode;
  /** Buttons row. */
  footer?: ReactNode;
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
}

/** Modal dialog: focus trapped and restored, Escape closes, title and description wired as the accessible name/description. */
export function Dialog({ trigger, title, description, children, footer, open, onOpenChange }: DialogProps) {
  const { t } = useTranslation();
  return (
    <RadixDialog.Root open={open} onOpenChange={onOpenChange}>
      {trigger && <RadixDialog.Trigger asChild>{trigger}</RadixDialog.Trigger>}
      <RadixDialog.Portal>
        <RadixDialog.Overlay className="fixed inset-0 z-40 bg-ink/50" />
        <RadixDialog.Content className="fixed left-1/2 top-1/2 z-50 w-[min(92vw,32rem)] -translate-x-1/2 -translate-y-1/2 rounded-lg border border-line bg-surface p-6 text-ink shadow-card">
          <RadixDialog.Title className="pr-10 font-serif text-xl font-semibold">{title}</RadixDialog.Title>
          {description ? (
            <RadixDialog.Description className="mt-2 text-muted">{description}</RadixDialog.Description>
          ) : (
            <RadixDialog.Description className="sr-only">{title}</RadixDialog.Description>
          )}
          {children && <div className="mt-4">{children}</div>}
          {footer && <div className="mt-6 flex flex-wrap justify-end gap-2">{footer}</div>}
          <RadixDialog.Close asChild>
            <Button variant="ghost" size="sm" aria-label={t('common.close')} className="absolute right-3 top-3">
              <span aria-hidden="true">✕</span>
            </Button>
          </RadixDialog.Close>
        </RadixDialog.Content>
      </RadixDialog.Portal>
    </RadixDialog.Root>
  );
}
