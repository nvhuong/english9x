import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore, type AuthUser } from '../../app/stores/auth';
import { useRoleStore } from '../../app/stores/roles';
import {
  bootstrapSession, clearCacheOnUserChange, endSession, logout, refreshSession, refreshStorage, startSession, syncLogoutAcrossTabs,
} from './session';
import { json, me, problem, resetAuth, stubApi, tokens } from './testUtils';

beforeEach(resetAuth);
afterEach(() => vi.unstubAllGlobals());

describe('refreshSession (single flight)', () => {
  it('five simultaneous callers cause ONE rotation and all get the same new token', async () => {
    refreshStorage.set('refresh-1');
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const calls = stubApi({
      'POST /api/auth/refresh': async () => {
        await gate;
        return json(200, tokens(2));
      },
    });
    const all = Promise.all([1, 2, 3, 4, 5].map(() => refreshSession()));
    release();
    expect(await all).toEqual(['access-2', 'access-2', 'access-2', 'access-2', 'access-2']);
    expect(calls.filter((c) => c.path === '/api/auth/refresh')).toHaveLength(1);
    expect(calls[0]!.body).toEqual({ refreshToken: 'refresh-1' });
    expect(refreshStorage.get()).toBe('refresh-2');
    expect(useAuthStore.getState().accessToken).toBe('access-2');
  });

  it('a later refresh after the first finished is a new rotation with the newest token', async () => {
    refreshStorage.set('refresh-1');
    const calls = stubApi({ 'POST /api/auth/refresh': (c) => json(200, tokens((c.body as { refreshToken: string }).refreshToken === 'refresh-1' ? 2 : 3)) });
    expect(await refreshSession()).toBe('access-2');
    expect(await refreshSession()).toBe('access-3');
    expect(calls.map((c) => (c.body as { refreshToken: string }).refreshToken)).toEqual(['refresh-1', 'refresh-2']);
  });

  it('a dead refresh token ends the session; nothing is left behind', async () => {
    refreshStorage.set('refresh-1');
    useAuthStore.setState({ accessToken: 'a', user: me() as never, status: 'authenticated' });
    useRoleStore.setState({ grants: [{ role: 'LEARNER', org: null, exam: null }] });
    stubApi({ 'POST /api/auth/refresh': () => problem(401, 'invalid-refresh-token') });
    expect(await refreshSession()).toBeNull();
    expect(refreshStorage.get()).toBeNull();
    expect(useAuthStore.getState()).toMatchObject({ accessToken: null, user: null, status: 'anonymous' });
    expect(useRoleStore.getState().grants).toBeNull();
  });

  it('being offline does NOT sign the person out', async () => {
    refreshStorage.set('refresh-1');
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('network down'); }));
    expect(await refreshSession()).toBeNull();
    expect(refreshStorage.get()).toBe('refresh-1');
  });

  it('a server error does NOT sign the person out either', async () => {
    refreshStorage.set('refresh-1');
    stubApi({ 'POST /api/auth/refresh': () => json(503, {}) });
    expect(await refreshSession()).toBeNull();
    expect(refreshStorage.get()).toBe('refresh-1');
  });

  it('when another tab rotated the token meanwhile, a 401 for the stale one retries with the newer token instead of logging out', async () => {
    refreshStorage.set('refresh-1');
    let n = 0;
    const calls = stubApi({
      'POST /api/auth/refresh': (c) => {
        n++;
        if (n === 1) {
          refreshStorage.set('refresh-2'); // the other tab finished its rotation while ours was in flight
          return problem(401, 'invalid-refresh-token');
        }
        return json(200, tokens(3 + (c.body as { refreshToken: string }).refreshToken.length * 0));
      },
    });
    expect(await refreshSession()).toBe('access-3');
    expect(calls.map((c) => (c.body as { refreshToken: string }).refreshToken)).toEqual(['refresh-1', 'refresh-2']);
    expect(refreshStorage.get()).toBe('refresh-3');
  });

  it('uses a cross-tab Web Lock when the browser has one', async () => {
    refreshStorage.set('refresh-1');
    const request = vi.fn(async (_name: string, fn: () => Promise<unknown>) => fn());
    vi.stubGlobal('navigator', Object.assign(Object.create(navigator), { locks: { request } }));
    stubApi({ 'POST /api/auth/refresh': () => json(200, tokens(2)) });
    await refreshSession();
    expect(request).toHaveBeenCalledWith('e9x-auth-refresh', expect.any(Function));
  });
});

