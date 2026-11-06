import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { ToastProvider } from '../design';
import { ApiProblemError } from '../api/problem';
import { guardContextWrites } from './stores/context';

export function makeQueryClient() {
  const client = new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        // Client mistakes (4xx) will not fix themselves; do not hammer the API.
        retry: (count, err) => !(err instanceof ApiProblemError && err.status >= 400 && err.status < 500) && count < 2,
        refetchOnWindowFocus: false,
      },
    },
  });
  guardContextWrites(client);
  return client;
}

/** The query client is shared with `switchContext` (P1.5) through this module-level instance. */
export const appQueryClient = makeQueryClient();

export function AppProviders({ children, client }: { children: ReactNode; client?: QueryClient }) {
  const [qc] = useState(() => client ?? appQueryClient);
  return (
    <QueryClientProvider client={qc}>
      <ToastProvider>{children}</ToastProvider>
    </QueryClientProvider>
  );
}
