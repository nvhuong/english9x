import { useAuthStore } from '../app/stores/auth';
import { useContextStore } from '../app/stores/context';
import { refreshSession } from '../features/auth/session';
import { createApiClient } from './generated/client';
import { problemFromResponse } from './problem';

/** Internal marker: the request is sent without X-Org-Context, and the marker itself never leaves the browser. */
const NO_CONTEXT_HEADER = 'x-e9x-no-context';

/**
 * The generated OpenAPI client plus:
 *  - `Authorization: Bearer <token>` from the auth store (filled by P1.4), omitted when signed out;
 *  - `X-Org-Context: <orgId>` from the context store, omitted in the personal context;
 *  - non-2xx responses throw {@link ApiProblemError} (typed problem+json incl. `errors[]`), so TanStack Query sees an error.
 * Both headers are read when the request is made, so a context switch applies to the very next call.
 *  - a 401 on a request that carried a token triggers ONE transparent refresh (single-flight, shared by every request that
 *    fails at the same time) and a single retry of the original request with the new token. If the refresh fails the
 *    session has ended and the 401 is thrown as usual.
 */
export function createAuthedClient(baseUrl = '') {
  const client = createApiClient(baseUrl);
  // A request's body can be read once; keep a pristine copy so the request can be repeated after a refresh.
  const copies = new WeakMap<Request, Request>();
  client.use({
    onRequest({ request }) {
      const token = useAuthStore.getState().accessToken;
      const orgId = useContextStore.getState().orgId;
      if (token) request.headers.set('Authorization', `Bearer ${token}`);
      // GET /api/me/context asks who the person is and where they belong: it must never carry a context that may be stale.
      const skipContext = request.headers.has(NO_CONTEXT_HEADER);
      request.headers.delete(NO_CONTEXT_HEADER);
      if (orgId && !skipContext) request.headers.set('X-Org-Context', orgId);
      if (token) copies.set(request, request.clone());
      return request;
    },
    async onResponse({ request, response }) {
      if (response.status === 401 && copies.has(request)) {
        const retry = copies.get(request)!;
        copies.delete(request);
        const current = useAuthStore.getState().accessToken;
        const sent = request.headers.get('Authorization');
        // Somebody already refreshed since this request was sent: just repeat it with the current token.
        const fresh = current && sent !== `Bearer ${current}` ? current : await refreshSession();
        if (fresh) {
          retry.headers.set('Authorization', `Bearer ${fresh}`);
          const again = await fetch(retry);
          if (!again.ok) throw await problemFromResponse(again);
          return again;
        }
      }
      if (!response.ok) throw await problemFromResponse(response);
      return response;
    },
  });
  return client;
}

export type ApiClient = ReturnType<typeof createAuthedClient>;

let shared: ApiClient | undefined;

/** Stable, app-wide client. Use inside components and query functions. */
export function useApi(): ApiClient {
  shared ??= createAuthedClient();
  return shared;
}
