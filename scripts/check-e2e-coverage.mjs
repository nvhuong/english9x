#!/usr/bin/env node
// Gate: every feature F01–F58 in e2e/features.json must have a Playwright spec file
// containing at least one active test (not only test.fixme / test.skip, not commented out).
// The manifest must list exactly the 58 unique ids F01..F58.
//
// A feature whose spec exists but cannot yet cover the whole §16.2 scenario is listed in e2e/partial-features.json with what
// is pending and the task that owns it. It then counts as PARTIAL, not covered: the report shows covered and partial
// separately, and the strict gate (E9X_E2E_GATE=strict) fails while that list is non-empty. A spec is never faked or skipped to
// make a feature look complete; an honest partial spec plus an entry in the list is the way to land what exists.
//
// Exit 0 only when every feature is complete (covered, not partial). Usage:
//   node scripts/check-e2e-coverage.mjs [--json]        report; exit 1 while anything is partial or missing
//   node scripts/check-e2e-coverage.mjs --gate          verdict used by verify.sh / CI: tolerant of progress unless E9X_E2E_GATE=strict
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

/**
 * Problems of e2e/partial-features.json ({"features": [{id, pending, owner}]}): every entry names a known feature once, says
 * what is pending and who owns it, and belongs to a feature that really has an active spec (partial means "some of it").
 * `results` are the rows of the manifest with their status ('covered' = an active spec exists).
 */
export function partialProblems(list, results) {
  if (list === undefined || list === null) return [];
  const entries = list.features;
  if (!Array.isArray(entries)) return ['partial-features.json: "features" must be an array'];
  const problems = [];
  const seen = new Set();
  for (const e of entries) {
    const id = e?.id;
    const row = results.find((r) => r.id === id);
    if (!row) problems.push(`partial-features.json: ${id} is not a feature of the manifest`);
    else if (row.status !== 'covered') problems.push(`partial-features.json: ${id} has no active spec (${row.status}); only a feature with a spec can be partial`);
    if (seen.has(id)) problems.push(`partial-features.json: duplicate ${id}`);
    seen.add(id);
    if (typeof e?.pending !== 'string' || e.pending.trim() === '') problems.push(`partial-features.json: ${id} needs "pending" (what the spec does not cover yet)`);
    if (typeof e?.owner !== 'string' || e.owner.trim() === '') problems.push(`partial-features.json: ${id} needs "owner" (the task that finishes it)`);
  }
  return problems;
}

/** Counts: complete (active spec, not listed partial), partial (active spec, listed), missing (anything else). */
export function summarize(results, list) {
  const listed = new Set((Array.isArray(list) ? list : []).map((e) => e?.id));
  const complete = results.filter((r) => r.status === 'covered' && !listed.has(r.id));
  const partial = results.filter((r) => r.status === 'covered' && listed.has(r.id));
  const missing = results.filter((r) => r.status !== 'covered');
  return {
    total: results.length,
    complete: complete.length,
    partial: partial.length,
    missing: missing.length,
    partialIds: partial.map((r) => r.id),
    missingIds: missing.map((r) => r.id),
  };
}

/** The verdict of verify.sh / CI. Progress is tolerated until W10 unless `strict`; a problem in the files always fails. */
export function gateVerdict(summary, strict) {
  const counts = `covered ${summary.complete}/${summary.total}, partial ${summary.partial}, missing ${summary.missing}`;
  if (summary.problems.length > 0) return { ok: false, message: `E2E coverage gate: FAIL, invalid manifest or partial list (${summary.problems.length} problem(s))` };
  if (summary.complete === summary.total) return { ok: true, message: `E2E coverage gate: PASS (${counts})` };
  if (strict) return { ok: false, message: `E2E coverage gate: FAIL (${counts}) [E9X_E2E_GATE=strict: no feature may be partial or missing]` };
  return { ok: true, message: `E2E coverage gate: coverage-progress ${counts} (allowed until W10; E9X_E2E_GATE=strict enforces)` };
}

function main() {
  const root = join(dirname(fileURLToPath(import.meta.url)), '..');
  const manifest = JSON.parse(readFileSync(join(root, 'e2e/features.json'), 'utf8'));
  const partialFile = join(root, 'e2e/partial-features.json');
  const partialList = existsSync(partialFile) ? JSON.parse(readFileSync(partialFile, 'utf8')) : undefined;
  const problems = manifestProblems(manifest);

  const results = manifest.features.map((f) => {
    const file = join(root, f.spec);
    if (!existsSync(file)) return { ...f, status: 'missing' };
    const source = readFileSync(file, 'utf8');
    if (!source.includes(f.id)) return { ...f, status: 'no-feature-tag' };
    return { ...f, status: hasActiveTest(source) ? 'covered' : 'only-skipped' };
  });
  const listProblems = partialProblems(partialList, results);
  const summary = summarize(results, partialList?.features);
  const notes = new Map((partialList?.features ?? []).map((e) => [e.id, e]));
  const rowsOut = results.map((r) => ({ ...r, status: r.status === 'covered' && notes.has(r.id) ? 'partial' : r.status, ...(notes.has(r.id) ? { pending: notes.get(r.id).pending, owner: notes.get(r.id).owner } : {}) }));

  if (process.argv.includes('--gate')) {
    const verdict = gateVerdict({ ...summary, problems: [...problems, ...listProblems] }, process.env.E9X_E2E_GATE === 'strict');
    for (const p of [...problems, ...listProblems]) console.log(`PROBLEM ${p}`);
    if (!verdict.ok || process.env.E9X_E2E_GATE === 'strict') for (const r of rowsOut.filter((x) => x.status === 'partial')) console.log(`PARTIAL ${r.id} ${r.title}: pending ${r.pending} (owner ${r.owner})`);
    (verdict.ok ? console.log : console.error)(verdict.message);
    process.exit(verdict.ok ? 0 : 1);
  }
  if (process.argv.includes('--json')) {
    console.log(JSON.stringify({ total: summary.total, covered: summary.complete, partial: summary.partial, missing: summary.missing,
      manifestProblems: problems, partialProblems: listProblems, results: rowsOut }, null, 2));
  } else {
    for (const p of [...problems, ...listProblems]) console.log(`PROBLEM ${p}`);
    for (const r of rowsOut) {
      const tag = r.status === 'covered' ? 'OK  ' : r.status === 'partial' ? 'PART' : 'MISS';
      console.log(`${tag} ${r.id} ${r.title} (${r.status}) ${r.spec}${r.status === 'partial' ? `\n       pending: ${r.pending} [owner ${r.owner}]` : ''}`);
    }
    console.log(`\nE2E coverage: covered ${summary.complete}/${summary.total}, partial ${summary.partial}, missing ${summary.missing}`);
    if (summary.partial > 0) console.log(`  partial: ${summary.partialIds.join(', ')} (specs exist; see e2e/partial-features.json for what each lacks)`);
  }
  process.exit(problems.length === 0 && listProblems.length === 0 && summary.complete === summary.total ? 0 : 1);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
