import { QueryClient, QueryClientProvider, useMutation, useQuery } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { ctxKey, guardContextWrites, publicKey, switchContext, useContextStore, useCtxKey } from './context';

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
    qc.setQueryData(publicKey('catalog'), ['C']);
    useContextStore.setState({ orgId: 'a' });
    await switchContext(qc, 'b');
    expect(useContextStore.getState().orgId).toBe('b');
    expect(qc.getQueryData(ctxKey('a', 'items'))).toBeUndefined();
    expect(qc.getQueryData(publicKey('catalog'))).toEqual(['C']);
  });

  it('an in-flight response from the old context never lands in the new one (Review Focus #1)', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const slowA = deferred<string[]>();
    const rendered: string[] = [];

    function Items() {
      const org = useContextStore((s) => s.orgId);
      const q = useQuery({ queryKey: useCtxKey('items'), queryFn: () => (org === 'a' ? slowA.promise : Promise.resolve(['B1'])) });
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

describe('inverted default: everything except public keys is dropped on switch', () => {
  it('publicKey builds [\'public\', ...] keys', () => {
    expect(publicKey('catalog', 1)).toEqual(['public', 'catalog', 1]);
  });

  it('a query whose key does NOT use the helpers is removed on switch', async () => {
    const qc = new QueryClient();
    qc.setQueryData(['courses'], ['A-COURSE']);
    useContextStore.setState({ orgId: 'a' });
    await switchContext(qc, 'b');
    expect(qc.getQueryData(['courses'])).toBeUndefined();
  });

  it('an in-flight old-context response under a non-helper key does not land or render', async () => {
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const slowA = deferred<string[]>();
    const rendered: string[] = [];
    function Courses() {
      const org = useContextStore((s) => s.orgId);
      // deliberately NOT built with a helper (the lint rule is off in tests for this): the runtime must still be safe
      const q = useQuery({ queryKey: ['courses'], queryFn: () => (org === 'a' ? slowA.promise : Promise.resolve(['B-COURSE'])) });
      rendered.push(q.data?.join(',') ?? '-');
      return <p data-testid="out">{q.data ? q.data.join(',') : 'loading'}</p>;
    }
    useContextStore.setState({ orgId: 'a' });
    render(<QueryClientProvider client={qc}><Courses /></QueryClientProvider>);
    await act(() => switchContext(qc, 'b'));
    await waitFor(() => expect(screen.getByTestId('out')).toHaveTextContent('B-COURSE'));
    await act(async () => {
      slowA.resolve(['A-LEAKED']);
      await slowA.promise;
    });
    expect(screen.getByTestId('out')).toHaveTextContent('B-COURSE');
    expect(rendered.join('|')).not.toContain('A-LEAKED');
    expect(qc.getQueryData(['courses'])).toEqual(['B-COURSE']);
  });

  it('a public key survives a switch and a fetch in flight for it is not cancelled', async () => {
    const qc = new QueryClient();
    const slow = deferred<string[]>();
    const p = qc.fetchQuery({ queryKey: publicKey('catalog'), queryFn: () => slow.promise });
    useContextStore.setState({ orgId: 'a' });
    await switchContext(qc, 'b');
    slow.resolve(['C']);
    await p;
    expect(qc.getQueryData(publicKey('catalog'))).toEqual(['C']);
  });
});

describe('mutations after a switch', () => {
  it("a mutation's onSuccess cache write for the OLD context does not repopulate the cache", async () => {
    const qc = new QueryClient();
    guardContextWrites(qc);
    const slow = deferred<string>();
    useContextStore.setState({ orgId: 'a' });
    function Save() {
      const m = useMutation({
        mutationFn: () => slow.promise,
        // captured old key, as a typical onSuccess would do
        onSuccess: (v) => qc.setQueryData(ctxKey('a', 'items'), [v]),
      });
      return <button onClick={() => m.mutate()}>save</button>;
    }
    render(<QueryClientProvider client={qc}><Save /></QueryClientProvider>);
    act(() => screen.getByText('save').click());
    await act(() => switchContext(qc, 'b'));
    await act(async () => {
      slow.resolve('A-LATE');
      await slow.promise;
    });
    expect(qc.getQueryData(ctxKey('a', 'items'))).toBeUndefined();
    expect(qc.getQueryData(ctxKey('b', 'items'))).toBeUndefined();
  });

  it('writes for the current context are still allowed', () => {
    const qc = new QueryClient();
    guardContextWrites(qc);
    useContextStore.setState({ orgId: 'b' });
    qc.setQueryData(ctxKey('b', 'items'), ['ok']);
    expect(qc.getQueryData(ctxKey('b', 'items'))).toEqual(['ok']);
  });
});
