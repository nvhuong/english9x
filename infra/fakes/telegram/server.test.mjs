import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createFakeTelegram } from './server.mjs';

async function start() {
  const server = createFakeTelegram();
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  return { base, close: () => new Promise((r) => server.close(r)) };
}
const sendJson = (base, token, body) =>
  fetch(`${base}/bot${token}/sendMessage`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body),
  });

test('sendMessage stores the message and answers like the Bot API', async () => {
  const { base, close } = await start();
  try {
    const res = await sendJson(base, '123:ABC', { chat_id: 42, text: 'xin chào' });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.ok, true);
    assert.equal(body.result.text, 'xin chào');
    assert.equal(typeof body.result.message_id, 'number');
    const stored = await (await fetch(`${base}/__messages?chat_id=42`)).json();
    assert.equal(stored.length, 1);
    assert.equal(stored[0].text, 'xin chào');
    assert.equal(stored[0].token, '123:ABC');
  } finally { await close(); }
});

test('GET /__messages filters by chat_id and returns everything without a filter', async () => {
  const { base, close } = await start();
  try {
    await sendJson(base, 't', { chat_id: 1, text: 'a' });
    await sendJson(base, 't', { chat_id: 2, text: 'b' });
    await sendJson(base, 't', { chat_id: '1', text: 'c' });
    assert.deepEqual((await (await fetch(`${base}/__messages?chat_id=1`)).json()).map((m) => m.text), ['a', 'c']);
    assert.equal((await (await fetch(`${base}/__messages`)).json()).length, 3);
    assert.equal((await (await fetch(`${base}/__messages?chat_id=999`)).json()).length, 0);
  } finally { await close(); }
});

test('accepts form-encoded bodies, as the real Bot API does', async () => {
  const { base, close } = await start();
  try {
    await fetch(`${base}/botT/sendMessage`, {
      method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ chat_id: '7', text: 'form msg' }),
    });
    const stored = await (await fetch(`${base}/__messages?chat_id=7`)).json();
    assert.equal(stored[0].text, 'form msg');
  } finally { await close(); }
});

test('missing chat_id or text gives a 400 Bot-API style error and stores nothing', async () => {
  const { base, close } = await start();
  try {
    const res = await sendJson(base, 't', { text: 'no chat' });
    assert.equal(res.status, 400);
    assert.equal((await res.json()).ok, false);
    assert.equal((await (await fetch(`${base}/__messages`)).json()).length, 0);
  } finally { await close(); }
});

test('DELETE /__messages resets the store; health is 200; unknown paths 404', async () => {
  const { base, close } = await start();
  try {
    await sendJson(base, 't', { chat_id: 1, text: 'a' });
    await fetch(`${base}/__messages`, { method: 'DELETE' });
    assert.equal((await (await fetch(`${base}/__messages`)).json()).length, 0);
    assert.equal((await fetch(`${base}/__health`)).status, 200);
    assert.equal((await fetch(`${base}/zzz`)).status, 404);
  } finally { await close(); }
});
