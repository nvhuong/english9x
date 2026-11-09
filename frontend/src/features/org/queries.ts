import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiProblemError } from '../../api/problem';
import { useApi } from '../../api/useApi';
import type { components } from '../../api/generated/client';
import { useAuthStore } from '../../app/stores/auth';
import { ctxKey, publicKey, useContextStore } from '../../app/stores/context';
import { choicesToRoles } from './roles';

export type MemberView = components['schemas']['MemberView'];
export type InvitationView = components['schemas']['InvitationView'];
export type AdminOrgView = components['schemas']['AdminOrgView'];
export type ExamInfo = components['schemas']['ExamInfo'];

/** The wire shape of a role: `examCode` is omitted, never null, for roles that apply to every exam. */
export const toWire = (keys: readonly string[]) =>
  choicesToRoles(keys).map((r) => (r.examCode ? { role: r.role, examCode: r.examCode } : { role: r.role }));

/**
 * The exams of the catalogue (GET /api/catalog/exams): the same in every context, so a public key. Where a screen lets somebody choose
 * an exam it asks here; there is no list of exams in the SPA.
 */
export function useExamCodes() {
  const api = useApi();
  return useQuery({
    queryKey: publicKey('catalog', 'exams'),
    staleTime: 5 * 60_000,
    queryFn: async () => ((await api.GET('/api/catalog/exams')).data as ExamInfo[]).flatMap((e) => (e.code ? [e.code] : [])),
  });
}

/** Every organisation (SYS_ADMIN). Context-keyed like everything the server answers per context. */
export function useAllOrganizations() {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  const parts = ['org', 'all'] as const;
  return useQuery({
    queryKey: ctxKey(ctx, ...parts),
    queryFn: async () => (await api.GET('/api/admin/organizations')).data as AdminOrgView[],
  });
}

export function useMembers(orgId: string) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  const parts = ['org', orgId, 'members'] as const;
  return useQuery({
    queryKey: ctxKey(ctx, ...parts),
    retry: false,
    queryFn: async () => (await api.GET('/api/orgs/{id}/role-grants', { params: { path: { id: orgId } } })).data!.members,
  });
}

export function useSetRoles(orgId: string) {
  const api = useApi();
  const queryClient = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  const parts = ['org', orgId, 'members'] as const;
  return useMutation({
    mutationFn: async (v: { userId: string; keys: string[] }) =>
      (await api.PUT('/api/orgs/{id}/role-grants', { params: { path: { id: orgId } }, body: { userId: v.userId, roles: toWire(v.keys) } })).data,
    onSuccess: () => {
      // an admin may have changed their own roles: the role store follows the server
      void queryClient.invalidateQueries({ queryKey: publicKey('me', 'context', useAuthStore.getState().user?.id ?? null) });
      return queryClient.invalidateQueries({ queryKey: ctxKey(ctx, ...parts) });
    },
  });
}

/** Invitations of an organisation, or of English9x staff when `orgId` is null. */
export function useInvitations(orgId: string | null) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  const parts = ['org', orgId ?? 'staff', 'invitations'] as const;
  return useQuery({
    queryKey: ctxKey(ctx, ...parts),
    queryFn: async () =>
      orgId
        ? ((await api.GET('/api/orgs/{id}/invitations', { params: { path: { id: orgId } } })).data as InvitationView[])
        : ((await api.GET('/api/admin/invitations')).data as InvitationView[]),
  });
}

export function useInvite(orgId: string | null) {
  const api = useApi();
  const queryClient = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  const parts = ['org', orgId ?? 'staff', 'invitations'] as const;
  return useMutation({
    mutationFn: async (v: { email: string; keys: string[] }) => {
      const body = { email: v.email, roles: toWire(v.keys) };
      return orgId
        ? (await api.POST('/api/orgs/{id}/invitations', { params: { path: { id: orgId } }, body })).data
        : (await api.POST('/api/admin/invitations', { body })).data;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ctxKey(ctx, ...parts) }),
  });
}

export function useRevokeInvitation(orgId: string | null) {
  const api = useApi();
  const queryClient = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  const parts = ['org', orgId ?? 'staff', 'invitations'] as const;
  return useMutation({
    mutationFn: async (invitationId: string) =>
      orgId
        ? api.DELETE('/api/orgs/{id}/invitations/{invitationId}', { params: { path: { id: orgId, invitationId } } })
        : api.DELETE('/api/admin/invitations/{invitationId}', { params: { path: { invitationId } } }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ctxKey(ctx, ...parts) }),
    // it expired or was revoked a moment ago: what the list shows is out of date, so show what is true now
    onError: (err) => {
      if (err instanceof ApiProblemError && err.status === 404) void queryClient.invalidateQueries({ queryKey: ctxKey(ctx, ...parts) });
    },
  });
}

export function useCreateOrganization() {
  const api = useApi();
  const queryClient = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  const parts = ['org', 'all'] as const;
  return useMutation({
    mutationFn: async (body: { name: string; type: 'CENTER' | 'ENTERPRISE' }) => (await api.POST('/api/admin/organizations', { body })).data,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ctxKey(ctx, ...parts) }),
  });
}
