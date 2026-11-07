import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../api/useApi';
import type { components } from '../../api/generated/client';
import { useAuthStore } from '../../app/stores/auth';
import { publicKey } from '../../app/stores/context';
import type { ListItem } from './NotificationListView';

export type NotificationItem = components['schemas']['NotificationItem'];
export type NotificationPrefs = components['schemas']['NotificationPrefs'];
export type NotificationEventPrefs = components['schemas']['NotificationEventPrefs'];

/**
 * Notifications belong to the PERSON, not to a context: the server answers the same whatever X-Org-Context says, and a bell that
 * emptied every time someone switched organisation would lie. So these queries use `publicKey` (they survive a context switch), and
 * the signed-in person's id is part of every key, so a different person signing in on the same browser can never be shown the
 * previous person's cache (a context-free key is not a person-free key).
 */
function useMe(): string | null {
  return useAuthStore((s) => (s.status === 'authenticated' ? (s.user?.id ?? null) : null));
}

const PAGE = 20;

export function useUnreadCount() {
  const api = useApi();
  const me = useMe();
  return useQuery({
    queryKey: publicKey('me', me, 'notifications', 'unread'),
    enabled: me !== null,
    refetchInterval: 30_000,
    queryFn: async () => (await api.GET('/api/me/notifications/unread-count')).data!.count ?? 0,
  });
}

/** The first `pages` pages of the inbox, newest first. */
export function useNotifications(pages: number) {
  const api = useApi();
  const me = useMe();
  return useQuery({
    queryKey: publicKey('me', me, 'notifications', 'list', pages),
    enabled: me !== null,
    queryFn: async () => {
      const data = (await api.GET('/api/me/notifications', { params: { query: { limit: PAGE * pages, offset: 0 } } })).data!;
      // the generated schema marks every field optional; the server always sends them
      const items = (data.items ?? []) as unknown as ListItem[];
      return { items, unread: data.unread ?? 0, hasMore: items.length === PAGE * pages };
    },
  });
}

function useRefreshInbox() {
  const queryClient = useQueryClient();
  const me = useMe();
  return () => queryClient.invalidateQueries({ queryKey: publicKey('me', me, 'notifications') });
}

export function useMarkRead() {
  const api = useApi();
  const refresh = useRefreshInbox();
  return useMutation({
    mutationFn: async (id: string) => api.POST('/api/me/notifications/{id}/read', { params: { path: { id } } }),
    onSuccess: refresh,
  });
}

export function useMarkAllRead() {
  const api = useApi();
  const refresh = useRefreshInbox();
  return useMutation({ mutationFn: async () => api.POST('/api/me/notifications/read-all'), onSuccess: refresh });
}

export function usePrefs() {
  const api = useApi();
  const me = useMe();
  return useQuery({
    queryKey: publicKey('me', me, 'notification-prefs'),
    enabled: me !== null,
    queryFn: async () => (await api.GET('/api/me/notification-prefs')).data as NotificationPrefs,
  });
}

export function useSetPref() {
  const api = useApi();
  const queryClient = useQueryClient();
  const me = useMe();
  return useMutation({
    mutationFn: async (v: { eventType: string; channel: 'EMAIL' | 'TELEGRAM'; enabled: boolean }) =>
      api.PUT('/api/me/notification-prefs', { body: v }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: publicKey('me', me, 'notification-prefs') }),
  });
}

export function useTelegramStatus() {
  const api = useApi();
  const me = useMe();
  return useQuery({
    queryKey: publicKey('me', me, 'telegram'),
    enabled: me !== null,
    queryFn: async () => (await api.GET('/api/me/telegram')).data as { enabled: boolean; linked: boolean },
  });
}

/** A new one-time link. The link is shown once and is not kept in the cache. */
export function useTelegramLink() {
  const api = useApi();
  return useMutation({ mutationFn: async () => (await api.POST('/api/me/telegram/link')).data as { deepLink: string; expiresAt: string } });
}

export function useTelegramUnlink() {
  const api = useApi();
  const queryClient = useQueryClient();
  const me = useMe();
  return useMutation({
    mutationFn: async () => api.DELETE('/api/me/telegram'),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: publicKey('me', me, 'telegram') }),
  });
}
