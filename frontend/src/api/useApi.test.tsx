import { renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useAuthStore } from '../app/stores/auth';
import { useContextStore } from '../app/stores/context';
import { ApiProblemError } from './problem';
import { createAuthedClient, useApi } from './useApi';

const seen: Request[] = [];
function stubFetch(respond: () => Response) {
  vi.stubGlobal('fetch', vi.fn(async (input: Request | string) => {
    seen.push(input as Request);
    return respond();
  }));
}
const ok = () => new Response(JSON.stringify({ status: 'pong' }), { status: 200, headers: { 'content-type': 'application/json' } });

beforeEach(() => {
  seen.length = 0;
  useAuthStore.setState({ accessToken: null });
  useContextStore.setState({ orgId: null });
});
afterEach(() => vi.unstubAllGlobals());

describe('useApi', () => {
  it('attaches Authorization and X-Org-Context read at request time', async () => {
    stubFetch(ok);
    const { result } = renderHook(() => useApi());
    const client = createAuthedClient('http://x');
    useAuthStore.setState({ accessToken: 'tok-1' });
    useContextStore.setState({ orgId: 'org-7' });
    await client.GET('/api/platform/ping');
    expect(seen[0]!.headers.get('Authorization')).toBe('Bearer tok-1');
    expect(seen[0]!.headers.get('X-Org-Context')).toBe('org-7');

    useContextStore.setState({ orgId: 'org-8' });
    await client.GET('/api/platform/ping');
    expect(seen[1]!.headers.get('X-Org-Context')).toBe('org-8');
    expect(result.current).toBeDefined();
  });

  it('omits both headers when signed out and in the personal context', async () => {
    stubFetch(ok);
    await createAuthedClient('http://x').GET('/api/platform/ping');
    expect(seen[0]!.headers.has('Authorization')).toBe(false);
    expect(seen[0]!.headers.has('X-Org-Context')).toBe(false);
  });

  it('returns a stable client across renders', () => {
    const { result, rerender } = renderHook(() => useApi());
    const first = result.current;
    rerender();
    expect(result.current).toBe(first);
  });

  it('surfaces problem+json as a typed ApiProblemError including errors[]', async () => {
    stubFetch(
      () =>
        new Response(
          JSON.stringify({
            type: 'urn:e9x:problem:validation-failed',
            title: 'Validation failed',
            status: 400,
            detail: 'Two fields are invalid',
            instance: '/api/platform/ping',
            errors: [
              { field: 'rows[1].sku', code: 'NotBlank', message: 'must not be blank', index: 1 },
              { field: 'name', code: 'Size', message: 'too long' },
            ],
          }),
          { status: 400, headers: { 'content-type': 'application/problem+json' } },
        ),
    );
    const err = await createAuthedClient('http://x').GET('/api/platform/ping').then(
      () => null,
      (e: unknown) => e,
    );
    expect(err).toBeInstanceOf(ApiProblemError);
    const p = err as ApiProblemError;
    expect(p.status).toBe(400);
    expect(p.type).toBe('urn:e9x:problem:validation-failed');
    expect(p.title).toBe('Validation failed');
    expect(p.detail).toBe('Two fields are invalid');
    expect(p.errors).toHaveLength(2);
    expect(p.errors[0]).toMatchObject({ field: 'rows[1].sku', code: 'NotBlank', index: 1 });
    expect(p.message).toContain('Validation failed');
  });

  it('still throws a typed error (empty errors[]) when the failure body is not problem+json', async () => {
    stubFetch(() => new Response('<html>bad gateway</html>', { status: 502, statusText: 'Bad Gateway', headers: { 'content-type': 'text/html' } }));
    const err = (await createAuthedClient('http://x').GET('/api/platform/ping').catch((e: unknown) => e)) as ApiProblemError;
    expect(err).toBeInstanceOf(ApiProblemError);
    expect(err.status).toBe(502);
    expect(err.errors).toEqual([]);
  });
});
