import { useCallback, useEffect, useRef, useState } from 'react';
import { examApi } from './examApi';
import type { ExamAttemptView } from './types';

/** Attempts this page load has just started: they are not "resumed". */
const fresh = new Set<string>();
export function markFresh(attemptId: string) {
  fresh.add(attemptId);
}
/** One resume per attempt per page load, shared by a second mount (React strict mode). Every reload of the page is one resume (the server counts them). */
const resumes = new Map<string, Promise<ExamAttemptView>>();
export function resetExamPageState() {
  fresh.clear();
  resumes.clear();
}

export type AttemptLoad = { status: 'loading' } | { status: 'error'; code: number } | { status: 'ready'; view: ExamAttemptView };

/**
 * The attempt for the room. Opened by a reload or a link it is RESUMED (the server counts it and may submit it as interrupted); right after Start it
 * is only read. Later reads (`refresh`) never resume. The view lives in this component, not in the query cache, so a context switch or a cache clear
 * elsewhere cannot trigger another resume.
 */
export function useExamAttempt(attemptId: string) {
  const [load, setLoad] = useState<AttemptLoad>({ status: 'loading' });
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    let p: Promise<ExamAttemptView>;
    if (fresh.has(attemptId)) p = examApi.get(attemptId);
    else {
      p = resumes.get(attemptId) ?? examApi.resume(attemptId);
      resumes.set(attemptId, p);
    }
    p.then(
      (view) => alive.current && setLoad({ status: 'ready', view }),
      (e: unknown) => alive.current && setLoad({ status: 'error', code: (e as { status?: number }).status ?? 0 }),
    );
    return () => {
      alive.current = false;
    };
  }, [attemptId]);

  const setView = useCallback((view: ExamAttemptView) => setLoad({ status: 'ready', view }), []);
  const refresh = useCallback(async () => {
    try {
      setView(await examApi.get(attemptId));
    } catch {
      /* offline: the clock goes on, the next try will do */
    }
  }, [attemptId, setView]);
  return { load, setView, refresh };
}
