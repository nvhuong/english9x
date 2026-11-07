import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { useApi } from '../../api/useApi';
import { useAuthStore } from '../../app/stores/auth';
import { publicKey, useContextStore } from '../../app/stores/context';
import { useRoleStore } from '../../app/stores/roles';
import { applyMeContext, resetOrgState, useOrgStore, type MeContextBody } from './orgContext';

/**
 * Keeps the role store and the organisation list in step with the server. Mounted once (providers).
 *  - Asks GET /api/me/context WITHOUT X-Org-Context: a stale stored organisation must never turn this call into a 403.
 *  - Asks again whenever the context changes, so the roles shown are the server's current word, not a cached one.
 *  - Applies the answer: a context the person no longer belongs to becomes "Cá nhân", once, without any retry loop.
 * The query key is public (the answer belongs to the person, not to a context); a different person clears the whole cache.
 */
export function OrgContextBootstrap() {
  const api = useApi();
  const queryClient = useQueryClient();
  const userId = useAuthStore((s) => (s.status === 'authenticated' ? (s.user?.id ?? null) : null));
  const orgId = useContextStore((s) => s.orgId);

  const query = useQuery({
    queryKey: publicKey('me', 'context', userId),
    enabled: userId !== null,
    staleTime: 0,
    retry: false,
    queryFn: async (): Promise<MeContextBody> => {
      const { data } = await api.GET('/api/me/context', { headers: { 'x-e9x-no-context': '1' } });
      return data as MeContextBody;
    },
  });

  // the context changed: ask again (the first run is the query's own initial fetch)
  useEffect(() => {
    if (userId !== null && query.isFetched) void query.refetch();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orgId]);

  useEffect(() => {
    if (userId !== null && query.data) void applyMeContext(queryClient, userId, query.data);
  }, [userId, query.data, query.dataUpdatedAt, queryClient]);

  const failed = userId !== null && query.isError && !query.isFetching;
  const { refetch } = query;
  useEffect(() => {
    useOrgStore.setState({ loadFailed: failed, retryLoad: () => void refetch() });
  }, [failed, refetch]);

  useEffect(() => {
    if (userId === null) {
      resetOrgState();
      if (useAuthStore.getState().status === 'anonymous') useRoleStore.getState().setGrants(null);
    }
  }, [userId]);

  return null;
}
