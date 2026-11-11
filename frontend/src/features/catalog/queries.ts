import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../api/useApi';
import { ctxKey, publicKey, useContextStore } from '../../app/stores/context';
import type { CatalogCourse, CourseOutline } from './types';

/** The public list: PUBLISHED English9x courses and the approved shared ones; the same for everybody, so it survives a context switch. */
export function useCatalog(exam: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: publicKey('catalog', 'courses', exam ?? 'all'),
    queryFn: async () => ((await api.GET('/api/catalog/courses', { params: { query: { exam } } })).data ?? []) as unknown as CatalogCourse[],
  });
}

/** One course by code. Row-level security lets a person see their own organisation's course here too, so the answer depends on the context. */
export function useCourseOutline(code: string | undefined) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'catalog', 'course', code ?? 'none'),
    enabled: Boolean(code),
    retry: false,
    queryFn: async () => (await api.GET('/api/catalog/courses/{code}', { params: { path: { code: code! } } })).data as unknown as CourseOutline,
  });
}
