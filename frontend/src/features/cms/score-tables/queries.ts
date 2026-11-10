import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../api/useApi';
import { ctxKey, publicKey, useContextStore } from '../../../app/stores/context';
import type { ExamScores, ScoreTableDraft, ScoreTableFilters, ScoreTablePage, ScoreTableVersion } from './types';

/** The exams of the catalogue with the scores each keeps (the skills a table can be made for). The same in every context. */
export function useExamScores() {
  const api = useApi();
  return useQuery({
    queryKey: publicKey('catalog', 'exam-scores'),
    staleTime: 10 * 60_000,
    queryFn: async () =>
      (((await api.GET('/api/catalog/exams')).data ?? []) as Array<{ code?: string; name?: string; scoreSkills?: string[] }>).flatMap((e): ExamScores[] =>
        e.code ? [{ code: e.code, name: e.name ?? e.code, scoreSkills: e.scoreSkills ?? [] }] : [],
      ),
  });
}

export function useScoreTableList(filters: ScoreTableFilters) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'cms', 'score-tables', 'list', filters),
    queryFn: async () =>
      (await api.GET('/api/cms/score-tables', { params: { query: { exam: filters.exam, status: filters.status, page: filters.page } } })).data as unknown as ScoreTablePage,
  });
}

export function useScoreTable(tableId: string | undefined) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'cms', 'score-tables', 'one', tableId ?? 'none'),
    enabled: Boolean(tableId),
    queryFn: async () => (await api.GET('/api/cms/score-tables/{id}', { params: { path: { id: tableId! } } })).data as unknown as ScoreTableVersion,
  });
}

export function useScoreTableMutations() {
  const api = useApi();
  const queryClient = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  const drop = () => queryClient.invalidateQueries({ queryKey: ctxKey(ctx, 'cms', 'score-tables') });
  return {
    create: useMutation({
      mutationFn: async (draft: ScoreTableDraft) => (await api.POST('/api/cms/score-tables', { body: draft as never })).data as unknown as ScoreTableVersion,
      onSuccess: drop,
    }),
    save: useMutation({
      mutationFn: async ({ tableId, draft }: { tableId: string; draft: ScoreTableDraft }) => {
        const r = await api.PUT('/api/cms/score-tables/{id}', { params: { path: { id: tableId } }, body: draft as never });
        return { version: r.data as unknown as ScoreTableVersion, newVersion: r.response.status === 201 };
      },
      onSuccess: drop,
    }),
    submit: useMutation({
      mutationFn: async (tableId: string) => (await api.POST('/api/cms/score-tables/{id}/submit', { params: { path: { id: tableId } } })).data,
      onSuccess: drop,
    }),
  };
}
