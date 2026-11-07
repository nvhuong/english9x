import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore, type AuthUser } from '../../app/stores/auth';
import { useContextStore } from '../../app/stores/context';
import { useRoleStore } from '../../app/stores/roles';
import { peekReturn, rememberReturn } from './returnTo';
import {
  bootstrapSession, clearCacheOnUserChange, endSession, logout, refreshSession, refreshStorage, startSession, syncSessionAcrossTabs,
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
    const stop = syncSessionAcrossTabs();
    window.dispatchEvent(new StorageEvent('storage', { key: 'e9x.auth.refresh', newValue: null }));
    expect(useAuthStore.getState().status).toBe('anonymous');
    stop();
  });
});

describe('a session that changed while something was in flight', () => {
  const deferred = <T,>() => {
    let resolve!: (v: T) => void;
    const promise = new Promise<T>((r) => (resolve = r));
    return { promise, resolve };
  };

  it('logging out during a refresh does not bring the tokens back, and the orphan is revoked', async () => {
    refreshStorage.set('refresh-1');
    useAuthStore.setState({ accessToken: 'a', user: me() as never, status: 'authenticated' });
    const gate = deferred<void>();
    const calls = stubApi({
      'POST /api/auth/refresh': async () => {
        await gate.promise;
        return json(200, tokens(2));
      },
      'POST /api/auth/logout': () => new Response(null, { status: 204 }),
    });
    const refreshing = refreshSession();
    await vi.waitFor(() => expect(calls.some((c) => c.path === '/api/auth/refresh')).toBe(true));
    await logout();
    gate.resolve();
    expect(await refreshing).toBeNull();
    expect(refreshStorage.get()).toBeNull();
    expect(useAuthStore.getState()).toMatchObject({ accessToken: null, user: null, status: 'anonymous' });
    await vi.waitFor(() => expect(calls.filter((c) => c.path === '/api/auth/logout').map((c) => c.body)).toContainEqual({ refreshToken: 'refresh-2' }));
  });

  it('signing in as someone else during a refresh keeps the new session', async () => {
    refreshStorage.set('refresh-old');
    const gate = deferred<void>();
    const calls = stubApi({
      'POST /api/auth/refresh': async () => {
        await gate.promise;
        return json(200, tokens(2));
      },
      'POST /api/auth/logout': () => new Response(null, { status: 204 }),
      'GET /api/auth/me': () => json(200, me({ id: 'other' })),
    });
    const refreshing = refreshSession();
    await vi.waitFor(() => expect(calls.some((c) => c.path === '/api/auth/refresh')).toBe(true));
    await startSession(tokens(7));
    gate.resolve();
    expect(await refreshing).toBeNull();
    expect(refreshStorage.get()).toBe('refresh-7');
    expect(useAuthStore.getState().accessToken).toBe('access-7');
    expect(useAuthStore.getState().user?.id).toBe('other');
  });

  it('a dead token answer that arrives after signing in as someone else does not end the new session', async () => {
    refreshStorage.set('refresh-old');
    const gate = deferred<void>();
    const calls = stubApi({
      'POST /api/auth/refresh': async () => {
        await gate.promise;
        return problem(401, 'invalid-refresh-token');
      },
      'GET /api/auth/me': () => json(200, me({ id: 'other' })),
    });
    const refreshing = refreshSession();
    await vi.waitFor(() => expect(calls.some((c) => c.path === '/api/auth/refresh')).toBe(true));
    await startSession(tokens(7));
    gate.resolve();
    await refreshing;
    expect(refreshStorage.get()).toBe('refresh-7');
    expect(useAuthStore.getState().status).toBe('authenticated');
  });

  it('the profile answer of a sign-in does not sign back in after logging out', async () => {
    const gate = deferred<void>();
    stubApi({
      'GET /api/auth/me': async () => {
        await gate.promise;
        return json(200, me());
      },
      'POST /api/auth/logout': () => new Response(null, { status: 204 }),
    });
    const signingIn = startSession(tokens(1));
    await logout();
    gate.resolve();
    await signingIn;
    expect(useAuthStore.getState()).toMatchObject({ accessToken: null, user: null, status: 'anonymous' });
  });

  it('a refresh after sign-in as someone else does not share the old in-flight promise', async () => {
    refreshStorage.set('refresh-old');
    const gate = deferred<void>();
    let n = 0;
    stubApi({
      'POST /api/auth/refresh': async (c) => {
        n++;
        if ((c.body as { refreshToken: string }).refreshToken === 'refresh-old') {
          await gate.promise;
          return json(200, tokens(2));
        }
        return json(200, tokens(9));
      },
      'POST /api/auth/logout': () => new Response(null, { status: 204 }),
      'GET /api/auth/me': () => json(200, me({ id: 'other' })),
    });
    const old = refreshSession();
    await vi.waitFor(() => expect(n).toBe(1));
    await startSession(tokens(7));
    expect(await refreshSession()).toBe('access-9');
    gate.resolve();
    await old;
    expect(refreshStorage.get()).toBe('refresh-9');
  });

  it('another tab signing out during our refresh wins: its answer is not written back', async () => {
    refreshStorage.set('refresh-1');
    localStorage.setItem('e9x.auth.session', 'm1');
    useAuthStore.setState({ accessToken: 'a', user: me() as never, status: 'authenticated' });
    const gate = deferred<void>();
    const calls = stubApi({
      'POST /api/auth/refresh': async () => {
        await gate.promise;
        return json(200, tokens(2));
      },
      'POST /api/auth/logout': () => new Response(null, { status: 204 }),
    });
    const refreshing = refreshSession();
    await vi.waitFor(() => expect(calls.some((c) => c.path === '/api/auth/refresh')).toBe(true));
    localStorage.removeItem('e9x.auth.refresh'); // the other tab
    localStorage.removeItem('e9x.auth.session');
    gate.resolve();
    expect(await refreshing).toBeNull();
    expect(refreshStorage.get()).toBeNull();
  });
});

