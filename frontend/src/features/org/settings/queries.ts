import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../api/useApi';
import type { components } from '../../../api/generated/client';
import { ctxKey, useContextStore } from '../../../app/stores/context';

export type GradingSettingsView = components['schemas']['GradingSettingsView'];
export type GradingSettingsRequest = components['schemas']['GradingSettingsRequest'];

/** Who grades a centre's learners and in which mode (F50). ORG_ADMIN of the organisation only; the server decides. */
export function useGradingSettings(orgId: string) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'org', orgId, 'grading-settings'),
    retry: false,
    queryFn: async () => (await api.GET('/api/orgs/{id}/grading-settings', { params: { path: { id: orgId } } })).data as GradingSettingsView,
  });
}

export function useSaveGradingSettings(orgId: string) {
  const api = useApi();
  const qc = useQueryClient();
  const ctx = useContextStore((s) => s.orgId);
  return useMutation({
    mutationFn: async (body: GradingSettingsRequest) =>
      (await api.PUT('/api/orgs/{id}/grading-settings', { params: { path: { id: orgId } }, body })).data as GradingSettingsView,
    onSuccess: () => qc.invalidateQueries({ queryKey: ctxKey(ctx, 'org', orgId, 'grading-settings') }),
  });
}
