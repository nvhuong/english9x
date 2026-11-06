import type { ReactNode } from 'react';
import { cn } from './cn';

export interface EmptyStateProps {
  title: string;
  description?: string;
  /** Decorative icon/illustration (hidden from assistive technology). */
  icon?: ReactNode;
  /** Call to action. */
  action?: ReactNode;
  /** Heading level to keep the page outline valid. Default 2. */
  headingLevel?: 1 | 2 | 3;
  /** Announce as an alert (e.g. access denied). */
  alert?: boolean;
  className?: string;
}

export function EmptyState({ title, description, icon, action, headingLevel = 2, alert, className }: EmptyStateProps) {
  const Heading = `h${headingLevel}` as const;
  return (
    <section role={alert ? 'alert' : undefined} className={cn('mx-auto flex max-w-xl flex-col items-center gap-3 px-4 py-12 text-center', className)}>
      {icon && (
        <div aria-hidden="true" className="text-5xl text-muted">
          {icon}
        </div>
      )}
      <Heading className="font-serif text-2xl font-semibold text-ink">{title}</Heading>
      {description && <p className="text-muted">{description}</p>}
      {action && <div className="mt-2">{action}</div>}
    </section>
  );
}
