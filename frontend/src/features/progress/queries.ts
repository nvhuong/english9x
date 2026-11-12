import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../api/useApi';
import { useCtxKey } from '../../app/stores/context';
import type { Progress } from './types';

/** My progress by plan. */
export function useMyProgress() {
  const api = useApi();
  return useQuery({
    queryKey: useCtxKey('progress', 'mine'),
    retry: false,
    queryFn: async () => (await api.GET('/api/me/progress')).data as unknown as Progress,
  });
}