describe('startSession, bootstrap, logout', () => {
  it('startSession stores both tokens and the profile', async () => {
    const calls = stubApi({ 'GET /api/auth/me': () => json(200, me()) });
    const user = await startSession(tokens(1));
    expect(user?.email).toBe('an@example.test');
    expect(refreshStorage.get()).toBe('refresh-1');
    expect(useAuthStore.getState()).toMatchObject({ accessToken: 'access-1', status: 'authenticated' });
    expect(calls[0]!.headers.get('Authorization')).toBe('Bearer access-1');
  });

  it('bootstrap restores a stored session, and without a stored token goes straight to anonymous', async () => {
    stubApi({});
    await bootstrapSession();
    expect(useAuthStore.getState().status).toBe('anonymous');

    refreshStorage.set('refresh-1');
    stubApi({ 'POST /api/auth/refresh': () => json(200, tokens(2)), 'GET /api/auth/me': () => json(200, me()) });
    await bootstrapSession();
    expect(useAuthStore.getState()).toMatchObject({ status: 'authenticated', accessToken: 'access-2' });
  });

  it('logout forgets everything locally and revokes the token family on the server', async () => {
    refreshStorage.set('refresh-9');
    useAuthStore.setState({ accessToken: 'a', user: me() as never, status: 'authenticated' });
    const calls = stubApi({ 'POST /api/auth/logout': () => new Response(null, { status: 204 }) });
    await logout();
    expect(refreshStorage.get()).toBeNull();
    expect(useAuthStore.getState()).toMatchObject({ accessToken: null, user: null, status: 'anonymous' });
    expect(calls[0]!.body).toEqual({ refreshToken: 'refresh-9' });
  });

  it('logout still signs out locally when the server is unreachable', async () => {
    refreshStorage.set('refresh-9');
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('down'); }));
    await logout();
    expect(refreshStorage.get()).toBeNull();
  });

  it('signing out in another tab signs this one out', () => {
    useAuthStore.setState({ accessToken: 'a', user: me() as never, status: 'authenticated' });
    const stop = syncLogoutAcrossTabs();
    window.dispatchEvent(new StorageEvent('storage', { key: 'e9x.auth.refresh', newValue: null }));
    expect(useAuthStore.getState().status).toBe('anonymous');
    stop();
  });
});

describe('query cache and users', () => {
  it('the cache is dropped on sign-in, on sign-out and when a different account signs in', () => {
    const qc = new QueryClient();
    const stop = clearCacheOnUserChange(qc);
    const A = me({ id: 'a' }) as unknown as AuthUser;
    const B = me({ id: 'b' }) as unknown as AuthUser;
    const fill = () => qc.setQueryData(['ctx', 'personal', 'x'], 'private');

    fill();
    useAuthStore.getState().setUser(A);
    expect(qc.getQueryData(['ctx', 'personal', 'x'])).toBeUndefined();

    fill();
    useAuthStore.getState().setUser({ ...A, displayName: 'renamed' }); // same person: cache stays
    expect(qc.getQueryData(['ctx', 'personal', 'x'])).toBe('private');

    useAuthStore.getState().setUser(B);
    expect(qc.getQueryData(['ctx', 'personal', 'x'])).toBeUndefined();

    fill();
    endSession();
    expect(qc.getQueryData(['ctx', 'personal', 'x'])).toBeUndefined();
    stop();
  });
});
