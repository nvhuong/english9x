import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../api/useApi';
import { ctxKey, useContextStore } from '../../../app/stores/context';
import type { Member, QueueLine, ReviewDetail } from './types';

const KEY = ['cms', 'reviews'] as const;

export function useReviewQueue() {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, ...KEY, 'queue'),
    queryFn: async () => ((await api.GET('/api/cms/reviews', { params: { query: { limit: 100 } } })).data ?? []) as unknown as QueueLine[],
  });
}

export function useReview(id: string) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, ...KEY, 'one', id),
    queryFn: async () => (await api.GET('/api/cms/reviews/{reviewId}', { params: { path: { reviewId: id } } })).data as unknown as ReviewDetail,
  });
}

export function useBundleMembers(bundleId: string) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, ...KEY, 'bundle', bundleId),
    queryFn: async () =>
      ((await api.GET('/api/cms/reviews/bundles/{bundleId}/members', { params: { path: { bundleId }, query: { limit: 200 } } })).data ?? []) as unknown as Member[],
  });
}

export type Decision = { kind: 'approve' } | { kind: 'changes'; comment: string };

/** Approve or send back one review (`bundle` false) or a whole bundle (`bundle` true). Every cached review list and detail is dropped afterwards. */
export function useDecide(id: string, bundle: boolean) {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  return useMutation({
    mutationFn: async (d: Decision) => {
      if (bundle) {
        const params = { path: { bundleId: id } };
        if (d.kind === 'approve') await api.POST('/api/cms/reviews/bundles/{bundleId}/approve', { params });
        else await api.POST('/api/cms/reviews/bundles/{bundleId}/request-changes', { params, body: { comment: d.comment } });
      } else {
        const params = { path: { reviewId: id } };
        if (d.kind === 'approve') await api.POST('/api/cms/reviews/{reviewId}/approve', { params });
        else await api.POST('/api/cms/reviews/{reviewId}/request-changes', { params, body: { comment: d.comment } });
      }
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ctxKey(ctx, ...KEY) }),
  });
}
