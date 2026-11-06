import crypto from 'node:crypto';
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createFakeOidc } from './server.mjs';

const CFG = { issuer: 'http://browser.example:9000', clientId: 'cid', clientSecret: 'sec', redirectUri: 'https://app.example/cb' };
const b64url = (b) => Buffer.from(b).toString('base64url');

async function start() {
  const server = createFakeOidc(CFG);
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  return { base, close: () => new Promise((r) => server.close(r)) };
}
const pkce = () => {
  const verifier = b64url(crypto.randomBytes(32));
  return { verifier, challenge: b64url(crypto.createHash('sha256').update(verifier).digest()) };
};
const authQuery = (extra = {}) =>
  new URLSearchParams({ client_id: 'cid', redirect_uri: CFG.redirectUri, response_type: 'code', state: 'st-1', nonce: 'no-1', scope: 'openid email', ...extra });

async function approve(base, form) {
  return fetch(`${base}/authorize`, {
    method: 'POST', redirect: 'manual', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(form),
  });
}
async function token(base, code, verifier, auth = 'Basic ' + Buffer.from('cid:sec').toString('base64')) {
  return fetch(`${base}/token`, {
    method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded', authorization: auth },
    body: new URLSearchParams({ grant_type: 'authorization_code', code, redirect_uri: CFG.redirectUri, ...(verifier ? { code_verifier: verifier } : {}) }),
  });
}
function decode(jwt) {
  const [h, b, s] = jwt.split('.');
  return { header: JSON.parse(Buffer.from(h, 'base64url')), claims: JSON.parse(Buffer.from(b, 'base64url')), signed: `${h}.${b}`, sig: Buffer.from(s, 'base64url') };
}
async function fullFlow(base, { email = 'an@example.test', verified = true, sub, nonce = 'no-1' } = {}) {
  const { verifier, challenge } = pkce();
  const q = authQuery({ code_challenge: challenge, code_challenge_method: 'S256', nonce });
  const page = await fetch(`${base}/authorize?${q}`);
  assert.equal(page.status, 200);
  const res = await approve(base, {
    ...Object.fromEntries(q), email, name: 'An', ...(verified ? { email_verified: 'true' } : {}), ...(sub ? { sub } : {}),
  });
  assert.equal(res.status, 302);
  const back = new URL(res.headers.get('location'));
  return { back, verifier, code: back.searchParams.get('code') };
}

test('the chooser page is an HTML form carrying the OAuth parameters through', async () => {
  const { base, close } = await start();
  try {
    const res = await fetch(`${base}/authorize?${authQuery()}`);
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type'), /text\/html/);
    const html = await res.text();
    assert.match(html, /name="email"/);
    assert.match(html, /name="state" value="st-1"/);
    assert.match(html, /name="email_verified"/);
  } finally { await close(); }
});

test('a wrong client or an unregistered redirect URI never reaches the chooser or issues a code', async () => {
  const { base, close } = await start();
  try {
    assert.equal((await fetch(`${base}/authorize?${authQuery({ client_id: 'other' })}`)).status, 400);
    assert.equal((await fetch(`${base}/authorize?${authQuery({ redirect_uri: 'https://evil.example/cb' })}`)).status, 400);
    const res = await approve(base, { ...Object.fromEntries(authQuery({ redirect_uri: 'https://evil.example/cb' })), email: 'a@b.test' });
    assert.equal(res.status, 400);
    assert.equal(res.headers.get('location'), null);
  } finally { await close(); }
});

test('the full dance: redirect with code and state, then a signed ID token with the nonce, email and audience', async () => {
  const { base, close } = await start();
  try {
    const { back, verifier, code } = await fullFlow(base, { email: 'An@Example.test' });
    assert.equal(back.origin + back.pathname, CFG.redirectUri);
    assert.equal(back.searchParams.get('state'), 'st-1');
    const res = await token(base, code, verifier);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.token_type, 'Bearer');
    const { header, claims, signed, sig } = decode(body.id_token);
    assert.equal(header.alg, 'RS256');
    assert.equal(claims.iss, CFG.issuer, 'iss is the browser-facing URL, not the one the app container reaches');
    assert.equal(claims.aud, 'cid');
    assert.equal(claims.nonce, 'no-1');
    assert.equal(claims.email, 'an@example.test');
    assert.equal(claims.email_verified, true);
    assert.ok(claims.exp > claims.iat);
    // the signature verifies with the published key
    const jwks = await (await fetch(`${base}/jwks`)).json();
    assert.equal(jwks.keys[0].kid, header.kid);
    const key = crypto.createPublicKey({ key: jwks.keys[0], format: 'jwk' });
    assert.equal(crypto.verify('RSA-SHA256', Buffer.from(signed), key, sig), true);
    assert.equal(crypto.verify('RSA-SHA256', Buffer.from(signed + 'x'), key, sig), false);
  } finally { await close(); }
});

test('the same email is the same Google account every time; a typed subject overrides it', async () => {
  const { base, close } = await start();
  try {
    const subOf = async (opts) => {
      const f = await fullFlow(base, opts);
      return decode((await (await token(base, f.code, f.verifier)).json()).id_token).claims.sub;
    };
    const a1 = await subOf({ email: 'same@example.test' });
    const a2 = await subOf({ email: 'same@example.test' });
    const b = await subOf({ email: 'other@example.test' });
    const c = await subOf({ email: 'same@example.test', sub: 'custom-subject' });
    assert.equal(a1, a2);
    assert.notEqual(a1, b);
    assert.equal(c, 'custom-subject');
  } finally { await close(); }
});

test('an unchecked "verified" box becomes email_verified:false', async () => {
  const { base, close } = await start();
  try {
    const f = await fullFlow(base, { verified: false });
    const claims = decode((await (await token(base, f.code, f.verifier)).json()).id_token).claims;
    assert.equal(claims.email_verified, false);
  } finally { await close(); }
});

test('PKCE: a wrong or missing verifier is refused', async () => {
  const { base, close } = await start();
  try {
    let f = await fullFlow(base);
    assert.equal((await token(base, f.code, 'not-the-verifier')).status, 400);
    f = await fullFlow(base);
    assert.equal((await token(base, f.code, undefined)).status, 400);
  } finally { await close(); }
});

test('a code works once, and a bad client secret is refused (and burns nothing it should not)', async () => {
  const { base, close } = await start();
  try {
    const f = await fullFlow(base);
    assert.equal((await token(base, f.code, f.verifier, 'Basic ' + Buffer.from('cid:wrong').toString('base64'))).status, 401);
    assert.equal((await token(base, f.code, f.verifier)).status, 200);
    assert.equal((await token(base, f.code, f.verifier)).status, 400, 'second use');
    assert.equal((await token(base, 'unknown', f.verifier)).status, 400);
  } finally { await close(); }
});

test('health and unknown paths', async () => {
  const { base, close } = await start();
  try {
    assert.equal((await fetch(`${base}/__health`)).status, 200);
    assert.equal((await fetch(`${base}/nope`)).status, 404);
  } finally { await close(); }
});
