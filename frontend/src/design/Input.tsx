import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { cn } from './cn';

export interface InputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'size'> {
  label: ReactNode;
  hint?: ReactNode;
  /** Error message; marks the field invalid and is announced. */
  error?: ReactNode;
}

/** Text input with its label, hint and error wired up for assistive technology. */
export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { label, hint, error, className, id, required, ...rest },
  ref,
) {
  const { t } = useTranslation();
  const auto = useId();
  const inputId = id ?? auto;
  const hintId = `${inputId}-hint`;
  const errId = `${inputId}-error`;
  const describedBy = [hint ? hintId : null, error ? errId : null].filter(Boolean).join(' ') || undefined;
  return (
    <div className={cn('flex flex-col gap-1', className)}>
      <label htmlFor={inputId} className="text-sm font-semibold text-ink">
        {label}
        {required && <span className="ml-1 font-normal text-muted">({t('common.required')})</span>}
      </label>
      {hint && (
        <p id={hintId} className="text-sm text-muted">
          {hint}
        </p>
      )}
      <input
        ref={ref}
        id={inputId}
        required={required}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={cn(
          'min-h-10 rounded-md border bg-surface px-3 text-base text-ink placeholder:text-muted',
          error ? 'border-danger' : 'border-edge',
          'disabled:cursor-not-allowed disabled:opacity-60',
        )}
        {...rest}
      />
      {error && (
        <p id={errId} role="alert" className="text-sm font-medium text-danger">
          {error}
        </p>
      )}
    </div>
  );
});
