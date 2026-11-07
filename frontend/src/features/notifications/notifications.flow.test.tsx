import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../../app/stores/auth';
import { publicKey, switchContext } from '../../app/stores/context';
import { json, me, renderAt, resetAuth, stubApi } from '../auth/testUtils';
import { NotificationBell } from './NotificationBell';
import NotificationsPage from './NotificationsPage';
import NotificationPreferencesPage from './PreferencesPage';

vi.mock('../../api/useApi', async (orig) => {
  const m = await orig<typeof import('../../api/useApi')>();
  return { ...m, useApi: () => m.createAuthedClient('http://localhost') };
});

beforeEach(() => {
  resetAuth();
  useAuthStore.setState({ accessToken: 'tok', status: 'authenticated', user: me() as never });
});
afterEach(() => vi.unstubAllGlobals());

const row = (id: string, read: boolean) => ({ id, type: 'REVIEW_REQUESTED', data: { contentType: 'question', examCode: 'IELTS' }, createdAt: '2026-11-07T03:00:00Z', read });

describe('notification pages against the API', () => {
  it('the bell shows the unread count and links to the centre; nothing is requested when signed out', async () => {
    stubApi({ 'GET /api/me/notifications/unread-count': () => json(200, { count: 3 }) });
    renderAt('/', '/', <NotificationBell />);
    expect(await screen.findByRole('link', { name: 'Thông báo, 3 chưa đọc' })).toHaveAttribute('href', '/notifications');
  });

  it('a signed-out visitor sees no bell and the page asks to sign in without calling the API', () => {
    useAuthStore.setState({ accessToken: null, status: 'anonymous', user: null });
    const calls = stubApi({});
    renderAt('/', '/', <NotificationBell />);
    expect(screen.queryByRole('link', { name: /Thông báo/ })).toBeNull();
    renderAt('/notifications', '/notifications', <NotificationsPage />);
    expect(screen.getByText('Vui lòng đăng nhập để xem thông báo.')).toBeInTheDocument();
    expect(calls).toHaveLength(0);
  });

  it('lists the inbox and marks one as read through the API', async () => {
    let items = [row('a', false), row('b', true)];
    const calls = stubApi({
      'GET /api/me/notifications': () => json(200, { items, unread: items.filter((i) => !i.read).length }),
      'POST /api/me/notifications/a/read': () => {
        items = items.map((i) => (i.id === 'a' ? { ...i, read: true } : i));
        return new Response(null, { status: 204 });
      },
    });
    renderAt('/notifications', '/notifications', <NotificationsPage />);
    await userEvent.click(await screen.findByRole('button', { name: /Đánh dấu đã đọc: Có nội dung chờ duyệt/ }));
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Bạn đã đọc hết'));
    expect(calls.some((c) => c.method === 'POST' && c.path === '/api/me/notifications/a/read')).toBe(true);
    expect(calls.find((c) => c.path === '/api/me/notifications')!.search).toContain('limit=20');
  });

  it('keeps the inbox in the cache when the organisation context changes (a person has one inbox)', async () => {
    stubApi({ 'GET /api/me/notifications': () => json(200, { items: [row('a', false)], unread: 1 }) });
    const { client } = renderAt('/notifications', '/notifications', <NotificationsPage />);
    await screen.findByRole('heading', { name: 'Có nội dung chờ duyệt' });
    await switchContext(client, 'aaaaaaaa-0000-0000-0000-00000000000a');
    expect(client.getQueryCache().findAll({ queryKey: publicKey('me') }).length).toBeGreaterThan(0);
    expect(screen.getByRole('heading', { name: 'Có nội dung chờ duyệt' })).toBeInTheDocument();
  });

  it('never shows one person\'s inbox to the next person signing in on the same browser', async () => {
    let call = 0;
    stubApi({
      'GET /api/me/notifications': () => json(200, { items: [{ ...row(`n${++call}`, false), type: call === 1 ? 'REVIEW_REQUESTED' : 'CONTENT_PUBLISHED' }], unread: 1 }),
    });
    renderAt('/notifications', '/notifications', <NotificationsPage />);
    await screen.findByRole('heading', { name: 'Có nội dung chờ duyệt' });
    act(() => useAuthStore.setState({ user: me({ id: '22222222-2222-2222-2222-222222222222' }) as never }));
    await screen.findByRole('heading', { name: 'Nội dung đã được xuất bản' });
    expect(screen.queryByRole('heading', { name: 'Có nội dung chờ duyệt' })).toBeNull();
  });

  it('changes a preference with PUT and shows the server state; creates a one-time Telegram link', async () => {
    let email = true;
    const calls = stubApi({
      'GET /api/me/notification-prefs': () => json(200, { channels: ['IN_APP', 'EMAIL', 'TELEGRAM'], events: [{ type: 'REVIEW_REQUESTED', audience: 'REVIEWER', email, telegram: true }] }),
      'PUT /api/me/notification-prefs': (c) => {
        email = (c.body as { enabled: boolean }).enabled;
        return new Response(null, { status: 204 });
      },
      'GET /api/me/telegram': () => json(200, { enabled: true, linked: false }),
      'POST /api/me/telegram/link': () => json(200, { deepLink: 'https://t.me/e9x_bot?start=tok', expiresAt: '2026-11-07T04:00:00Z' }),
    });
    renderAt('/notifications/preferences', '/notifications/preferences', <NotificationPreferencesPage />);
    const box = await screen.findByRole('checkbox', { name: /Email: Có nội dung chờ duyệt/ });
    expect(box).toBeChecked();
    await userEvent.click(box);
    await waitFor(() => expect(screen.getByRole('checkbox', { name: /Email: Có nội dung chờ duyệt/ })).not.toBeChecked());
    expect(calls.find((c) => c.method === 'PUT')!.body).toEqual({ eventType: 'REVIEW_REQUESTED', channel: 'EMAIL', enabled: false });
    await userEvent.click(await screen.findByRole('button', { name: 'Tạo liên kết kết nối' }));
    expect(await screen.findByRole('link', { name: 'Mở trong Telegram' })).toHaveAttribute('href', 'https://t.me/e9x_bot?start=tok');
  });

  it('shows an error with a retry when marking one notification read fails', async () => {
    let fail = true;
    const calls = stubApi({
      'GET /api/me/notifications': () => json(200, { items: [row('a', false)], unread: 1 }),
      'POST /api/me/notifications/a/read': () => (fail ? json(500, { title: 'x' }) : new Response(null, { status: 204 })),
    });
    renderAt('/notifications', '/notifications', <NotificationsPage />);
    await userEvent.click(await screen.findByRole('button', { name: /Đánh dấu đã đọc: Có nội dung chờ duyệt/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Chưa đánh dấu được đã đọc');
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
    expect(calls.filter((c) => c.path === '/api/me/notifications/a/read')).toHaveLength(2);
  });

  it('shows an error with a retry when marking all read fails', async () => {
    let fail = true;
    stubApi({
      'GET /api/me/notifications': () => json(200, { items: [row('a', false)], unread: 1 }),
      'POST /api/me/notifications/read-all': () => (fail ? json(500, { title: 'x' }) : new Response(null, { status: 204 })),
    });
    renderAt('/notifications', '/notifications', <NotificationsPage />);
    await userEvent.click(await screen.findByRole('button', { name: 'Đánh dấu tất cả đã đọc' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Chưa đánh dấu được tất cả');
    fail = false;
    await userEvent.click(screen.getByRole('button', { name: 'Thử lại' }));
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
  });

  it('shows an error with a retry when the Telegram status cannot be read, and the failing-chat warning when it says so', async () => {
    let status: 'error' | 'failing' = 'error';
    stubApi({
      'GET /api/me/notification-prefs': () => json(200, { channels: ['IN_APP', 'EMAIL', 'TELEGRAM'], events: [] }),
      'GET /api/me/telegram': () => (status === 'error' ? json(500, { title: 'x' }) : json(200, { enabled: true, linked: true, failing: true })),
    });
    renderAt('/notifications/preferences', '/notifications/preferences', <NotificationPreferencesPage />);
    expect(await screen.findByText('Không tải được trạng thái Telegram.')).toBeInTheDocument();
    status = 'failing';
    await userEvent.click(screen.getByRole('button', { name: 'Thử lại trạng thái Telegram' }));
    expect(await screen.findByText(/Telegram đã kết nối nhưng tin gần nhất không gửi được/)).toBeInTheDocument();
    expect(screen.queryByText('Không tải được trạng thái Telegram.')).toBeNull();
  });
});
