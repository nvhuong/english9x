// k6: 50 learners sit an exam at the same time over the real API (login, start an attempt, save answers, finish a section, submit).
//   k6 run -e BASE_URL=https://staging.example.com -e TEST_VERSION_ID=<published test version> \
//          -e USER_EMAIL_PATTERN='load{n}@example.com' -e USER_PASSWORD=<password> infra/k6/exam-50.js
// {n} in the pattern is replaced by 1..50. The 50 accounts must exist beforehand, with an entitlement to the test (README, Load test); a learner who already has
// an attempt IN_PROGRESS on it is resumed (the API answers 409 with that attempt), so the script may be run again.
// Thresholds: failed requests under 1%, p95 of the save endpoint under 800 ms. Needs a stack WITHOUT the login rate limit biting (see README).
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'https://localhost').replace(/\/$/, '');
const TEST_VERSION_ID = __ENV.TEST_VERSION_ID;
const PATTERN = __ENV.USER_EMAIL_PATTERN || 'load{n}@example.com';
const PASSWORD = __ENV.USER_PASSWORD;
const USERS = Number(__ENV.USERS || 50);
const SAVES_PER_SECTION = Number(__ENV.SAVES_PER_SECTION || 10);

const saveDuration = new Trend('exam_save_duration', true);

export const options = {
  scenarios: {
    learners: { executor: 'per-vu-iterations', vus: USERS, iterations: 1, maxDuration: '15m' },
  },
  insecureSkipTLSVerify: __ENV.INSECURE_TLS === '1', // a staging Caddy with an internal certificate
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{endpoint:save}': ['p(95)<800'],
    exam_save_duration: ['p(95)<800'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  if (!TEST_VERSION_ID || !PASSWORD) throw new Error('TEST_VERSION_ID and USER_PASSWORD are required (see the header of this file)');
}

function post(path, body, token, endpoint) {
  return http.post(`${BASE_URL}${path}`, body === null ? null : JSON.stringify(body), { headers: headers(token), tags: { endpoint } });
}
function headers(token) {
  const h = { 'Content-Type': 'application/json' };
  if (token) h.Authorization = `Bearer ${token}`;
  return h;
}

function questionIds(attempt) {
  const ids = [];
  const section = attempt && attempt.currentSection;
  if (!section) return ids;
  for (const g of section.groups || []) for (const q of g.questions || []) ids.push(q.questionId);
  return ids;
}

function saveAnswers(token, attempt, count) {
  const ids = questionIds(attempt);
  for (let i = 0; i < Math.min(count, ids.length); i++) {
    // answeredAt on the server's clock, as the exam room sends it: serverNow of the last view, moving forward
    const answeredAt = new Date(Date.parse(attempt.serverNow) + 1000 * (i + 1)).toISOString();
    const res = http.put(`${BASE_URL}/api/exam/attempts/${attempt.attemptId}/responses/${ids[i]}`,
      JSON.stringify({ answer: 'A', answeredAt }), { headers: headers(token), tags: { endpoint: 'save' } });
    saveDuration.add(res.timings.duration);
    check(res, { 'save: 204': (r) => r.status === 204 });
    sleep(1 + Math.random()); // a learner thinks between answers
  }
}

export default function () {
  const n = __VU; // 1..USERS
  const email = PATTERN.replace('{n}', String(n));
  let token;

  group('login', () => {
    const res = post('/api/auth/login', { email, password: PASSWORD }, null, 'login');
    check(res, { 'login: 200 with a token': (r) => r.status === 200 && !!r.json('accessToken') });
    token = res.json('accessToken');
  });
  if (!token) return;

  let attempt;
  group('start', () => {
    const res = post('/api/exam/attempts', { testVersionId: TEST_VERSION_ID }, token, 'start');
    check(res, { 'start: 201 or 409 (already running)': (r) => r.status === 201 || r.status === 409 });
    attempt = res.json();
  });
  if (!attempt || !attempt.attemptId) return;

  const sections = (attempt.sections || []).length || 1;
  for (let s = 0; s < sections; s++) {
    group('save answers', () => saveAnswers(token, attempt, SAVES_PER_SECTION));
    const section = attempt.currentSection;
    if (!section || s === sections - 1) break;
    group('finish section', () => {
      const res = post(`/api/exam/attempts/${attempt.attemptId}/sections/${section.sectionId}/finish`, null, token, 'finish');
      check(res, { 'finish: 200': (r) => r.status === 200 });
      attempt = res.json();
    });
    if (!attempt || !attempt.attemptId) return;
  }

  group('submit', () => {
    const res = post(`/api/exam/attempts/${attempt.attemptId}/submit`, null, token, 'submit');
    check(res, { 'submit: 200 SUBMITTED': (r) => r.status === 200 && r.json('status') === 'SUBMITTED' });
  });
}
