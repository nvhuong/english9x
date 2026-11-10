import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../api/useApi';
import type { components } from '../../../api/generated/client';
import { ctxKey, useContextStore } from '../../../app/stores/context';

export type OrgLicenseView = components['schemas']['OrgLicenseView'];
export type LicenseCourse = components['schemas']['LicenseCourse'];
export type SeatHolder = components['schemas']['SeatHolder'];
export type OrgLicenseCreated = components['schemas']['OrgLicenseCreated'];
export type CreateOrgLicenseRequest = components['schemas']['CreateOrgLicenseRequest'];

/** The licences of the organisation of the current context, with who holds each seat (ORG_ADMIN). */
export function useOrgLicenses(orgId: string) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'licenses', orgId),
    retry: false,
    queryFn: async () => (await api.GET('/api/orgs/{orgId}/licenses', { params: { path: { orgId } } })).data as OrgLicenseView[],
  });
}

export function useAssignSeat(orgId: string, licenseId: string) {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  return useMutation({
    mutationFn: async (v: { userId: string; courseIds: string[] }) =>
      (await api.POST('/api/orgs/{orgId}/licenses/{id}/seats', { params: { path: { orgId, id: licenseId } }, body: v })).data as SeatHolder,
    onSuccess: () => qc.invalidateQueries({ queryKey: ctxKey(ctx, 'licenses', orgId) }),
  });
}

export function useRevokeSeat(orgId: string, licenseId: string) {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  return useMutation({
    mutationFn: async (userId: string) => {
      await api.DELETE('/api/orgs/{orgId}/licenses/{id}/seats/{userId}', { params: { path: { orgId, id: licenseId, userId } } });
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ctxKey(ctx, 'licenses', orgId) }),
  });
}

/** Every licence (SYS_ADMIN). */
export function useAdminLicenses() {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'licenses', 'admin'),
    retry: false,
    queryFn: async () => (await api.GET('/api/admin/org-licenses')).data as OrgLicenseView[],
  });
}

/** The published English9x courses a licence may cover (SYS_ADMIN). */
export function useLicensableCourses() {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'licenses', 'courses'),
    retry: false,
    queryFn: async () => (await api.GET('/api/admin/org-licenses/courses')).data as LicenseCourse[],
  });
}

export function useCreateLicense() {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  return useMutation({
    mutationFn: async (body: CreateOrgLicenseRequest) => (await api.POST('/api/admin/org-licenses', { body })).data as OrgLicenseCreated,
    onSuccess: () => qc.invalidateQueries({ queryKey: ctxKey(ctx, 'licenses', 'admin') }),
  });
}
