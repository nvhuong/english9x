import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react';
import { cn } from '../../design';

/** Card for the sign-in family of pages: one heading, a short description, the form, links underneath. */
export function AuthCard({ title, description, children, footer }: { title: string; description?: string; children: ReactNode; footer?: ReactNode }) {
  return (
    <section className="mx-auto w-full max-w-md rounded-lg border border-line bg-surface p-6 shadow-sm sm:p-8">
      <h1 className="font-serif text-3xl font-bold tracking-tight text-ink">{title}</h1>
      {description && <p className="mt-2 text-muted">{description}</p>}
      <div className="mt-6 flex flex-col gap-4">{children}</div>
      {footer && <div className="mt-6 border-t border-line pt-4 text-sm text-muted">{footer}</div>}
    </section>
  );
}

/** Announced message for a failure that belongs to the whole form. */
export function FormAlert({ children }: { children: ReactNode }) {
  return (
    <p role="alert" className="rounded-md border border-danger bg-danger-bg px-3 py-2 text-sm font-medium text-danger">
      {children}
    </p>
  );
}

export function FormNotice({ children, tone = 'success' }: { children: ReactNode; tone?: 'success' | 'info' }) {
  return (
    <p role="status" className={cn('rounded-md border px-3 py-2 text-sm', tone === 'success' ? 'border-success bg-success-bg text-ink' : 'border-line bg-sunken text-ink')}>
      {children}
    </p>
  );
}

interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'size'> {
  label: ReactNode;
  error?: ReactNode;
}

/** Labelled checkbox with an announced error (the design system has text inputs only). */
export const CheckboxField = forwardRef<HTMLInputElement, CheckboxProps>(function CheckboxField({ label, error, id, ...rest }, ref) {
  const auto = useId();
  const inputId = id ?? auto;
  const errId = `${inputId}-error`;
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-start gap-3">
        <input
          ref={ref}
          id={inputId}
          type="checkbox"
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errId : undefined}
          className="mt-1 size-5 shrink-0 accent-primary"
          {...rest}
        />
        <label htmlFor={inputId} className="text-sm text-ink">
          {label}
        </label>
      </div>
      {error && (
        <p id={errId} role="alert" className="text-sm font-medium text-danger">
          {error}
        </p>
      )}
    </div>
  );
});
