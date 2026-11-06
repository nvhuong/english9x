import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from './Button';
import { cn } from './cn';

export type RecorderPhase = 'mic-check' | 'denied' | 'ready' | 'preparing' | 'recording' | 'uploading' | 'done' | 'error';

export interface RecorderShellProps {
  phase: RecorderPhase;
  /** Microphone input level 0..1 (mic-check and recording). */
  micLevel?: number;
  /** Seconds left in the preparing / recording phase. */
  secondsLeft?: number;
  /** Length of the current phase, for the progress bar. */
  totalSeconds?: number;
  onMicTest?: () => void;
  onBegin?: () => void;
  onStop?: () => void;
  onRetry?: () => void;
  className?: string;
}

/**
 * Speaking recorder shell (spec §9): mic check, preparation countdown, recording, upload states.
 * Purely presentational: P3.4 owns MediaRecorder, upload and retry, and drives `phase`.
 */
export function RecorderShell({ phase, micLevel = 0, secondsLeft = 0, totalSeconds, onMicTest, onBegin, onStop, onRetry, className }: RecorderShellProps) {
  const { t } = useTranslation();
  const uid = useId();
  const countdown = phase === 'preparing' || phase === 'recording';
  const pct = totalSeconds ? Math.max(0, Math.min(100, Math.round((secondsLeft / totalSeconds) * 100))) : 0;
  const level = Math.round(Math.max(0, Math.min(1, micLevel)) * 100);
  const micOk = level >= 5;

  return (
    <section role="group" aria-label={t('recorder.label')} data-phase={phase} className={cn('flex flex-col gap-4 rounded-md border border-line bg-surface p-5 text-ink', className)}>
      <h2 className="font-serif text-xl font-semibold">{phase === 'mic-check' || phase === 'denied' ? t('recorder.micCheck') : phase === 'preparing' ? t('recorder.preparing') : phase === 'recording' ? t('recorder.recording') : t('recorder.label')}</h2>

      {(phase === 'mic-check' || phase === 'recording') && (
        <div className="flex flex-col gap-1">
          <span id={`${uid}-mic`} className="text-sm font-semibold">
            {t('recorder.micLevel')}
          </span>
          <div role="meter" aria-labelledby={`${uid}-mic`} aria-valuemin={0} aria-valuemax={100} aria-valuenow={level} className="h-3 overflow-hidden rounded-full bg-sunken">
            <div className="h-full bg-primary motion-safe:transition-[width]" style={{ width: `${level}%` }} />
          </div>
        </div>
      )}

      {countdown && (
        <div className="flex flex-col gap-2">
          <p aria-hidden="true" className="font-mono text-4xl font-bold tabular-nums">
            {t('recorder.seconds', { count: secondsLeft })}
          </p>
          <div role="progressbar" aria-label={phase === 'preparing' ? t('recorder.preparing') : t('recorder.recording')} aria-valuemin={0} aria-valuemax={100} aria-valuenow={pct} className="h-2 overflow-hidden rounded-full bg-sunken">
            <div className="h-full bg-accent" style={{ width: `${pct}%` }} />
          </div>
        </div>
      )}

      <p role="status" className={cn('font-medium', (phase === 'denied' || phase === 'error') && 'text-danger')}>
        {phase === 'mic-check' && (micOk ? t('recorder.micOk') : t('recorder.micCheckHelp'))}
        {phase === 'denied' && t('recorder.micDenied')}
        {phase === 'ready' && t('recorder.ready')}
        {phase === 'preparing' && t('recorder.preparingHelp')}
        {phase === 'recording' && t('recorder.recordingHelp')}
        {phase === 'uploading' && t('recorder.uploading')}
        {phase === 'done' && t('recorder.done')}
        {phase === 'error' && t('recorder.error')}
      </p>

      <div className="flex flex-wrap gap-2">
        {phase === 'mic-check' && <Button onClick={onMicTest}>{t('recorder.micTest')}</Button>}
        {phase === 'denied' && <Button onClick={onRetry}>{t('common.retry')}</Button>}
        {phase === 'ready' && <Button variant="accent" onClick={onBegin}>{t('recorder.begin')}</Button>}
        {phase === 'recording' && <Button variant="danger" onClick={onStop}>{t('recorder.stop')}</Button>}
        {phase === 'error' && <Button onClick={onRetry}>{t('common.retry')}</Button>}
      </div>
    </section>
  );
}
