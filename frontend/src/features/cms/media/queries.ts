import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../api/useApi';
import { ctxKey, publicKey, useContextStore } from '../../../app/stores/context';
import type { MediaLimits, MediaLink } from './types';

/** What the upload may offer (the server's size limit and the allowed extensions): the same in every context. */
export function useMediaLimits() {
  const api = useApi();
  return useQuery({
    queryKey: publicKey('cms', 'media', 'limits'),
    staleTime: 10 * 60_000,
    queryFn: async () => (await api.GET('/api/cms/media/limits')).data as MediaLimits,
  });
}

/**
 * A signed link to a stored file (GET /api/media/{id}/url). Per context, because who may open a file depends on the context. The link lives about
 * ten minutes (the answer says when). It is NOT renewed on a timer, because a new address would restart a player in the middle of a recording:
 * {@link MediaPreview} asks again when the element fails after the link has run out.
 */
export function useMediaLink(mediaId: string | undefined) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'media', mediaId ?? 'none', 'url'),
    enabled: Boolean(mediaId),
    retry: false,
    gcTime: 60_000,
    staleTime: 0,
    queryFn: async () => (await api.GET('/api/media/{id}/url', { params: { path: { id: mediaId! } } })).data as MediaLink,
  });
}
