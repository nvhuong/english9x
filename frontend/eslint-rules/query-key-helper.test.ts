// @vitest-environment node
import { Linter } from 'eslint';
import { describe, expect, it } from 'vitest';
import plugin from './index.js';

const linter = new Linter({ configType: 'flat' });
const lint = (code: string) =>
  linter.verify(code, [{ files: ['**/*.js'], plugins: { e9x: plugin }, rules: { 'e9x/query-key-helper': 'error' } }], 'x.js').map((m) => m.message);

describe('e9x/query-key-helper', () => {
  it('rejects a literal queryKey (the bad example)', () => {
    const msgs = lint("useQuery({ queryKey: ['courses'], queryFn });");
    expect(msgs).toHaveLength(1);
    expect(msgs[0]).toMatch(/ctxKey|publicKey/);
  });
  it('rejects an identifier / template / other call', () => {
    expect(lint('useQuery({ queryKey: key, queryFn });')).toHaveLength(1);
    expect(lint('useQuery({ queryKey: makeKey("a"), queryFn });')).toHaveLength(1);
    expect(lint('qc.invalidateQueries({ queryKey: ["ctx", id] });')).toHaveLength(1);
  });
  it('accepts ctxKey, useCtxKey and publicKey calls', () => {
    expect(lint("useQuery({ queryKey: ctxKey(org, 'a'), queryFn });")).toEqual([]);
    expect(lint("useQuery({ queryKey: useCtxKey('a'), queryFn });")).toEqual([]);
    expect(lint("useQuery({ queryKey: publicKey('catalog'), queryFn });")).toEqual([]);
  });
  it('ignores unrelated properties', () => {
    expect(lint('const o = { key: 1, queryKeys: 2 };')).toEqual([]);
  });
});

describe('e9x/query-key-helper: the places a key is read, not only written', () => {
  it('rejects a literal key given straight to the query client', () => {
    for (const m of ['invalidateQueries', 'removeQueries', 'cancelQueries', 'resetQueries', 'refetchQueries', 'getQueryData', 'setQueryData', 'getQueryState', 'ensureQueryData', 'prefetchQuery', 'fetchQuery']) {
      expect(lint(`qc.${m}(['cms', 'questions']);`), m).toHaveLength(1);
      expect(lint(`queryClient.${m}(ctxKey(org, 'a'));`), `${m} with helper`).toEqual([]);
      expect(lint(`queryClient.${m}({ queryKey: ctxKey(org, 'a') });`), `${m} with object`).toEqual([]);
    }
    expect(lint('qc.invalidateQueries({ predicate: (q) => true });')).toEqual([]);
    expect(lint("qc.setQueriesData({ queryKey: ['x'] }, d);")).toHaveLength(1);
  });
  it('rejects a context-free first argument of ctxKey (the organisation is what scopes the key)', () => {
    expect(lint("useQuery({ queryKey: ctxKey('cms', 'questions'), queryFn });")).toHaveLength(1);
    expect(lint('useQuery({ queryKey: ctxKey(`cms`, 1), queryFn });')).toHaveLength(1);
    expect(lint("useQuery({ queryKey: ctxKey(null, 'cms'), queryFn });")).toEqual([]);
    expect(lint("useQuery({ queryKey: ctxKey(ctx, 'cms'), queryFn });")).toEqual([]);
    expect(lint("useQuery({ queryKey: ctxKey(useContextStore((s) => s.orgId), 'cms'), queryFn });")).toEqual([]);
  });
  it('rejects a key that is built by spreading or concatenating around the helper', () => {
    expect(lint("useQuery({ queryKey: [...ctxKey(org, 'a'), 'b'], queryFn });")).toHaveLength(1);
  });
  it('rejects a bare useQueries key inside the list', () => {
    expect(lint("useQueries({ queries: ids.map((id) => ({ queryKey: ['x', id], queryFn })) });")).toHaveLength(1);
  });
});
