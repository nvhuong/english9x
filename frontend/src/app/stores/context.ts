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
 * Change context safely: store first (new requests carry the new header and key), then cancel and drop every
 * context-scoped query that belongs to a DIFFERENT context. Because keys include the context, a late response
 * from the old context can only ever land under the old key, which no longer exists / is never read.
 */
export async function switchContext(queryClient: QueryClient, orgId: string | null): Promise<void> {
  if (useContextStore.getState().orgId === orgId) return;
  useContextStore.setState({ orgId });
  const stale = {
    predicate: (q: { queryKey: QueryKey }) => q.queryKey[0] === 'ctx' && q.queryKey[1] !== scope(orgId),
  };
  await queryClient.cancelQueries(stale);
  queryClient.removeQueries(stale);
}
