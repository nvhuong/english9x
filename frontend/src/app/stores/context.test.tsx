import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { ctxKey, switchContext, useContextStore, useCtxKey } from './context';

function deferred<T>() {
  let resolve!: (v: T) => void;
  const promise = new Promise<T>((r) => (resolve = r));
  return { promise, resolve };
}

beforeEach(() => useContextStore.setState({ orgId: null }));

describe('ctxKey', () => {
  it("scopes keys as ['ctx', orgId, ...]; personal context is its own scope", () => {
    expect(ctxKey('org-a', 'courses', 1)).toEqual(['ctx', 'org-a', 'courses', 1]);
    expect(ctxKey(null, 'courses')).toEqual(['ctx', 'personal', 'courses']);
  });
});

describe('switching context', () => {
  it('drops cached data of the old context and keeps unscoped data', async () => {
    const qc = new QueryClient();
    qc.setQueryData(ctxKey('a', 'items'), ['A1']);
    qc.setQueryData(['public', 'catalog'], ['C']);
    useContextStore.setState({ orgId: 'a' });
    await switchContext(qc, 'b');
    expect(useContextStore.getState().orgId).toBe('b');
    expect(qc.getQueryData(ctxKey('a', 'items'))).toBeUndefined();
    expect(qc.getQueryData(['public', 'catalog'])).toEqual(['C']);
  });

  it('an in-flight response from the old context never lands in the new one (Review Focus #1)', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const slowA = deferred<string[]>();
    const rendered: string[] = [];

    function Items() {
      const key = useCtxKey('items');
      const org = useContextStore((s) => s.orgId);
      const q = useQuery({ queryKey: key, queryFn: () => (org === 'a' ? slowA.promise : Promise.resolve(['B1'])) });
      rendered.push(q.data?.join(',') ?? '-');
      return <p data-testid="out">{q.data ? q.data.join(',') : 'loading'}</p>;
    }

    useContextStore.setState({ orgId: 'a' });
    render(
      <QueryClientProvider client={qc}>
        <Items />
      </QueryClientProvider>,
    );
    expect(screen.getByTestId('out')).toHaveTextContent('loading');

    await act(() => switchContext(qc, 'b'));
    await waitFor(() => expect(screen.getByTestId('out')).toHaveTextContent('B1'));

    // The slow response for A arrives only now.
    await act(async () => {
      slowA.resolve(['A-LEAKED']);
      await slowA.promise;
    });

    expect(screen.getByTestId('out')).toHaveTextContent('B1');
    expect(rendered.join('|')).not.toContain('A-LEAKED');
    expect(qc.getQueryData(ctxKey('a', 'items'))).toBeUndefined();
    expect(qc.getQueryData(ctxKey('b', 'items'))).toEqual(['B1']);
  });

  it('does not remove queries already started for the new context', async () => {
    const qc = new QueryClient();
    qc.setQueryData(ctxKey('b', 'items'), ['B1']);
    useContextStore.setState({ orgId: 'a' });
    await switchContext(qc, 'b');
    expect(qc.getQueryData(ctxKey('b', 'items'))).toEqual(['B1']);
  });
});
