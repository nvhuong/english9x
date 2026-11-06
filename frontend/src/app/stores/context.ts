import type { QueryClient, QueryKey } from '@tanstack/react-query';
import { create } from 'zustand';

/**
 * Current context: `null` = "Cá nhân" (personal), otherwise an organisation id.
 * P1.5 builds ContextSwitcher.tsx and the real org list on top of `switchContext`.
 */
interface ContextState {
  orgId: string | null;
}

export const useContextStore = create<ContextState>(() => ({ orgId: null }));

const scope = (orgId: string | null) => orgId ?? 'personal';

/**
 * Query key for data that depends on the current context: `['ctx', orgId | 'personal', ...parts]`.
 * EVERY query whose answer depends on X-Org-Context must use this, so switching context cannot show old data.
 */
export function ctxKey(orgId: string | null, ...parts: unknown[]): QueryKey {
  return ['ctx', scope(orgId), ...parts];
}

/** Hook form of {@link ctxKey} bound to the current context. */
export function useCtxKey(...parts: unknown[]): QueryKey {
  const orgId = useContextStore((s) => s.orgId);
  return ctxKey(orgId, ...parts);
}

/**
 * Key for data that does NOT depend on the context (public catalogue, etc.): `['public', ...parts]`.
 * Only these survive a context switch. Every other key is treated as context-scoped and dropped.
 */
export function publicKey(...parts: unknown[]): QueryKey {
  return ['public', ...parts];
}

const isCurrentCtx = (key: QueryKey, orgId: string | null) => key[0] === 'ctx' && key[1] === scope(orgId);
const isPublic = (key: QueryKey) => key[0] === 'public';

/**
 * Change context safely. Default-deny: the store changes first (new requests carry the new header and key), then EVERY query
 * is cancelled and removed except `['public', ...]` ones and ones already keyed to the new context. A late response of a
 * cancelled/removed query cannot write into the cache. This does not depend on callers remembering to use {@link ctxKey}
 * (an ESLint rule, e9x/query-key-helper, also requires the helpers).
 */
export async function switchContext(queryClient: QueryClient, orgId: string | null): Promise<void> {
  if (useContextStore.getState().orgId === orgId) return;
  useContextStore.setState({ orgId });
  const stale = { predicate: (q: { queryKey: QueryKey }) => !isPublic(q.queryKey) && !isCurrentCtx(q.queryKey, orgId) };
  await queryClient.cancelQueries(stale);
  queryClient.removeQueries(stale);
}

/**
 * Runtime guard for writes that bypass fetching, e.g. a mutation's onSuccess calling setQueryData with a key captured in the
 * OLD context: a cache entry created for another context's `ctx` key is removed immediately. (Fetched results cannot land
 * because switchContext removed those queries.) Called by makeQueryClient.
 */
export function guardContextWrites(queryClient: QueryClient): () => void {
  return queryClient.getQueryCache().subscribe((event) => {
    if (event.type !== 'added') return;
    const key = event.query.queryKey;
    if (key[0] === 'ctx' && !isCurrentCtx(key, useContextStore.getState().orgId)) queryClient.getQueryCache().remove(event.query);
  });
}
