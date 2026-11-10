import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../api/useApi';
import { ctxKey, useContextStore } from '../../../app/stores/context';
import type { LessonDraft, LessonFilters, LessonPage, LessonVersion } from './types';

export function useLessonList(filters: LessonFilters) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'cms', 'lessons', 'list', filters),
    queryFn: async () =>
      (await api.GET('/api/cms/strategy-lessons', { params: { query: { exam: filters.exam, status: filters.status, page: filters.page } } }))
        .data as unknown as LessonPage,
  });
}

export function useLesson(lessonId: string | undefined) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'cms', 'lessons', 'one', lessonId ?? 'none'),
    enabled: Boolean(lessonId),
    queryFn: async () => (await api.GET('/api/cms/strategy-lessons/{id}', { params: { path: { id: lessonId! } } })).data as unknown as LessonVersion,
  });
}

export function useLessonMutations() {
  const api = useApi();
  const queryClient = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  const drop = () => queryClient.invalidateQueries({ queryKey: ctxKey(ctx, 'cms', 'lessons') });
  return {
    create: useMutation({
      mutationFn: async (draft: LessonDraft) => (await api.POST('/api/cms/strategy-lessons', { body: draft as never })).data as unknown as LessonVersion,
      onSuccess: drop,
    }),
    save: useMutation({
      mutationFn: async ({ lessonId, draft }: { lessonId: string; draft: LessonDraft }) => {
        const r = await api.PUT('/api/cms/strategy-lessons/{id}', { params: { path: { id: lessonId } }, body: draft as never });
        return { version: r.data as unknown as LessonVersion, newVersion: r.response.status === 201 };
      },
      onSuccess: drop,
    }),
    submit: useMutation({
      mutationFn: async (lessonId: string) => (await api.POST('/api/cms/strategy-lessons/{id}/submit', { params: { path: { id: lessonId } } })).data,
      onSuccess: drop,
    }),
  };
}
