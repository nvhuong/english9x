import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { StrictMode, type ReactElement } from 'react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { vi } from 'vitest';
import { ToastProvider } from '../../design';
import { useAuthStore } from '../../app/stores/auth';
import { refreshStorage } from './session';

export interface Call {
  method: string;
  path: string;
  search: string;
  body: unknown;
  headers: Headers;
}
export type Handler = (call: Call) => Response | Promise<Response>;

export const json = (status: number, body: unknown = {}, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': status >= 400 ? 'application/problem+json' : 'application/json', ...headers },
  });

export const problem = (status: number, slug: string, extra: Record<string, unknown> = {}) =>
  json(status, { type: `urn:e9x:problem:${slug}`, title: slug, status, detail: 'x', errors: [], ...extra });

export const tokens = (n = 1) => ({ accessToken: `access-${n}`, refreshToken: `refresh-${n}`, expiresIn: 900, tokenType: 'Bearer' });
export const me = (over: Record<string, unknown> = {}) => ({
  id: '11111111-1111-1111-1111-111111111111', email: 'an@example.test', displayName: 'An', emailVerified: true, locale: 'vi', ...over,
});

/** Replaces fetch: routes by "METHOD /path"; unknown routes fail the test loudly. Returns the recorded calls. */
export function stubApi(routes: Record<string, Handler>): Call[] {
  const calls: Call[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const req = input instanceof Request ? input : new Request(new URL(String(input), 'http://localhost'), init);
      const url = new URL(req.url);
      const text = await req.clone().text();
      const call: Call = {
        method: req.method,
        path: url.pathname,
        search: url.search,
        body: text ? JSON.parse(text) : undefined,
        headers: req.headers,
      };
      calls.push(call);
      const handler = routes[`${call.method} ${call.path}`];
      if (!handler) throw new Error(`unexpected request ${call.method} ${call.path}`);
      return handler(call);
    }),
  );
  return calls;
}

function Probe() {
  const l = useLocation();
  return <p data-testid="location">{l.pathname + l.search}</p>;
}

/** Renders a page at `path` inside a router, a fresh query client and (like main.tsx) StrictMode. */
export function renderAt(path: string, routePath: string, page: ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const view = render(
    <StrictMode>
      <QueryClientProvider client={client}>
        <ToastProvider>
          <MemoryRouter initialEntries={[path]}>
            <Routes>
              <Route path={routePath} element={page} />
              <Route path="*" element={<Probe />} />
            </Routes>
          </MemoryRouter>
        </ToastProvider>
      </QueryClientProvider>
    </StrictMode>,
  );
  return { ...view, client };
}

export function resetAuth() {
  refreshStorage.clear();
  localStorage.removeItem('e9x.auth.session');
  useAuthStore.setState({ accessToken: null, user: null, status: 'anonymous' });
}
