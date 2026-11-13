import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../api/useApi';
import { ctxKey, publicKey, useContextStore } from '../../../app/stores/context';
import { requestOf } from './model';
import type { CourseDraft, CourseFilters, CoursePage, CourseVersion, RefChoice } from './types';

export function useExamCodes() {
  const api = useApi();
  return useQuery({
    queryKey: publicKey('catalog', 'exam-codes'),
    staleTime: 10 * 60_000,
    queryFn: async () => (((await api.GET('/api/catalog/exams')).data ?? []) as unknown as Array<{ code?: string }>).flatMap((e) => (e.code ? [e.code] : [])),
  });
}

export function useCourseList(filters: CourseFilters) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'cms', 'courses', 'list', filters),
    queryFn: async () => (await api.GET('/api/cms/courses', { params: { query: { exam: filters.exam, status: filters.status, page: filters.page } } })).data as unknown as CoursePage,
  });
}

export function useCourse(courseId: string | undefined) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'cms', 'courses', 'one', courseId ?? 'none'),
    enabled: Boolean(courseId),
    queryFn: async () => (await api.GET('/api/cms/courses/{id}', { params: { path: { id: courseId! } } })).data as unknown as CourseVersion,
  });
}

/** The strategy lessons and tests of an exam an author can put in a course: the latest version of each, any status but the retired. */
export function useRefChoices(exam: string | undefined) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'cms', 'courses', 'ref-choices', exam ?? 'none'),
    enabled: Boolean(exam),
    queryFn: async () => {
      type Item = { versionId: string; status: RefChoice['status']; title: string };
      const lessons = (await api.GET('/api/cms/strategy-lessons', { params: { query: { exam } } })).data as unknown as { items: Item[] };
      const tests = (await api.GET('/api/cms/tests', { params: { query: { exam } } })).data as unknown as { items: Item[] };
      const pick = (kind: RefChoice['kind']) => (i: Item): RefChoice => ({ refId: i.versionId, title: i.title, kind, status: i.status });
      return [...lessons.items.filter((i) => i.status !== 'ARCHIVED').map(pick('LESSON')), ...tests.items.filter((i) => i.status !== 'ARCHIVED').map(pick('TEST'))];
    },
  });
}

export function useCourseMutations() {
  const api = useApi();
  const queryClient = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  const drop = () => queryClient.invalidateQueries({ queryKey: ctxKey(ctx, 'cms', 'courses') });
  return {
    create: useMutation({
      mutationFn: async (draft: CourseDraft) => (await api.POST('/api/cms/courses', { body: requestOf(draft, true) as never })).data as unknown as CourseVersion,
      onSuccess: drop,
    }),
    save: useMutation({
      mutationFn: async ({ courseId, draft }: { courseId: string; draft: CourseDraft }) => {
        const r = await api.PUT('/api/cms/courses/{id}', { params: { path: { id: courseId } }, body: requestOf(draft, false) as never });
        return { version: r.data as unknown as CourseVersion, newVersion: r.response.status === 201 };
      },
      onSuccess: drop,
    }),
    submit: useMutation({
      mutationFn: async (courseId: string) => (await api.POST('/api/cms/courses/{id}/submit', { params: { path: { id: courseId } } })).data,
      onSuccess: drop,
    }),
    share: useMutation({
      mutationFn: async (courseId: string) => (await api.POST('/api/cms/courses/{id}/share', { params: { path: { id: courseId } } })).data as unknown as CourseVersion,
      onSuccess: drop,
    }),
    withdrawShare: useMutation({
      mutationFn: async (courseId: string) => (await api.POST('/api/cms/courses/{id}/share/withdraw', { params: { path: { id: courseId } } })).data as unknown as CourseVersion,
      onSuccess: drop,
    }),
  };
}
