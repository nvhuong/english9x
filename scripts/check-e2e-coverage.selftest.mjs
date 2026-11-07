#!/usr/bin/env node
// Self-test for the e2e coverage gate matcher. Usage: node scripts/check-e2e-coverage.selftest.mjs
import assert from 'node:assert/strict';
import { gateVerdict, hasActiveTest, manifestProblems, partialProblems, summarize } from './check-e2e-coverage.mjs';

const cases = [
  ['active test', `test('F01 register', async () => {});`, true],
  ['test.only', `test.only("F01", async () => {});`, true],
  ['only line-commented test', `// test('F01 register', async () => {});\ntest.fixme('F01', async () => {});`, false],
  ['only block-commented test', `/*\n test('F01 register', async () => {});\n*/`, false],
  ['test.fixme only', `test.fixme('F01 register', async () => {});`, false],
  ['test.skip only', `test.skip('F01 register', async () => {});`, false],
  ['url in string is not a comment', `const u = 'http://x.test/a';\ntest('F01', async () => {});`, true],
  ['comment marker inside string', `const s = "/* not a comment";\ntest('F01', async () => {});\nconst t = "*/";`, true],
];
for (const [name, source, expected] of cases) {
  assert.equal(hasActiveTest(source), expected, name);
}

const ids = Array.from({ length: 58 }, (_, i) => `F${String(i + 1).padStart(2, '0')}`);
const manifest = (list) => ({ features: list.map((id) => ({ id, title: id, spec: `e2e/specs/${id}.spec.ts` })) });
assert.deepEqual(manifestProblems(manifest(ids)), [], 'complete manifest');
assert.notDeepEqual(manifestProblems(manifest(ids.slice(0, 57))), [], 'missing F58');
assert.notDeepEqual(manifestProblems(manifest([...ids.slice(0, 57), 'F01'])), [], 'duplicate id');
assert.notDeepEqual(manifestProblems(manifest([...ids, 'F59'])), [], 'extra id');
assert.notDeepEqual(manifestProblems(manifest([...ids.slice(0, 57), 'F99'])), [], 'unknown id');

// ---- partial features: a spec exists but cannot yet cover the whole §16.2 scenario ----------------------------------------------
const rows = (statuses) => Object.entries(statuses).map(([id, status]) => ({ id, title: id, spec: `e2e/specs/${id}.spec.ts`, status }));
const partialEntry = (id, extra = {}) => ({ id, pending: 'the checkout clause', owner: 'P5.1', ...extra });

// classification: covered (complete) vs partial vs missing are counted separately
const sum = summarize(rows({ F01: 'covered', F02: 'covered', F03: 'covered', F04: 'missing' }), [partialEntry('F02')]);
assert.deepEqual([sum.total, sum.complete, sum.partial, sum.missing], [4, 2, 1, 1], 'counts');
assert.deepEqual(sum.partialIds, ['F02']);
// a partial entry for a feature without an active spec does not make it "partial": it is missing, and the list entry is a problem
const odd = summarize(rows({ F01: 'missing' }), [partialEntry('F01')]);
assert.deepEqual([odd.complete, odd.partial, odd.missing], [0, 0, 1], 'partial needs a spec');

// the list itself is validated
assert.deepEqual(partialProblems({ features: [partialEntry('F01')] }, rows({ F01: 'covered' })), [], 'valid list');
assert.deepEqual(partialProblems(undefined, rows({ F01: 'covered' })), [], 'no list = nothing is partial');
assert.notDeepEqual(partialProblems({ features: [partialEntry('F01', { owner: '' })] }, rows({ F01: 'covered' })), [], 'owner is required');
assert.notDeepEqual(partialProblems({ features: [partialEntry('F01', { pending: '  ' })] }, rows({ F01: 'covered' })), [], 'what is pending is required');
assert.notDeepEqual(partialProblems({ features: [partialEntry('F99')] }, rows({ F01: 'covered' })), [], 'unknown feature');
assert.notDeepEqual(partialProblems({ features: [partialEntry('F01'), partialEntry('F01')] }, rows({ F01: 'covered' })), [], 'duplicate');
assert.notDeepEqual(partialProblems({ features: [partialEntry('F01')] }, rows({ F01: 'missing' })), [], 'a feature without a spec cannot be partial');
assert.notDeepEqual(partialProblems({ features: 'F01' }, rows({ F01: 'covered' })), [], 'wrong shape');

// the verdict: normal mode tolerates progress, strict mode refuses any partial or missing feature, a problem always fails
const done = { total: 3, complete: 3, partial: 0, missing: 0, problems: [] };
const withPartial = { total: 3, complete: 2, partial: 1, missing: 0, problems: [] };
const withMissing = { total: 3, complete: 1, partial: 1, missing: 1, problems: [] };
assert.equal(gateVerdict(done, false).ok, true, 'all complete passes');
assert.equal(gateVerdict(done, true).ok, true, 'all complete passes in strict mode');
assert.equal(gateVerdict(withPartial, false).ok, true, 'normal mode tolerates a partial feature');
assert.match(gateVerdict(withPartial, false).message, /covered 2\/3.*partial 1/, 'normal mode reports covered and partial separately');
assert.equal(gateVerdict(withPartial, true).ok, false, 'strict mode fails while the partial list is non-empty');
assert.equal(gateVerdict(withMissing, true).ok, false, 'strict mode fails on a missing feature');
assert.equal(gateVerdict(withMissing, false).ok, true, 'normal mode tolerates progress');
assert.equal(gateVerdict({ ...done, problems: ['bad'] }, false).ok, false, 'a problem fails in normal mode too');

console.log(`check-e2e-coverage selftest: ${cases.length + 5 + 20} assertions passed`);