describe('other tabs', () => {
  const otherTabSignsIn = (token: string, marker: string) => {
    localStorage.setItem('e9x.auth.refresh', token);
    localStorage.setItem('e9x.auth.session', marker);
    window.dispatchEvent(new StorageEvent('storage', { key: 'e9x.auth.refresh', newValue: token }));
    window.dispatchEvent(new StorageEvent('storage', { key: 'e9x.auth.session', newValue: marker }));
  };

  it('a sign-in as someone else in another tab re-reads /me, shows that person and drops the cache', async () => {
    const qc = new QueryClient();
    const stopCache = clearCacheOnUserChange(qc);
    const A = me({ id: 'a' });
    stubApi({ 'GET /api/auth/me': () => json(200, A) });
    await startSession(tokens(1));
    qc.setQueryData(['ctx', 'personal', 'x'], 'private of A');
    useRoleStore.setState({ grants: [{ role: 'LEARNER', org: null, exam: null }] });
    const calls = stubApi({
      'POST /api/auth/refresh': (c) => {
        expect((c.body as { refreshToken: string }).refreshToken).toBe('refresh-b');
        return json(200, tokens(3));
      },
      'GET /api/auth/me': () => json(200, me({ id: 'b', email: 'b@example.test' })),
    });
    const stop = syncSessionAcrossTabs();
    otherTabSignsIn('refresh-b', 'session-of-b');
    await vi.waitFor(() => expect(useAuthStore.getState().user?.id).toBe('b'));
    expect(useAuthStore.getState()).toMatchObject({ accessToken: 'access-3', status: 'authenticated' });
    expect(qc.getQueryData(['ctx', 'personal', 'x'])).toBeUndefined();
    expect(useRoleStore.getState().grants).toBeNull();
    expect(calls.filter((c) => c.path === '/api/auth/refresh')).toHaveLength(1);
    stop();
    stopCache();
  });

  it('the same person signing in again in another tab keeps the cache', async () => {
    const qc = new QueryClient();
    const stopCache = clearCacheOnUserChange(qc);
    stubApi({ 'GET /api/auth/me': () => json(200, me({ id: 'a' })) });
    await startSession(tokens(1));
    qc.setQueryData(['ctx', 'personal', 'x'], 'mine');
    stubApi({ 'POST /api/auth/refresh': () => json(200, tokens(3)), 'GET /api/auth/me': () => json(200, me({ id: 'a' })) });
    const stop = syncSessionAcrossTabs();
    otherTabSignsIn('refresh-again', 'second-session');
    await vi.waitFor(() => expect(useAuthStore.getState().accessToken).toBe('access-3'));
    expect(qc.getQueryData(['ctx', 'personal', 'x'])).toBe('mine');
    stop();
    stopCache();
  });

  it('an ordinary rotation in another tab costs nothing: no refresh of our own, no ping-pong between tabs', async () => {
    stubApi({ 'GET /api/auth/me': () => json(200, me()) });
    await startSession(tokens(1));
    const marker = localStorage.getItem('e9x.auth.session');
    expect(marker).not.toBeNull();
    const calls = stubApi({});
    const stop = syncSessionAcrossTabs();
    localStorage.setItem('e9x.auth.refresh', 'refresh-rotated');
    window.dispatchEvent(new StorageEvent('storage', { key: 'e9x.auth.refresh', newValue: 'refresh-rotated' }));
    await new Promise((r) => setTimeout(r, 20));
    expect(calls).toHaveLength(0);
    expect(useAuthStore.getState()).toMatchObject({ accessToken: 'access-1', status: 'authenticated' });
    stop();
  });

  it('a tab that is signed out follows a sign-in made in another tab', async () => {
    stubApi({ 'POST /api/auth/refresh': () => json(200, tokens(3)), 'GET /api/auth/me': () => json(200, me({ id: 'b' })) });
    const stop = syncSessionAcrossTabs();
    otherTabSignsIn('refresh-b', 'session-of-b');
    await vi.waitFor(() => expect(useAuthStore.getState().status).toBe('authenticated'));
    expect(useAuthStore.getState().user?.id).toBe('b');
    stop();
  });

  it('sign-out then immediately sign-in elsewhere: the late sign-out event does not wipe the new token', async () => {
    useAuthStore.setState({ accessToken: 'a', user: me({ id: 'a' }) as never, status: 'authenticated' });
    stubApi({ 'POST /api/auth/refresh': () => json(200, tokens(3)), 'GET /api/auth/me': () => json(200, me({ id: 'b' })) });
    const stop = syncSessionAcrossTabs();
    localStorage.setItem('e9x.auth.refresh', 'refresh-b');
    localStorage.setItem('e9x.auth.session', 'later-session-of-b');
    window.dispatchEvent(new StorageEvent('storage', { key: 'e9x.auth.refresh', newValue: null })); // delivered late
    expect(localStorage.getItem('e9x.auth.refresh')).toBe('refresh-b');
    window.dispatchEvent(new StorageEvent('storage', { key: 'e9x.auth.session', newValue: 'later-session-of-b' }));
    await vi.waitFor(() => expect(useAuthStore.getState().user?.id).toBe('b'));
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

describe('signing out leaves nothing of the context or the way back', () => {
  it('endSession resets the context store: the next person on this tab does not start in this one\'s organisation', () => {
    useContextStore.setState({ orgId: 'aaaaaaaa-0000-0000-0000-00000000000a' });
    endSession();
    expect(useContextStore.getState().orgId).toBeNull();
  });

  it('a sign-out in another tab resets it too', () => {
    const stop = syncSessionAcrossTabs();
    useAuthStore.setState({ status: 'authenticated', user: me() as AuthUser });
    useContextStore.setState({ orgId: 'aaaaaaaa-0000-0000-0000-00000000000a' });
    window.dispatchEvent(new StorageEvent('storage', { key: 'e9x.auth.refresh' }));
    expect(useContextStore.getState().orgId).toBeNull();
    stop();
    useContextStore.setState({ orgId: null });
  });

  it('logout forgets a remembered way back', async () => {
    stubApi({ 'POST /api/auth/logout': () => new Response(null, { status: 204 }) });
    rememberReturn('/accept-invitation?token=abc');
    await logout();
    expect(peekReturn()).toBeNull();
  });
});
