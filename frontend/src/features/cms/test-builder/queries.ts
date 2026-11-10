import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../api/useApi';
import { ctxKey, publicKey, useContextStore } from '../../../app/stores/context';
import { requestOf } from './model';
import type { ExamInfo, GroupChoice, TestDraft, TestFilters, TestPage, TestVersion } from './types';

/** The exams of the catalogue with their variants and sections (what a test is checked against). The same in every context. */
export function useExams() {
  const api = useApi();
  return useQuery({
    queryKey: publicKey('catalog', 'exams-sections'),
    staleTime: 10 * 60_000,
    queryFn: async () =>
      (((await api.GET('/api/catalog/exams')).data ?? []) as unknown as Array<Partial<ExamInfo>>).flatMap((e): ExamInfo[] =>
        e.code ? [{ code: e.code, name: e.name ?? e.code, variants: e.variants ?? [], sections: e.sections ?? [] }] : [],
      ),
  });
}

export function useTestList(filters: TestFilters) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'cms', 'tests', 'list', filters),
    queryFn: async () => (await api.GET('/api/cms/tests', { params: { query: { exam: filters.exam, status: filters.status, page: filters.page } } })).data as unknown as TestPage,
  });
}

export function useTest(testId: string | undefined) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'cms', 'tests', 'one', testId ?? 'none'),
    enabled: Boolean(testId),
    queryFn: async () => (await api.GET('/api/cms/tests/{id}', { params: { path: { id: testId! } } })).data as unknown as TestVersion,
  });
}

/** The question groups of an exam the author can put in a test: the latest version of each, any status but the retired. */
export function useGroupChoices(exam: string | undefined) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'cms', 'tests', 'group-choices', exam ?? 'none'),
    enabled: Boolean(exam),
    queryFn: async () => {
      const page = (await api.GET('/api/cms/question-groups', { params: { query: { exam, size: 100 } } })).data as unknown as {
        items: Array<{ versionId: string; status: GroupChoice['status']; title: string | null; members: number }>;
      };
      return page.items
        .filter((g) => g.status !== 'ARCHIVED')
        .map((g): GroupChoice => ({ groupVersionId: g.versionId, title: g.title ?? g.versionId, members: g.members, status: g.status }));
    },
  });
}

export function useTestMutations() {
  const api = useApi();
  const queryClient = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  const drop = () => queryClient.invalidateQueries({ queryKey: ctxKey(ctx, 'cms', 'tests') });
  return {
    create: useMutation({
      mutationFn: async (body: { examCode: string; variant: string | null; title: string }) =>
        (await api.POST('/api/cms/tests', { body: body as never })).data as unknown as TestVersion,
      onSuccess: drop,
    }),
    save: useMutation({
      mutationFn: async ({ testId, draft }: { testId: string; draft: TestDraft }) => {
        const r = await api.PUT('/api/cms/tests/{id}/structure', { params: { path: { id: testId } }, body: requestOf(draft) as never });
        return { version: r.data as unknown as TestVersion, newVersion: r.response.status === 201 };
      },
      onSuccess: drop,
    }),
    submit: useMutation({
      mutationFn: async (testId: string) => (await api.POST('/api/cms/tests/{id}/submit', { params: { path: { id: testId } } })).data,
      onSuccess: drop,
    }),
  };
}
