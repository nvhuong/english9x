import { useEffect, useMemo, useState } from 'react';
import { Timer } from '../../design';

export interface SectionTimerProps {
  /** `endsAt` of the section, as the server says. */
  endsAt: string;
  /** `serverNow` of the same answer: the offset between the server's clock and this computer's is taken from it. */
  serverNow: string;
  onWarn?: (level: 'warn5' | 'warn1') => void;
  onExpire?: () => void;
  /** The tab came back to the front (a background tab runs its timers late): the caller may ask the server again for a fresh `serverNow`. */
  onResync?: () => void;
  /** Monotonic clock; injectable for tests. */
  perfNow?: () => number;
}

/**
 * The clock of a section. Remaining = endsAt - (serverNow + time elapsed since that answer), the elapsed time measured with performance.now()
 * (monotonic: changing the computer's clock or time zone does nothing). Recomputed when the tab becomes visible again; the 5 and 1 minute warnings
 * come from the design-system Timer.
 */
export function SectionTimer({ endsAt, serverNow, onWarn, onExpire, onResync, perfNow = () => performance.now() }: SectionTimerProps) {
  const [version, setVersion] = useState(0);
  // anchored when the pair (serverNow, performance.now()) arrives, and only then
  const anchor = useMemo(() => ({ server: Date.parse(serverNow), perf: perfNow() }), [serverNow, perfNow]);
  // a new function identity (same anchor) makes the Timer recompute at once
  const clock = useMemo(() => {
    void version;
    return () => anchor.server + (perfNow() - anchor.perf);
  }, [anchor, perfNow, version]);

  useEffect(() => {
    const onVisible = () => {
      if (document.visibilityState !== 'visible') return;
      setVersion((v) => v + 1); // a new clock function: the Timer recomputes at once
      onResync?.();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [onResync]);

  return <Timer deadline={endsAt} now={clock} serverOffsetMs={0} onWarn={onWarn} onExpire={onExpire} />;
}
