import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../api/useApi';
import { ctxKey, useContextStore } from '../../../app/stores/context';
import { narrowPage } from './narrow';
import type { QuestionDraft, QuestionFilters, QuestionTypeForm, QuestionVersion } from './types';

/**
 * The forms of the question types the person may write or review in this context. The list depends on the grants, which depend on the context, so
 * the key is the context's. (The schemas themselves are the same everywhere; the list is what differs.)
 */
export function useQuestionTypeForms() {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'cms', 'question-types'),
    staleTime: 10 * 60_000,
    queryFn: async () => ((await api.GET('/api/cms/question-types', {})).data ?? []) as unknown as QuestionTypeForm[],
  });
}

export function useQuestionList(filters: QuestionFilters) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'cms', 'questions', 'list', filters),
    queryFn: async () =>
      narrowPage((await api.GET('/api/cms/questions', { params: { query: { exam: filters.exam, type: filters.type, status: filters.status, page: filters.page, size: filters.size } } })).data),
  });
}

export function useQuestion(questionId: string | undefined) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'cms', 'questions', 'one', questionId ?? 'none'),
    enabled: Boolean(questionId),
    queryFn: async () => (await api.GET('/api/cms/questions/{id}', { params: { path: { id: questionId! } } })).data as unknown as QuestionVersion,
  });
}

/** Creating, saving and submitting. Each one drops the cached lists and the question, so the next read is the server's. */
export function useQuestionMutations() {
  const api = useApi();
  const queryClient = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  const drop = () => queryClient.invalidateQueries({ queryKey: ctxKey(ctx, 'cms', 'questions') });
  return {
    create: useMutation({
      mutationFn: async (draft: QuestionDraft) => (await api.POST('/api/cms/questions', { body: draft })).data as unknown as QuestionVersion,
      onSuccess: drop,
    }),
    save: useMutation({
      mutationFn: async ({ questionId, draft }: { questionId: string; draft: QuestionDraft }) => {
        const r = await api.PUT('/api/cms/questions/{id}', { params: { path: { id: questionId } }, body: draft });
        return { version: r.data as unknown as QuestionVersion, newVersion: r.response.status === 201 };
      },
      onSuccess: drop,
    }),
    submit: useMutation({
      mutationFn: async (questionId: string) => (await api.POST('/api/cms/questions/{id}/submit', { params: { path: { id: questionId } } })).data,
      onSuccess: drop,
    }),
  };
}
