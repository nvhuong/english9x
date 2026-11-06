import { useAuthStore } from '../app/stores/auth';
import { useContextStore } from '../app/stores/context';
import { createApiClient } from './generated/client';
import { problemFromResponse } from './problem';

/**
 * The generated OpenAPI client plus:
 *  - `Authorization: Bearer <token>` from the auth store (filled by P1.4), omitted when signed out;
 *  - `X-Org-Context: <orgId>` from the context store, omitted in the personal context;
 *  - non-2xx responses throw {@link ApiProblemError} (typed problem+json incl. `errors[]`), so TanStack Query sees an error.
 * Both headers are read when the request is made, so a context switch applies to the very next call.
 */
export function createAuthedClient(baseUrl = '') {
  const client = createApiClient(baseUrl);
  client.use({
    onRequest({ request }) {
      const token = useAuthStore.getState().accessToken;
      const orgId = useContextStore.getState().orgId;
      if (token) request.headers.set('Authorization', `Bearer ${token}`);
      if (orgId) request.headers.set('X-Org-Context', orgId);
      return request;
    },
    async onResponse({ response }) {
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
