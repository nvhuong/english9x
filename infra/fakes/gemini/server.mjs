// Fake Gemini API for dev/E2E. Zero dependencies.
//   POST /v1beta/models/{model}:generateContent  -> response chosen by the current scenario
//   POST /__scenario {"scenario":"ok"|"error500"|"badSchema"}   GET /__scenario
//   GET /__requests (recorded calls)  DELETE /__requests    GET /__health
import http from 'node:http';
import { pathToFileURL } from 'node:url';

export const SCENARIOS = ['ok', 'error500', 'badSchema'];

const OK_PAYLOAD = {
  overallBand: 6.5,
  criteria: [
    { name: 'Task Achievement', band: 6.5, comment: 'Addresses all parts of the task.' },
    { name: 'Coherence and Cohesion', band: 6.5, comment: 'Clear progression.' },
    { name: 'Lexical Resource', band: 6.0, comment: 'Adequate range.' },
    { name: 'Grammatical Range and Accuracy', band: 6.5, comment: 'Mix of structures.' },
  ],
  feedback: 'Fake feedback from fake-gemini.',
};
const BAD_PAYLOAD = { unexpected: true };

function candidate(payload) {
  return {
    candidates: [{ content: { role: 'model', parts: [{ text: JSON.stringify(payload) }] }, finishReason: 'STOP', index: 0 }],
    usageMetadata: { promptTokenCount: 120, candidatesTokenCount: 80, totalTokenCount: 200 },
    modelVersion: 'fake-gemini',
  };
}

async function readJson(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  const raw = Buffer.concat(chunks).toString('utf8');
  if (!raw) return {};
  try { return JSON.parse(raw); } catch { return undefined; }
}

export function createFakeGemini() {
  let scenario = 'ok';
  let requests = [];
  const send = (res, status, body) => {
    res.writeHead(status, { 'content-type': 'application/json' });
    res.end(JSON.stringify(body));
  };
  return http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://fake');
    const path = url.pathname;
    if (path === '/__health') return send(res, 200, { ok: true });
    if (path === '/__scenario') {
      if (req.method === 'GET') return send(res, 200, { scenario });
      if (req.method === 'POST') {
        const body = await readJson(req);
        if (!body || !SCENARIOS.includes(body.scenario)) {
          return send(res, 400, { error: `scenario must be one of ${SCENARIOS.join(', ')}` });
        }
        scenario = body.scenario;
        return send(res, 200, { scenario });
      }
    }
    if (path === '/__requests') {
      if (req.method === 'GET') return send(res, 200, requests);
      if (req.method === 'DELETE') { requests = []; return send(res, 200, { ok: true }); }
    }
    const m = /^\/v1beta\/models\/([^/:]+):generateContent$/.exec(path);
    if (m && req.method === 'POST') {
      const body = await readJson(req);
      requests.push({ model: m[1], body, scenario });
      if (scenario === 'error500') {
        return send(res, 500, { error: { code: 500, message: 'Internal error (fake-gemini)', status: 'INTERNAL' } });
      }
      return send(res, 200, candidate(scenario === 'badSchema' ? BAD_PAYLOAD : OK_PAYLOAD));
    }
    return send(res, 404, { error: 'not found' });
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const port = Number(process.env.PORT ?? 8080);
  createFakeGemini().listen(port, '0.0.0.0', () => console.log(`fake-gemini listening on ${port}`));
}
