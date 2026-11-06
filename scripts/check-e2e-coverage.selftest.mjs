#!/usr/bin/env node
// Self-test for the e2e coverage gate matcher. Usage: node scripts/check-e2e-coverage.selftest.mjs
import assert from 'node:assert/strict';
import { hasActiveTest, manifestProblems } from './check-e2e-coverage.mjs';

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

console.log(`check-e2e-coverage selftest: ${cases.length + 5} assertions passed`);
