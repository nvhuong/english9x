/** One field of a version, by path (`content.options[1].text`), compared with the same field of the earlier version. */
export type FieldDiff = { path: string; status: 'same' | 'changed' | 'added' | 'removed'; before?: unknown; after?: unknown };

type Json = unknown;

/** Every leaf of a JSON value by path. An empty object or array is itself a leaf, so "was empty, now has items" still shows. */
export function flatten(value: Json, prefix = ''): Map<string, Json> {
  const out = new Map<string, Json>();
  const walk = (v: Json, path: string) => {
    if (Array.isArray(v) && v.length > 0) v.forEach((x, i) => walk(x, `${path}[${i}]`));
    else if (v !== null && typeof v === 'object' && Object.keys(v as object).length > 0) {
      for (const [k, x] of Object.entries(v as Record<string, Json>)) walk(x, path ? `${path}.${k}` : k);
    } else out.set(path || '(root)', v);
  };
  walk(value, prefix);
  return out;
}

/**
 * Field by field: a path in both with equal values is `same`, with different values `changed`, only in the new one `added`, only in the old one `removed`.
 * With no earlier version (`before` null or undefined) every field is `added`. The order is the new version's, then the removed ones.
 */
export function diffVersions(before: Json | null | undefined, after: Json): FieldDiff[] {
  const a = flatten(after);
  const b = before == null ? new Map<string, Json>() : flatten(before);
  const out: FieldDiff[] = [];
  for (const [path, value] of a) {
    if (!b.has(path)) out.push({ path, status: 'added', after: value });
    else out.push({ path, status: JSON.stringify(b.get(path)) === JSON.stringify(value) ? 'same' : 'changed', before: b.get(path), after: value });
  }
  for (const [path, value] of b) if (!a.has(path)) out.push({ path, status: 'removed', before: value });
  return out;
}

export function show(v: unknown): string {
  if (v === undefined) return '';
  if (typeof v === 'string') return v;
  return JSON.stringify(v);
}
