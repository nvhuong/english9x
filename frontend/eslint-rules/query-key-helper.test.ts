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
