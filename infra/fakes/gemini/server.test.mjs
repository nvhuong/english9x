import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createFakeGemini } from './server.mjs';

async function start() {
  const server = createFakeGemini();
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  return { base, close: () => new Promise((r) => server.close(r)) };
}
const post = (url, body) =>
  fetch(url, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body ?? {}) });

test('default scenario ok: returns a candidate whose text is parseable JSON, plus usage', async () => {
  const { base, close } = await start();
  try {
    const res = await post(`${base}/v1beta/models/gemini-2.5-flash:generateContent`, { contents: [] });
    assert.equal(res.status, 200);
    const body = await res.json();
    const parsed = JSON.parse(body.candidates[0].content.parts[0].text);
    assert.equal(typeof parsed.overallBand, 'number');
    assert.ok(body.usageMetadata.promptTokenCount > 0);
  } finally { await close(); }
});

test('error500 scenario returns HTTP 500 with a Google-style error body', async () => {
  const { base, close } = await start();
  try {
    assert.equal((await post(`${base}/__scenario`, { scenario: 'error500' })).status, 200);
    const res = await post(`${base}/v1beta/models/m:generateContent`);
    assert.equal(res.status, 500);
    assert.equal((await res.json()).error.code, 500);
  } finally { await close(); }
});

test('badSchema scenario returns 200 whose text is JSON missing the required fields', async () => {
  const { base, close } = await start();
  try {
    await post(`${base}/__scenario`, { scenario: 'badSchema' });
    const res = await post(`${base}/v1beta/models/m:generateContent`);
    assert.equal(res.status, 200);
    const parsed = JSON.parse((await res.json()).candidates[0].content.parts[0].text);
    assert.equal(parsed.overallBand, undefined);
  } finally { await close(); }
});

test('unknown scenario is rejected and does not change the current one; GET reports current', async () => {
  const { base, close } = await start();
  try {
    await post(`${base}/__scenario`, { scenario: 'error500' });
    assert.equal((await post(`${base}/__scenario`, { scenario: 'nope' })).status, 400);
    assert.deepEqual(await (await fetch(`${base}/__scenario`)).json(), { scenario: 'error500' });
  } finally { await close(); }
});

test('requests are recorded and can be listed and cleared; unknown paths 404; health is 200', async () => {
  const { base, close } = await start();
  try {
    await post(`${base}/v1beta/models/gemini-x:generateContent`, { contents: ['hi'] });
    const list = await (await fetch(`${base}/__requests`)).json();
    assert.equal(list.length, 1);
    assert.equal(list[0].model, 'gemini-x');
    assert.deepEqual(list[0].body, { contents: ['hi'] });
    await fetch(`${base}/__requests`, { method: 'DELETE' });
    assert.deepEqual(await (await fetch(`${base}/__requests`)).json(), []);
    assert.equal((await fetch(`${base}/nothing`)).status, 404);
    assert.equal((await fetch(`${base}/__health`)).status, 200);
  } finally { await close(); }
});
