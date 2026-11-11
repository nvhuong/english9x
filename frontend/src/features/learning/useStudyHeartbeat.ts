import { useEffect, useRef } from 'react';

/** Study time counts only while the page is on screen AND the learner did something within this window (spec 8). */
export const INTERACTION_WINDOW_MS = 2 * 60_000;
/** e9x.learning.heartbeat-interval (the default); the server accepts any pace and counts the gaps itself. */
export const HEARTBEAT_INTERVAL_MS = 30_000;

const ACTIVITY_EVENTS = ['pointerdown', 'pointermove', 'keydown', 'scroll', 'touchstart', 'wheel'] as const;

export interface HeartbeatOptions {
  /** Sends one signal; a failure is ignored (the next one follows). */
  send: () => Promise<unknown> | void;
  enabled?: boolean;
  intervalMs?: number;
  windowMs?: number;
  now?: () => number;
}

/**
 * Sends a heartbeat every interval, but only when `document.visibilityState === 'visible'` and the learner has interacted (pointer, key, scroll, touch) within the
 * last two minutes. Opening the page counts as an interaction. A tab that is hidden, or left alone, sends nothing.
 */
export function useStudyHeartbeat({ send, enabled = true, intervalMs = HEARTBEAT_INTERVAL_MS, windowMs = INTERACTION_WINDOW_MS, now = Date.now }: HeartbeatOptions) {
  const sendRef = useRef(send);
  const nowRef = useRef(now);
  useEffect(() => {
    sendRef.current = send;
    nowRef.current = now;
  });

  useEffect(() => {
    if (!enabled) return;
    let last = nowRef.current();
    const touch = () => {
      last = nowRef.current();
    };
    for (const e of ACTIVITY_EVENTS) window.addEventListener(e, touch, { passive: true });
    const timer = window.setInterval(() => {
      if (document.visibilityState !== 'visible') return;
      if (nowRef.current() - last > windowMs) return;
      void Promise.resolve(sendRef.current()).catch(() => undefined);
    }, intervalMs);
    return () => {
      window.clearInterval(timer);
      for (const e of ACTIVITY_EVENTS) window.removeEventListener(e, touch);
    };
  }, [enabled, intervalMs, windowMs]);
}
