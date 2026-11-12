import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../api/useApi';
import { ApiProblemError } from '../../api/problem';
import { useCtxKey } from '../../app/stores/context';
import type { AttemptResult, Weakness } from './types';

/** The result of one attempt. While the server says SCORING the page asks again every few seconds (the scoring runs after the late-sync grace). */
export function useAttemptResult(attemptId: string) {
  const api = useApi();
  return useQuery({
    queryKey: useCtxKey('results', 'attempt', attemptId),
    retry: (count, err) => !(err instanceof ApiProblemError) && count < 2,
    refetchInterval: (q) => (q.state.data?.state === 'SCORING' ? 5000 : false),
    queryFn: async () => (await api.GET('/api/results/attempts/{id}', { params: { path: { id: attemptId } } })).data as unknown as AttemptResult,
  });
}

/** The share of right answers per question type over my scored attempts, and the method lessons to open. */
export function useWeakness(exam: string) {
  const api = useApi();
  return useQuery({
    queryKey: useCtxKey('results', 'weakness', exam),
    retry: false,
    queryFn: async () => (await api.GET('/api/results/weakness', { params: { query: exam ? { exam } : {} } })).data as unknown as Weakness,
  });
}
