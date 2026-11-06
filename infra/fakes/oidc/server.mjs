// Fake OpenID Connect provider ("Google") for E2E. Zero dependencies (node:http, node:crypto). E2E overlay only.
//   GET  /authorize     the "account chooser": a form (email, name, email-verified, optional subject). Query: client_id,
//                       redirect_uri, state, nonce, code_challenge(+_method), scope, response_type=code
//   POST /authorize     the form: validates the client and redirect URI, then 302 -> redirect_uri?code=..&state=..
//   POST /token         authorization_code grant: client auth (Basic or form), PKCE S256, single-use code -> id_token (RS256)
//   GET  /jwks          the public signing key         GET /__health
// The subject of an account is derived from its email (same email -> same account each time), unless one is typed in.
// Configuration (environment): FAKE_OIDC_ISSUER (the URL the BROWSER uses; it is the `iss` of every token),
// FAKE_OIDC_CLIENT_ID, FAKE_OIDC_CLIENT_SECRET, FAKE_OIDC_REDIRECT_URI (the only redirect URI it will send people to).
import crypto from 'node:crypto';
import http from 'node:http';
import { pathToFileURL } from 'node:url';

const b64url = (buf) => Buffer.from(buf).toString('base64url');
const sha256 = (s) => crypto.createHash('sha256').update(s).digest();
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

async function readForm(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  return Object.fromEntries(new URLSearchParams(Buffer.concat(chunks).toString('utf8')));
}

export function createFakeOidc({ issuer, clientId, clientSecret, redirectUri }) {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  const jwk = { ...publicKey.export({ format: 'jwk' }), kid: 'fake-oidc-1', use: 'sig', alg: 'RS256' };
  const codes = new Map(); // code -> { claims, nonce, challenge }

  const sign = (claims) => {
    const header = b64url(JSON.stringify({ alg: 'RS256', kid: jwk.kid, typ: 'JWT' }));
    const body = b64url(JSON.stringify(claims));
    const sig = crypto.sign('RSA-SHA256', Buffer.from(`${header}.${body}`), privateKey);
    return `${header}.${body}.${b64url(sig)}`;
  };
  const send = (res, status, body, type = 'application/json') => {
    res.writeHead(status, { 'content-type': type, 'cache-control': 'no-store' });
    res.end(typeof body === 'string' ? body : JSON.stringify(body));
  };
  const badRequest = (res, msg) => send(res, 400, { error: 'invalid_request', error_description: msg });

  const chooser = (q) => `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Fake Google sign-in</title></head>
<body><main><h1>Fake Google</h1><p>Choose the account to sign in with.</p>
<form method="post" action="/authorize">
${['client_id', 'redirect_uri', 'state', 'nonce', 'code_challenge', 'code_challenge_method', 'scope', 'response_type']
    .map((k) => `<input type="hidden" name="${k}" value="${esc(q.get(k))}">`).join('\n')}
<p><label>Google account email <input name="email" type="email" required></label></p>
<p><label>Name <input name="name" value="Fake Google User"></label></p>
<p><label><input name="email_verified" type="checkbox" value="true" checked> Google has verified this email</label></p>
<p><label>Subject (optional) <input name="sub"></label></p>
<p><button type="submit">Continue</button></p>
</form></main></body></html>`;

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://fake');
    if (url.pathname === '/__health') return send(res, 200, { ok: true });
    if (url.pathname === '/jwks' && req.method === 'GET') return send(res, 200, { keys: [jwk] });

    if (url.pathname === '/authorize' && req.method === 'GET') {
      const q = url.searchParams;
      if (q.get('client_id') !== clientId) return badRequest(res, 'unknown client_id');
      if (q.get('redirect_uri') !== redirectUri) return badRequest(res, 'redirect_uri is not registered');
      if (q.get('response_type') !== 'code') return badRequest(res, 'response_type must be code');
      return send(res, 200, chooser(q), 'text/html; charset=utf-8');
    }

    if (url.pathname === '/authorize' && req.method === 'POST') {
      const f = await readForm(req);
      if (f.client_id !== clientId) return badRequest(res, 'unknown client_id');
      if (f.redirect_uri !== redirectUri) return badRequest(res, 'redirect_uri is not registered');
      const email = String(f.email ?? '').trim().toLowerCase();
      if (!email) return badRequest(res, 'email is required');
      const code = crypto.randomUUID();
      codes.set(code, {
        challenge: f.code_challenge || undefined,
        nonce: f.nonce || undefined,
        claims: {
          sub: f.sub?.trim() || `fake-${sha256(email).toString('hex').slice(0, 24)}`,
          email,
          email_verified: f.email_verified === 'true',
          name: f.name || 'Fake Google User',
          locale: 'vi',
        },
      });
      const back = new URL(redirectUri);
      back.searchParams.set('code', code);
      if (f.state) back.searchParams.set('state', f.state);
      res.writeHead(302, { location: back.toString(), 'cache-control': 'no-store' });
      return res.end();
    }

    if (url.pathname === '/token' && req.method === 'POST') {
      const f = await readForm(req);
      let id = f.client_id;
      let secret = f.client_secret;
      const basic = /^Basic (.+)$/i.exec(req.headers.authorization ?? '');
      if (basic) {
        const [i, ...s] = Buffer.from(basic[1], 'base64').toString('utf8').split(':');
        id = decodeURIComponent(i);
        secret = decodeURIComponent(s.join(':'));
      }
      if (id !== clientId || secret !== clientSecret) return send(res, 401, { error: 'invalid_client' });
      if (f.grant_type !== 'authorization_code') return send(res, 400, { error: 'unsupported_grant_type' });
      const issued = codes.get(f.code);
      codes.delete(f.code); // single use, also when the rest fails
      if (!issued) return send(res, 400, { error: 'invalid_grant', error_description: 'unknown or used code' });
      if (f.redirect_uri !== redirectUri) return send(res, 400, { error: 'invalid_grant', error_description: 'redirect_uri mismatch' });
      if (issued.challenge) {
        const ok = f.code_verifier && b64url(sha256(f.code_verifier)) === issued.challenge;
        if (!ok) return send(res, 400, { error: 'invalid_grant', error_description: 'PKCE verification failed' });
      }
      const now = Math.floor(Date.now() / 1000);
      const idToken = sign({
        iss: issuer, aud: clientId, iat: now, exp: now + 300,
        ...(issued.nonce ? { nonce: issued.nonce } : {}), ...issued.claims,
      });
      return send(res, 200, { access_token: `fake-access-${crypto.randomUUID()}`, token_type: 'Bearer', expires_in: 300, id_token: idToken });
    }
    return send(res, 404, { error: 'not_found' });
  });
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const need = (k) => process.env[k] || (() => { throw new Error(`${k} is required`); })();
  const port = Number(process.env.PORT ?? 8080);
  createFakeOidc({
    issuer: need('FAKE_OIDC_ISSUER'),
    clientId: need('FAKE_OIDC_CLIENT_ID'),
    clientSecret: need('FAKE_OIDC_CLIENT_SECRET'),
    redirectUri: need('FAKE_OIDC_REDIRECT_URI'),
  }).listen(port, '0.0.0.0', () => console.log(`fake-oidc listening on ${port}`));
}
