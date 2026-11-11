import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../api/useApi';
import { ctxKey, useContextStore } from '../../app/stores/context';
import type { Detail, QueueItem, ReviewResult } from './types';

const KEY = ['teacher', 'queue'] as const;

/** The submissions the teacher may take (their side's) and the ones they hold. The context is part of the key: a centre's queue is not English9x's. */
export function useQueue(exam: string) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, ...KEY, 'list', exam),
    queryFn: async () =>
      ((await api.GET('/api/teacher/queue', { params: { query: exam ? { exam } : {} } })).data ?? []) as unknown as QueueItem[],
  });
}

export function useClaim() {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  return useMutation({
    mutationFn: async (id: string) =>
      (await api.POST('/api/teacher/queue/{id}/claim', { params: { path: { id } } })).data as unknown as QueueItem,
    // a lost race is a 409 / 404 (it is somebody else's now): the list is read again either way
    onSettled: () => qc.invalidateQueries({ queryKey: ctxKey(ctx, ...KEY) }),
  });
}

export function useRelease() {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  return useMutation({
    mutationFn: async (id: string) =>
      (await api.POST('/api/teacher/queue/{id}/release', { params: { path: { id } } })).data as unknown as QueueItem,
    onSettled: () => qc.invalidateQueries({ queryKey: ctxKey(ctx, ...KEY) }),
  });
}

/** The work of a submission the teacher holds. */
export function useDetail(id: string) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, ...KEY, 'detail', id),
    queryFn: async () => (await api.GET('/api/teacher/queue/{id}', { params: { path: { id } } })).data as unknown as Detail,
  });
}

export interface ReviewInput {
  skill: string;
  criteria: Record<string, number>;
  comment: string;
}

export function useReview(id: string) {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  return useMutation({
    mutationFn: async (input: ReviewInput) =>
      (await api.POST('/api/teacher/queue/{id}/review', { params: { path: { id } }, body: input })).data as unknown as ReviewResult,
    onSuccess: () => qc.invalidateQueries({ queryKey: ctxKey(ctx, ...KEY) }),
  });
}

/** The recording of one answer, fetched with the session's token (an audio element cannot send it) and handed back as an object URL. */
export function useRecording(id: string, questionId: string, enabled: boolean) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, ...KEY, 'recording', id, questionId),
    enabled,
    staleTime: Infinity,
    queryFn: async () => {
      const res = await api.GET('/api/teacher/queue/{id}/recordings/{questionId}', { params: { path: { id, questionId } }, parseAs: 'blob' });
      return URL.createObjectURL(res.data as unknown as Blob);
    },
  });
}
