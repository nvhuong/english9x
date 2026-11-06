#!/usr/bin/env node
// Gate: every feature F01–F58 in e2e/features.json must have a Playwright spec file
// containing at least one active test (not only test.fixme / test.skip).
// Exit 0 only when all features are covered. Usage: node scripts/check-e2e-coverage.mjs [--json]
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const manifest = JSON.parse(readFileSync(join(root, 'e2e/features.json'), 'utf8'));

const ACTIVE_TEST = /(^|[^.\w])test(\.only)?\s*\(\s*['"`]/m;

const results = manifest.features.map((f) => {
  const file = join(root, f.spec);
  if (!existsSync(file)) return { ...f, status: 'missing' };
  const source = readFileSync(file, 'utf8');
  if (!source.includes(f.id)) return { ...f, status: 'no-feature-tag' };
  return { ...f, status: ACTIVE_TEST.test(source) ? 'covered' : 'only-skipped' };
});

const covered = results.filter((r) => r.status === 'covered');
if (process.argv.includes('--json')) {
  console.log(JSON.stringify({ total: results.length, covered: covered.length, results }, null, 2));
} else {
  for (const r of results) console.log(`${r.status === 'covered' ? 'OK  ' : 'MISS'} ${r.id} ${r.title} (${r.status}) ${r.spec}`);
  console.log(`\nE2E coverage: ${covered.length}/${results.length} features have an active spec`);
}
process.exit(covered.length === results.length ? 0 : 1);
