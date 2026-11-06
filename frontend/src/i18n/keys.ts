/** Flatten a nested translation dictionary to sorted dotted keys ("a.b.c"). */
export function flattenKeys(dict: unknown, prefix = ''): string[] {
  if (dict === null || typeof dict !== 'object') return prefix ? [prefix] : [];
  return Object.entries(dict as Record<string, unknown>)
    .flatMap(([k, v]) => flattenKeys(v, prefix ? `${prefix}.${k}` : k))
    .sort();
}

export function keyDiff(a: unknown, b: unknown): { onlyA: string[]; onlyB: string[] } {
  const ka = new Set(flattenKeys(a));
  const kb = new Set(flattenKeys(b));
  return { onlyA: [...ka].filter((k) => !kb.has(k)), onlyB: [...kb].filter((k) => !ka.has(k)) };
}

/** `{{name}}` placeholders used by a string, sorted. */
export function placeholders(s: string): string[] {
  return [...s.matchAll(/\{\{\s*(\w+)\s*\}\}/g)].map((m) => m[1]!).sort();
}
