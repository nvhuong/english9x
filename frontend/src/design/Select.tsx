import * as RadixSelect from '@radix-ui/react-select';
import { useId, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { cn } from './cn';

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

export interface SelectProps {
  label: ReactNode;
  options: readonly SelectOption[];
  value?: string;
  defaultValue?: string;
  onValueChange?: (value: string) => void;
  placeholder?: string;
  error?: ReactNode;
  disabled?: boolean;
  name?: string;
  className?: string;
}

/** Accessible listbox select (Radix): keyboard type-ahead, arrows, Home/End, Escape. */
export function Select({ label, options, placeholder, error, className, ...rest }: SelectProps) {
  const { t } = useTranslation();
  const id = useId();
  const errId = `${id}-error`;
  return (
    <div className={cn('flex flex-col gap-1', className)}>
      <span id={`${id}-label`} className="text-sm font-semibold text-ink">
        {label}
      </span>
      <RadixSelect.Root {...rest}>
        <RadixSelect.Trigger
          id={id}
          aria-labelledby={`${id}-label`}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errId : undefined}
          className={cn(
            'inline-flex min-h-10 items-center justify-between gap-2 rounded-md border bg-surface px-3 text-base text-ink',
            error ? 'border-danger' : 'border-edge',
            'data-[placeholder]:text-muted disabled:cursor-not-allowed disabled:opacity-60',
          )}
        >
          <RadixSelect.Value placeholder={placeholder ?? t('common.selectPlaceholder')} />
          <RadixSelect.Icon aria-hidden="true">▾</RadixSelect.Icon>
        </RadixSelect.Trigger>
        <RadixSelect.Portal>
          <RadixSelect.Content position="popper" sideOffset={4} className="z-50 min-w-[var(--radix-select-trigger-width)] overflow-hidden rounded-md border border-edge bg-surface text-ink shadow-card">
            <RadixSelect.Viewport className="p-1">
              {options.map((o) => (
                <RadixSelect.Item
                  key={o.value}
                  value={o.value}
                  disabled={o.disabled}
                  className="flex min-h-9 cursor-pointer items-center rounded-sm px-3 text-base data-[disabled]:opacity-50 data-[highlighted]:bg-primary data-[highlighted]:text-on-primary"
                >
                  <RadixSelect.ItemText>{o.label}</RadixSelect.ItemText>
                </RadixSelect.Item>
              ))}
            </RadixSelect.Viewport>
          </RadixSelect.Content>
        </RadixSelect.Portal>
      </RadixSelect.Root>
      {error && (
        <p id={errId} role="alert" className="text-sm font-medium text-danger">
          {error}
        </p>
      )}
    </div>
  );
}
