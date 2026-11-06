import type { HTMLAttributes } from 'react';
import { cn } from './cn';

export type BadgeTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger' | 'accent';

const tones: Record<BadgeTone, string> = {
  neutral: 'bg-sunken text-ink border-edge',
  info: 'bg-info-bg text-info border-info',
  success: 'bg-success-bg text-success border-success',
  warning: 'bg-warning-bg text-warning border-warning',
  danger: 'bg-danger-bg text-danger border-danger',
  accent: 'bg-trap-bg text-trap border-accent',
};

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: BadgeTone;
}

/** Status label. Meaning is carried by the text, never by colour alone. */
export function Badge({ tone = 'neutral', className, ...rest }: BadgeProps) {
  return <span className={cn('inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-semibold', tones[tone], className)} {...rest} />;
}
