import { useQueries } from '@tanstack/react-query';
import { useMemo } from 'react';
import { useApi } from '../../../api/useApi';
import { useAuthStore } from '../../../app/stores/auth';
import { ctxKey, useContextStore } from '../../../app/stores/context';
import { useRoleStore } from '../../../app/stores/roles';
import { narrowPage } from '../questions/narrow';
import type { QuestionSummary } from '../questions/types';
import { buildQueue, plan, type Queue } from './model';

const PAGE = 100;

/**
 * "Hàng việc của tôi": one list request per (exam, status) the person's roles call for, so the server does the scoping to the exams and the status, and the
 * model sorts the answers into the three queues. The context and the person are part of every key; a switch of context drops them.
 */
export function useWorkQueue(): { queue: Queue; isPending: boolean; isError: boolean; refetch: () => void; empty: boolean; planned: number } {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  const grants = useRoleStore((s) => s.grants);
  const userId = useAuthStore((s) => s.user?.id);
  const requests = useMemo(() => plan(grants, ctx), [grants, ctx]);
  const results = useQueries({
    queries: requests.map((r) => ({
      queryKey: ctxKey(ctx, 'cms', 'questions', 'queue', userId ?? 'anonymous', r.exam, r.status),
      enabled: Boolean(userId),
      queryFn: async (): Promise<QuestionSummary[]> =>
        narrowPage((await api.GET('/api/cms/questions', { params: { query: { exam: r.exam, status: r.status, size: PAGE } } })).data).items,
    })),
  });
  const items = results.flatMap((r) => r.data ?? []);
  const queue = buildQueue(items, userId, grants, ctx);
  return {
    queue,
    isPending: requests.length > 0 && results.some((r) => r.isPending),
    isError: results.some((r) => r.isError),
    refetch: () => results.forEach((r) => void r.refetch()),
    empty: queue.drafts.length + queue.changes.length + queue.review.length === 0,
    planned: requests.length,
  };
}
