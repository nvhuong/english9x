import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../app/stores/auth';
import { refreshStorage } from '../features/auth/session';
import { json, problem, resetAuth, stubApi, tokens } from '../features/auth/testUtils';
import { ApiProblemError } from './problem';
import { createAuthedClient } from './useApi';

beforeEach(resetAuth);
afterEach(() => vi.unstubAllGlobals());

const signedIn = () => {
  refreshStorage.set('refresh-1');
  useAuthStore.setState({ accessToken: 'access-1', status: 'authenticated' });
};

describe('transparent refresh', () => {
  it('a 401 triggers one refresh and one retry with the new token; the caller just gets the data', async () => {
    signedIn();
    const calls = stubApi({
      'GET /api/auth/me': (c) => (c.headers.get('Authorization') === 'Bearer access-2' ? json(200, { id: 'u' }) : problem(401, 'unauthorized')),
      'POST /api/auth/refresh': () => json(200, tokens(2)),
    });
    const res = await createAuthedClient('http://localhost').GET('/api/auth/me');
    expect(res.data).toMatchObject({ id: 'u' });
    expect(calls.map((c) => `${c.method} ${c.path} ${c.headers.get('Authorization') ?? '-'}`)).toEqual([
      'GET /api/auth/me Bearer access-1',
      'POST /api/auth/refresh -',
      'GET /api/auth/me Bearer access-2',
    ]);
  });

  it('a burst of requests that all get 401 share ONE refresh, and every one is retried', async () => {
    signedIn();
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const calls = stubApi({
      'GET /api/auth/me': (c) => (c.headers.get('Authorization') === 'Bearer access-2' ? json(200, { id: 'u' }) : problem(401, 'unauthorized')),
      'POST /api/auth/refresh': async () => {
        await gate;
        return json(200, tokens(2));
      },
    });
    const client = createAuthedClient('http://localhost');
    const all = Promise.all([1, 2, 3, 4].map(() => client.GET('/api/auth/me')));
    await vi.waitFor(() => expect(calls.filter((c) => c.path === '/api/auth/refresh')).toHaveLength(1));
    release();
    const results = await all;
    expect(results.every((r) => r.data)).toBe(true);
    expect(calls.filter((c) => c.path === '/api/auth/refresh')).toHaveLength(1);
    expect(calls.filter((c) => c.path === '/api/auth/me' && c.headers.get('Authorization') === 'Bearer access-2')).toHaveLength(4);
  });

  it('a request that failed with an old token after somebody else refreshed is simply repeated, with no second rotation', async () => {
    signedIn();
    let slow!: () => void;
    const hold = new Promise<void>((r) => (slow = r));
    const calls = stubApi({
      'GET /api/auth/me': async (c) => {
        if (c.headers.get('Authorization') === 'Bearer access-2') return json(200, { id: 'u' });
        await hold;
        return problem(401, 'unauthorized');
      },
    });
    const client = createAuthedClient('http://localhost');
    const pending = client.GET('/api/auth/me');
    await vi.waitFor(() => expect(calls).toHaveLength(1));
    useAuthStore.setState({ accessToken: 'access-2' }); // another request refreshed in the meantime
    slow();
    expect((await pending).data).toMatchObject({ id: 'u' });
    expect(calls.some((c) => c.path === '/api/auth/refresh')).toBe(false);
  });

  it('a POST body survives the retry', async () => {
    signedIn();
    const calls = stubApi({
      'POST /api/auth/logout': (c) => (c.headers.get('Authorization') === 'Bearer access-2' ? new Response(null, { status: 204 }) : problem(401, 'unauthorized')),
      'POST /api/auth/refresh': () => json(200, tokens(2)),
    });
    await createAuthedClient('http://localhost').POST('/api/auth/logout', { body: { refreshToken: 'xyz' } });
    const posts = calls.filter((c) => c.path === '/api/auth/logout');
    expect(posts).toHaveLength(2);
    expect(posts[1]!.body).toEqual({ refreshToken: 'xyz' });
  });

  it('when the refresh token is dead the 401 is surfaced and the session is over', async () => {
    signedIn();
    stubApi({
      'GET /api/auth/me': () => problem(401, 'unauthorized'),
      'POST /api/auth/refresh': () => problem(401, 'invalid-refresh-token'),
    });
    await expect(createAuthedClient('http://localhost').GET('/api/auth/me')).rejects.toMatchObject({ status: 401 });
    expect(useAuthStore.getState().status).toBe('anonymous');
    expect(refreshStorage.get()).toBeNull();
  });

  it('a 401 from an unauthenticated call (wrong password) never triggers a refresh', async () => {
    refreshStorage.set('refresh-1'); // a stale token exists, but this request carried no access token
    const calls = stubApi({ 'POST /api/auth/login': () => problem(401, 'invalid-credentials') });
    const err = await createAuthedClient('http://localhost').POST('/api/auth/login', { body: { email: 'a@b.test', password: 'x' } }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiProblemError);
    expect(calls).toHaveLength(1);
  });

  it('other errors are untouched: no refresh on 403/500', async () => {
    signedIn();
    const calls = stubApi({ 'GET /api/auth/me': () => problem(403, 'forbidden') });
    await expect(createAuthedClient('http://localhost').GET('/api/auth/me')).rejects.toMatchObject({ status: 403 });
    expect(calls).toHaveLength(1);
  });
});
