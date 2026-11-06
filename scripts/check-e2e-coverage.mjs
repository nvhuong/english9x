#!/usr/bin/env node
// Gate: every feature F01–F58 in e2e/features.json must have a Playwright spec file
// containing at least one active test (not only test.fixme / test.skip, not commented out).
// The manifest must list exactly the 58 unique ids F01..F58.
// Exit 0 only when all features are covered. Usage: node scripts/check-e2e-coverage.mjs [--json]
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const FEATURE_COUNT = 58;
const EXPECTED_IDS = Array.from({ length: FEATURE_COUNT }, (_, i) => `F${String(i + 1).padStart(2, '0')}`);

const ACTIVE_TEST = /(^|[^.\w])test(\.only)?\s*\(\s*['"`]/m;

/** Removes // line comments and /* *\/ blocks, leaving string literals intact. */
export function stripComments(source) {
  let out = '';
  let i = 0;
  while (i < source.length) {
    const c = source[i];
    const next = source[i + 1];
    if (c === '/' && next === '/') {
      while (i < source.length && source[i] !== '\n') i++;
    } else if (c === '/' && next === '*') {
      const end = source.indexOf('*/', i + 2);
      const stop = end === -1 ? source.length : end + 2;
      // keep line breaks so ^-anchored matches still see line starts
      out += source.slice(i, stop).replace(/[^\n]/g, ' ');
      i = stop;
    } else if (c === '"' || c === "'" || c === '`') {
      let j = i + 1;
      while (j < source.length && source[j] !== c) {
        if (source[j] === '\\') j++;
        else if (c !== '`' && source[j] === '\n') break;
        j++;
      }
      out += source.slice(i, j + 1);
      i = j + 1;
    } else {
      out += c;
      i++;
    }
  }
  return out;
}

/** True when the spec source has at least one active (uncommented, non-fixme/skip) test. */
export function hasActiveTest(source) {
  return ACTIVE_TEST.test(stripComments(source));
}

/** Returns a list of problems; empty when the manifest holds exactly the unique ids F01..F58. */
export function manifestProblems(manifest) {
  const features = Array.isArray(manifest?.features) ? manifest.features : [];
  const ids = features.map((f) => f?.id);
  const problems = [];
  if (ids.length !== FEATURE_COUNT) problems.push(`expected ${FEATURE_COUNT} features, found ${ids.length}`);
  const seen = new Set();
  for (const id of ids) {
    if (seen.has(id)) problems.push(`duplicate id ${id}`);
    seen.add(id);
    if (!EXPECTED_IDS.includes(id)) problems.push(`unexpected id ${id}`);
  }
  for (const id of EXPECTED_IDS) if (!seen.has(id)) problems.push(`missing id ${id}`);
  return problems;
}

function main() {
  const root = join(dirname(fileURLToPath(import.meta.url)), '..');
  const manifest = JSON.parse(readFileSync(join(root, 'e2e/features.json'), 'utf8'));
  const problems = manifestProblems(manifest);

  const results = manifest.features.map((f) => {
    const file = join(root, f.spec);
    if (!existsSync(file)) return { ...f, status: 'missing' };
    const source = readFileSync(file, 'utf8');
    if (!source.includes(f.id)) return { ...f, status: 'no-feature-tag' };
    return { ...f, status: hasActiveTest(source) ? 'covered' : 'only-skipped' };
  });

  const covered = results.filter((r) => r.status === 'covered');
  if (process.argv.includes('--json')) {
    console.log(JSON.stringify({ total: results.length, covered: covered.length, manifestProblems: problems, results }, null, 2));
  } else {
    for (const p of problems) console.log(`MANIFEST ${p}`);
    for (const r of results) console.log(`${r.status === 'covered' ? 'OK  ' : 'MISS'} ${r.id} ${r.title} (${r.status}) ${r.spec}`);
    console.log(`\nE2E coverage: ${covered.length}/${results.length} features have an active spec`);
  }
  process.exit(problems.length === 0 && covered.length === results.length ? 0 : 1);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
