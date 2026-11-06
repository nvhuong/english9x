import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { cn } from './cn';

export type TimerLevel = 'ok' | 'warn5' | 'warn1' | 'expired';

export const WARN_5_MS = 5 * 60_000;
export const WARN_1_MS = 60_000;

export function timerLevel(remainingMs: number): TimerLevel {
  if (remainingMs <= 0) return 'expired';
  if (remainingMs <= WARN_1_MS) return 'warn1';
  if (remainingMs <= WARN_5_MS) return 'warn5';
  return 'ok';
}

export function formatClock(remainingMs: number): string {
  const total = Math.max(0, Math.ceil(remainingMs / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const mm = String(m).padStart(2, '0');
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

export interface TimerProps {
  /** Server-computed end of the section (`ends_at`): epoch ms, ISO string or Date. The client never decides the deadline. */
  deadline: number | string | Date;
  /** `serverNow - clientNow` at the time the deadline was fetched, to correct clock skew. Default 0. */
  serverOffsetMs?: number;
  /** Fired once per threshold crossed (also once at mount if already inside it). */
  onWarn?: (level: 'warn5' | 'warn1') => void;
  onExpire?: () => void;
  /** Clock source; injectable for tests. */
  now?: () => number;
  className?: string;
}

const levelClass: Record<TimerLevel, string> = {
  ok: 'bg-surface text-ink border-edge',
  warn5: 'bg-clock-warn-bg text-clock-warn border-clock-warn',
  warn1: 'bg-clock-danger-bg text-clock-danger border-clock-danger-bg motion-safe:animate-pulse',
  expired: 'bg-clock-danger-bg text-clock-danger border-clock-danger-bg',
};

/**
 * Exam clock. Presentational: counts down to a server deadline, warns at 5 and 1 minutes (text + colour, announced politely
 * once per threshold), never announces every second. Backend wiring is P3.4.
 */
export function Timer({ deadline, serverOffsetMs = 0, onWarn, onExpire, now = Date.now, className }: TimerProps) {
  const { t } = useTranslation();
  const end = new Date(deadline).getTime();
  const remainingNow = () => end - (now() + serverOffsetMs);
  const [remaining, setRemaining] = useState(remainingNow);
  const fired = useRef<Set<TimerLevel>>(new Set());
  const cb = useRef({ onWarn, onExpire });
  useEffect(() => {
    cb.current = { onWarn, onExpire };
  });

  useEffect(() => {
    fired.current = new Set();
    const tick = () => setRemaining(end - (now() + serverOffsetMs));
    tick();
    const id = setInterval(tick, 250);
    return () => clearInterval(id);
  }, [end, serverOffsetMs, now]);

  const level = timerLevel(remaining);
  useEffect(() => {
    if (level === 'ok' || fired.current.has(level)) return;
    fired.current.add(level);
    if (level === 'expired') cb.current.onExpire?.();
    else cb.current.onWarn?.(level);
  }, [level]);

  const message = level === 'ok' ? '' : t(`timer.${level}`);
  return (
    <div className={cn('inline-flex items-center gap-3 rounded-md border-2 px-3 py-1.5', levelClass[level], className)} data-level={level}>
      <div role="timer" aria-label={t('timer.label')}>
        <span aria-hidden="true">{t('timer.label')} </span>
        <time className="font-mono text-xl font-bold tabular-nums" dateTime={`PT${Math.max(0, Math.ceil(remaining / 1000))}S`}>
          {formatClock(remaining)}
        </time>
      </div>
      <span role="status" className={cn('text-sm font-semibold', !message && 'sr-only')}>
        {message}
      </span>
    </div>
  );
}
