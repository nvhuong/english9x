import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ExamHttpError, ExamNetworkError, examApi } from './examApi';
import { OfflineQueue, defaultStore, type QueueStore, type SendResult } from './offlineQueue';
import type { ServerClock } from './serverClock';
import type { Json } from './types';

export type SaveState = 'saved' | 'saving' | 'offline' | 'rejected';

export async function sendWrite(w: { attemptId: string; questionId: string; answer: Json; answeredAt: string }): Promise<SendResult> {
  try {
    await examApi.save(w.attemptId, w.questionId, w.answer, w.answeredAt);
    return 'sent';
  } catch (e) {
    if (e instanceof ExamNetworkError) return 'retry';
    if (e instanceof ExamHttpError) return e.status === 401 || e.status === 408 || e.status === 429 || e.status >= 500 ? 'retry' : 'rejected';
    return 'retry';
  }
}

export interface AnswerSync {
  values: Record<string, Json>;
  setValue(questionId: string, value: Json): void;
  /** Writes everything pending and tries to send it; true when nothing is left waiting. */
  flush(): Promise<boolean>;
  state: SaveState;
  pending: number;
  ready: boolean;
}

/**
 * Autosave of one attempt. Every change is stamped with the time it was written (server clock), then, at the autosave interval, on a change of question
 * (the caller calls `flush`) and when the connection comes back, handed to the offline queue (IndexedDB) and sent in order.
 */
export function useAnswerSync(opts: { attemptId: string; clock: ServerClock; intervalMs: number; saved: Array<{ questionId: string; value: Json }>; store?: QueueStore }): AnswerSync {
  const { attemptId, intervalMs } = opts;
  const clockRef = useRef(opts.clock);
  useEffect(() => {
    clockRef.current = opts.clock;
  });
  const queue = useMemo(() => new OfflineQueue(opts.store ?? defaultStore(), sendWrite), [opts.store]);
  const dirty = useRef(new Map<string, { value: Json; answeredAt: string }>());
  const [values, setValues] = useState<Record<string, Json>>(() => Object.fromEntries(opts.saved.map((s) => [s.questionId, s.value])));
  const [state, setState] = useState<SaveState>('saved');
  const [pending, setPending] = useState(0);
  const [ready, setReady] = useState(false);

  const commit = useCallback(async (): Promise<boolean> => {
    const batch = [...dirty.current.entries()];
    dirty.current.clear();
    try {
      for (const [questionId, d] of batch) await queue.enqueue({ attemptId, questionId, answer: d.value, answeredAt: d.answeredAt });
    } catch {
      for (const [q, d] of batch) if (!dirty.current.has(q)) dirty.current.set(q, d);
      setState('offline');
      return false;
    }
    if (batch.length > 0) setState('saving');
    const r = await queue.flush(attemptId);
    setPending(r.remaining + dirty.current.size);
    if (r.remaining > 0) setState('offline');
    else if (r.rejected > 0) setState('rejected');
    else if (dirty.current.size === 0) setState('saved');
    return r.remaining === 0 && dirty.current.size === 0;
  }, [queue, attemptId]);

  // answers left in the queue by an earlier visit (reload, closed tab) are newer than what the server shows: put them back, then send them
  useEffect(() => {
    let live = true;
    void (async () => {
      try {
        const waiting = await queue.peek(attemptId);
        if (live && waiting.length > 0) {
          setValues((v) => {
            const next = { ...v };
            for (const w of waiting) next[w.questionId] = w.answer;
            return next;
          });
        }
      } catch {
        /* storage unavailable */
      }
      if (live) {
        setReady(true);
        void commit();
      }
    })();
    return () => {
      live = false;
    };
  }, [queue, attemptId, commit]);

  useEffect(() => {
    const id = setInterval(() => void commit(), intervalMs);
    const online = () => void commit();
    const hide = () => void commit();
    window.addEventListener('online', online);
    window.addEventListener('pagehide', hide);
    return () => {
      clearInterval(id);
      window.removeEventListener('online', online);
      window.removeEventListener('pagehide', hide);
    };
  }, [commit, intervalMs]);

  const setValue = useCallback((questionId: string, value: Json) => {
    dirty.current.set(questionId, { value, answeredAt: clockRef.current.iso() });
    setValues((v) => ({ ...v, [questionId]: value }));
    setState((s) => (s === 'offline' ? s : 'saving'));
  }, []);

  return { values, setValue, flush: commit, state, pending, ready };
}
