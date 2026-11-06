// @vitest-environment node
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { flattenKeys, keyDiff, placeholders } from './keys';

const dir = dirname(fileURLToPath(import.meta.url));
const read = (p: string): unknown => JSON.parse(readFileSync(join(dir, p), 'utf8'));
const nsFiles = readdirSync(join(dir, 'ns')).filter((f) => f.endsWith('.json'));
const namespaces = [...new Set(nsFiles.map((f) => f.replace(/\.(vi|en)\.json$/, '')))];

function leaves(dict: unknown, prefix = ''): Array<[string, string]> {
  if (typeof dict === 'string') return [[prefix, dict]];
  if (dict && typeof dict === 'object') return Object.entries(dict).flatMap(([k, v]) => leaves(v, prefix ? `${prefix}.${k}` : k));
  return [];
}

describe('i18n key parity', () => {
  it('vi.json and en.json have identical key sets', () => {
    expect(keyDiff(read('vi.json'), read('en.json'))).toEqual({ onlyA: [], onlyB: [] });
  });

  it('every key has a non-empty string in both languages and the same {{placeholders}}', () => {
    const vi = new Map(leaves(read('vi.json')));
    const en = new Map(leaves(read('en.json')));
    for (const [k, v] of vi) {
      expect(v.trim(), `vi ${k}`).not.toBe('');
      expect(en.get(k)?.trim(), `en ${k}`).toBeTruthy();
      expect(placeholders(en.get(k)!), `placeholders of ${k}`).toEqual(placeholders(v));
    }
  });

  it('every namespaced file has its partner with identical keys', () => {
    for (const ns of namespaces) {
      expect(nsFiles, `${ns}.vi.json`).toContain(`${ns}.vi.json`);
      expect(nsFiles, `${ns}.en.json`).toContain(`${ns}.en.json`);
      expect(keyDiff(read(`ns/${ns}.vi.json`), read(`ns/${ns}.en.json`)), ns).toEqual({ onlyA: [], onlyB: [] });
    }
  });

  it('the parity helper detects a missing key and nested structure', () => {
    expect(keyDiff({ a: { b: 'x' }, c: 'y' }, { a: { b: 'x' } })).toEqual({ onlyA: ['c'], onlyB: [] });
    expect(flattenKeys({ a: { b: 'x', c: { d: 'y' } } })).toEqual(['a.b', 'a.c.d']);
    expect(placeholders('Câu {{n}} / {{ total }}')).toEqual(['n', 'total']);
  });
});
