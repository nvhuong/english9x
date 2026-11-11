import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ApiProblemError, type ProblemBody } from '../../../api/problem';
import { useApi } from '../../../api/useApi';
import type { components } from '../../../api/generated/client';
import { useAuthStore } from '../../../app/stores/auth';
import { ctxKey, publicKey, useContextStore } from '../../../app/stores/context';
import { refreshSession } from '../../auth/session';

export type JoinCodeView = components['schemas']['JoinCodeView'];
export type JoinCodeCreated = components['schemas']['JoinCodeCreated'];

export function useJoinCodes(orgId: string) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'org', orgId, 'join-codes'),
    retry: false,
    queryFn: async () => (await api.GET('/api/orgs/{id}/join-codes', { params: { path: { id: orgId } } })).data as JoinCodeView[],
  });
}

export function useCreateJoinCode(orgId: string) {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  return useMutation({
    mutationFn: async (v: { expiresAt: string; maxUses: number }) =>
      (await api.POST('/api/orgs/{id}/join-codes', { params: { path: { id: orgId } }, body: v })).data as JoinCodeCreated,
    onSuccess: () => qc.invalidateQueries({ queryKey: ctxKey(ctx, 'org', orgId, 'join-codes') }),
  });
}

export function useRevokeJoinCode(orgId: string) {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  return useMutation({
    mutationFn: async (codeId: string) => {
      await api.DELETE('/api/orgs/{id}/join-codes/{codeId}', { params: { path: { id: orgId, codeId } } });
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ctxKey(ctx, 'org', orgId, 'join-codes') }),
  });
}

export function useBulkInvite(orgId: string) {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  return useMutation({
    mutationFn: async (emails: string[]) => (await api.POST('/api/orgs/{id}/members/invite', { params: { path: { id: orgId } }, body: { emails } })).data!,
    onSuccess: () => qc.invalidateQueries({ queryKey: ctxKey(ctx, 'org', orgId, 'invitations') }),
  });
}

/** Removes a member (an admin) or leaves the organisation (own id): the seat is taken back by the server, the history stays. */
export function useRemoveMember(orgId: string) {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  return useMutation({
    mutationFn: async (userId: string) => {
      await api.DELETE('/api/orgs/{id}/members/{userId}', { params: { path: { id: orgId, userId } } });
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: publicKey('me', 'context', useAuthStore.getState().user?.id ?? null) });
      return qc.invalidateQueries({ queryKey: ctxKey(ctx, 'org', orgId, 'members') });
    },
  });
}

export type JoinResult = components['schemas']['JoinResponse'];

export function useJoin() {
  const api = useApi();
  const qc = useQueryClient();
  const user = useAuthStore((s) => s.user);
  return useMutation({
    mutationFn: async (code: string) => (await api.POST('/api/join/{code}', { params: { path: { code } } })).data as JoinResult,
    // the memberships changed: ask again who I am and where I belong
    onSuccess: () => qc.invalidateQueries({ queryKey: publicKey('me', 'context', user?.id ?? null) }),
  });
}

async function post(file: File, orgId: string, token: string | null): Promise<Response> {
  const form = new FormData();
  form.append('file', file, file.name);
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  const ctx = useContextStore.getState().orgId;
  if (ctx) headers['X-Org-Context'] = ctx;
  return fetch(`/api/orgs/${encodeURIComponent(orgId)}/members/import`, { method: 'POST', body: form, headers });
}

/** Uploads the workbook; a 422 carries the rows to fix (ApiProblemError.errors), and nobody is invited until the file is clean. */
export async function importMembers(orgId: string, file: File): Promise<{ rows: number; invited: number }> {
  let token = useAuthStore.getState().accessToken;
  let res = await post(file, orgId, token);
  if (res.status === 401 && token) {
    const fresh = await refreshSession();
    if (fresh) {
      token = fresh;
      res = await post(file, orgId, token);
    }
  }
  const body = (await res.json().catch(() => ({}))) as ProblemBody & { rows?: number; invited?: number };
  if (!res.ok) throw new ApiProblemError(res.status, body);
  return { rows: body.rows ?? 0, invited: body.invited ?? 0 };
}

export function useImportMembers(orgId: string) {
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  return useMutation({
    mutationFn: (file: File) => importMembers(orgId, file),
    onSuccess: () => qc.invalidateQueries({ queryKey: ctxKey(ctx, 'org', orgId, 'invitations') }),
  });
}

/** Splits what an admin pasted (one per line, or separated by commas, semicolons or spaces) into distinct addresses. */
export function parseEmails(text: string): string[] {
  return [...new Set(text.split(/[\s,;]+/).map((e) => e.trim().toLowerCase()).filter(Boolean))];
}
