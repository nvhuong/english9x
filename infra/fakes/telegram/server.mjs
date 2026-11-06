// Fake Telegram Bot API for dev/E2E. Zero dependencies.
//   POST /bot{token}/sendMessage  (JSON or form-encoded: chat_id, text) -> stores the message
//   GET /__messages[?chat_id=]    DELETE /__messages    GET /__health
import http from 'node:http';
import { pathToFileURL } from 'node:url';

async function readBody(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const raw = Buffer.concat(chunks).toString('utf8');
  if (!raw) return {};
  const type = req.headers['content-type'] ?? '';
  try {
    if (type.includes('application/json')) return JSON.parse(raw);
    return Object.fromEntries(new URLSearchParams(raw));
  } catch { return undefined; }
}

export function createFakeTelegram() {
  let messages = [];
  let nextId = 1;
  const send = (res, status, body) => {
    res.writeHead(status, { 'content-type': 'application/json' });
    res.end(JSON.stringify(body));
  };
  return http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://fake');
    if (url.pathname === '/__health') return send(res, 200, { ok: true });
    if (url.pathname === '/__messages') {
      if (req.method === 'GET') {
        const chat = url.searchParams.get('chat_id');
        return send(res, 200, chat === null ? messages : messages.filter((m) => String(m.chat_id) === chat));
      }
      if (req.method === 'DELETE') { messages = []; return send(res, 200, { ok: true }); }
    }
    const m = /^\/bot([^/]+)\/sendMessage$/.exec(url.pathname);
    if (m && req.method === 'POST') {
      const body = await readBody(req);
      if (!body || body.chat_id === undefined || body.chat_id === '' || typeof body.text !== 'string' || body.text === '') {
        return send(res, 400, { ok: false, error_code: 400, description: 'Bad Request: chat_id and text are required' });
      }
      const stored = {
        message_id: nextId++,
        token: decodeURIComponent(m[1]),
        chat_id: body.chat_id,
        text: body.text,
        date: Math.floor(Date.now() / 1000),
      };
      messages.push(stored);
      return send(res, 200, {
        ok: true,
        result: { message_id: stored.message_id, chat: { id: Number(body.chat_id) || body.chat_id }, date: stored.date, text: stored.text },
      });
    }
    return send(res, 404, { ok: false, error_code: 404, description: 'Not Found' });
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number(process.env.PORT ?? 8080);
  createFakeTelegram().listen(port, '0.0.0.0', () => console.log(`fake-telegram listening on ${port}`));
}
