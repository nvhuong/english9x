import { useId, type ReactNode, type TextareaHTMLAttributes } from 'react';

interface TextAreaProps extends Omit<TextareaHTMLAttributes<HTMLTextAreaElement>, 'onChange'> {
  label: ReactNode;
  hint?: ReactNode;
  error?: ReactNode;
  value: string;
  onValue: (v: string) => void;
}

/** A labelled multi-line text field (the design system has single-line inputs only). */
export function TextArea({ label, hint, error, value, onValue, ...rest }: TextAreaProps) {
  const id = useId();
  const describedBy = [hint ? `${id}-hint` : null, error ? `${id}-error` : null].filter(Boolean).join(' ') || undefined;
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-semibold text-ink">
        {label}
      </label>
      {hint && (
        <p id={`${id}-hint`} className="text-sm text-muted">
          {hint}
        </p>
      )}
      <textarea
        id={id}
        value={value}
        rows={4}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        onChange={(e) => onValue(e.target.value)}
        className="min-h-24 rounded-md border border-edge bg-surface px-3 py-2 text-ink"
        {...rest}
      />
      {error && (
        <p id={`${id}-error`} className="text-sm font-medium text-danger">
          {error}
        </p>
      )}
    </div>
  );
}
