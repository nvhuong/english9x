import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../api/useApi';
import { ctxKey, useContextStore } from '../../app/stores/context';
import type { AttemptResultData, ChapterMapData, ChapterTestData, LessonData, PracticeResultData } from './types';

/** The person's time zone as the browser knows it: the server cuts the days of the study streak in it. */
export function browserTimeZone(): string | undefined {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone;
  } catch {
    return undefined;
  }
}

export function useChapterMap(courseId: string | undefined) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'learning', 'map', courseId ?? 'none'),
    enabled: Boolean(courseId),
    retry: false,
    queryFn: async () => (await api.GET('/api/learning/courses/{courseId}/map', { params: { path: { courseId: courseId! } } })).data as unknown as ChapterMapData,
  });
}

export function useLesson(lessonId: string | undefined) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'learning', 'lesson', lessonId ?? 'none'),
    enabled: Boolean(lessonId),
    retry: false,
    queryFn: async () => (await api.GET('/api/learning/lessons/{id}', { params: { path: { id: lessonId! } } })).data as unknown as LessonData,
  });
}

export function useChapterTest(lessonId: string | undefined) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'learning', 'chapter-test', lessonId ?? 'none'),
    enabled: Boolean(lessonId),
    retry: false,
    queryFn: async () => (await api.GET('/api/learning/chapter-tests/{id}', { params: { path: { id: lessonId! } } })).data as unknown as ChapterTestData,
  });
}

export function useCompleteLesson(lessonId: string) {
  const api = useApi();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const tz = browserTimeZone();
      return (await api.POST('/api/learning/lessons/{id}/complete', { params: { path: { id: lessonId }, header: tz ? { 'X-Time-Zone': tz } : {} } })).data;
    },
    onSuccess: () => qc.invalidateQueries({ predicate: (q) => q.queryKey[2] === 'learning' }),
  });
}

export function useSubmitAttempt(lessonId: string) {
  const api = useApi();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (answers: { questionVersionId: string; answer: string }[]) => {
      const tz = browserTimeZone();
      return (await api.POST('/api/learning/chapter-tests/{id}/attempts', {
        params: { path: { id: lessonId }, header: tz ? { 'X-Time-Zone': tz } : {} },
        body: { answers },
      })).data as unknown as AttemptResultData;
    },
    onSuccess: () => qc.invalidateQueries({ predicate: (q) => q.queryKey[2] === 'learning' }),
  });
}

/** Practice: the server's verdict on one answer, with the answer and the explanation (the one place a learner is shown them). */
export function practiceRequest(api: ReturnType<typeof useApi>, lessonId: string) {
  return async (questionVersionId: string, answer: string): Promise<PracticeResultData> =>
    (await api.POST('/api/learning/lessons/{id}/practice', { params: { path: { id: lessonId } }, body: { questionVersionId, answer } })).data as unknown as PracticeResultData;
}

export async function sendHeartbeat(api: ReturnType<typeof useApi>, courseId: string, lessonId: string) {
  await api.POST('/api/learning/heartbeat', { body: { courseId, lessonId, timeZone: browserTimeZone() } });
}
