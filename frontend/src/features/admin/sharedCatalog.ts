import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../api/useApi';
import type { components } from '../../api/generated/client';
import { ctxKey, useContextStore } from '../../app/stores/context';
import type { RoleGrant } from '../../app/stores/roles';

export type SharedCatalogQueueItem = components['schemas']['SharedCatalogQueueItem'];

/** The pending centre courses of the shared catalogue (English9x REVIEWER of the exam). */
export function useSharedCatalogQueue() {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'shared-catalog', 'queue'),
    retry: false,
    queryFn: async () => (await api.GET('/api/admin/shared-catalog')).data as SharedCatalogQueueItem[],
  });
}

export function useSharedCatalogDecision() {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  const done = () => qc.invalidateQueries({ queryKey: ctxKey(ctx, 'shared-catalog') });
  return {
    approve: useMutation({
      mutationFn: async (courseId: string) => (await api.POST('/api/admin/shared-catalog/{id}/approve', { params: { path: { id: courseId } } })).data,
      onSuccess: done,
    }),
    reject: useMutation({
      mutationFn: async (courseId: string) => (await api.POST('/api/admin/shared-catalog/{id}/reject', { params: { path: { id: courseId } } })).data,
      onSuccess: done,
    }),
  };
}

/**
 * Does the person review this exam as English9x staff? An English9x-wide REVIEWER grant (org null) for the exam; a centre's own reviewer role or SYS_ADMIN does not count.
 * UI convenience only: the server decides.
 */
export function isEnglish9xReviewer(grants: RoleGrant[] | null, exam: string | undefined): boolean {
  return (grants ?? []).some((g) => g.role === 'REVIEWER' && g.org === null && (g.exam === null || g.exam === exam));
}

/** Is the person a member of the centre that owns the course? They never decide on their own course. */
export function isOwnerMember(grants: RoleGrant[] | null, ownerOrgId: string | undefined): boolean {
  return !!ownerOrgId && (grants ?? []).some((g) => g.org === ownerOrgId);
}
