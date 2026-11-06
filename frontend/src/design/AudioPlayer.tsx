import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button } from './Button';
import { cn } from './cn';

export interface AudioPlayerProps {
  /** Audio URL issued by the server for this section (played once; not re-issued). */
  src: string;
  /** Try to start by itself. If the browser refuses, a single "Start" button appears. Default true. */
  autoStart?: boolean;
  onStart?: () => void;
  onEnded?: () => void;
  className?: string;
}

type Status = 'idle' | 'playing' | 'ended';

/**
 * Listening audio, exam rules (spec §9, F27): plays ONCE. There is deliberately no pause, seek, replay or speed control;
 * only volume. If the browser pauses/seeks/changes speed, the player undoes it. Presentational: P3.4 wires it to the attempt.
 */
export function AudioPlayer({ src, autoStart = true, onStart, onEnded, className }: AudioPlayerProps) {
  const { t } = useTranslation();
  const ref = useRef<HTMLAudioElement>(null);
  const lastTime = useRef(0);
  const finished = useRef(false);
  const started = useRef(false);
  const [status, setStatus] = useState<Status>('idle');
  const [needsStart, setNeedsStart] = useState(!autoStart);
  const [progress, setProgress] = useState(0);
  const [volume, setVolume] = useState(1);

  const start = () => {
    const el = ref.current;
    if (!el || finished.current) return;
    const p = el.play() as Promise<void> | undefined;
    if (p && typeof p.then === 'function') {
      p.then(() => setNeedsStart(false)).catch(() => setNeedsStart(true));
    } else {
      setNeedsStart(false);
    }
  };

  useEffect(() => {
    if (autoStart) start();
  }, [src, autoStart]);

  return (
    <div role="group" aria-label={t('audio.label')} className={cn('flex flex-col gap-3 rounded-md border border-line bg-surface p-4 text-ink', className)}>
      <audio
        ref={ref}
        src={src}
        preload="auto"
        onContextMenu={(e) => e.preventDefault()}
        onPlay={() => {
          if (finished.current) {
            ref.current?.pause();
            return;
          }
          setStatus('playing');
          setNeedsStart(false);
          if (!started.current) {
            started.current = true;
            onStart?.();
          }
        }}
        onPause={() => {
          // No pausing: resume unless the clip is over.
          if (!finished.current && ref.current && !ref.current.ended) void (ref.current.play() as Promise<void> | undefined)?.catch?.(() => {});
        }}
        onSeeking={() => {
          if (ref.current && !finished.current) ref.current.currentTime = lastTime.current;
        }}
        onRateChange={() => {
          if (ref.current && ref.current.playbackRate !== 1) ref.current.playbackRate = 1;
        }}
        onTimeUpdate={() => {
          const el = ref.current;
          if (!el || el.seeking) return;
          lastTime.current = Math.max(lastTime.current, el.currentTime);
          if (el.duration) setProgress(Math.min(100, Math.round((el.currentTime / el.duration) * 100)));
        }}
        onEnded={() => {
          finished.current = true;
          setStatus('ended');
          setProgress(100);
          onEnded?.();
        }}
      />
      <p role="status" className="font-semibold">
        {status === 'idle' ? (needsStart && autoStart ? t('audio.blocked') : t('audio.idle')) : status === 'playing' ? t('audio.playing') : t('audio.ended')}
      </p>
      {needsStart && status === 'idle' && <Button onClick={start}>{t('audio.start')}</Button>}
      {/* Read-only progress: not a slider, so it cannot be used to seek. */}
      <div role="progressbar" aria-label={t('audio.progress')} aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress} className="h-2 overflow-hidden rounded-full bg-sunken">
        <div className="h-full bg-primary" style={{ width: `${progress}%` }} />
      </div>
      <label className="flex items-center gap-2 text-sm font-semibold">
        {t('audio.volume')}
        <input
          type="range"
          min={0}
          max={1}
          step={0.05}
          value={volume}
          onChange={(e) => {
            const v = Number(e.target.value);
            setVolume(v);
            if (ref.current) ref.current.volume = v;
          }}
          className="accent-primary"
        />
      </label>
    </div>
  );
}
