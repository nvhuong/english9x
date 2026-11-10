// Harness self-check (not a feature): every helper is exercised against the REAL fake containers, so a broken
// helper fails here instead of inside F01-F58. Names starting with "_" are never counted by the coverage gate.
import { acquireGeminiLock } from '../helpers/gemini';
import { env, json, request } from '../helpers/env';
import { NotImplementedError } from '../fixtures/notImplemented';
import { paidOrder } from '../fixtures/billing';
import { publishedQuestion } from '../fixtures/content';
import { test, expect } from '../fixtures';

const generate = (model = 'gemini-2.5-flash') =>
  json<{ candidates: Array<{ content: { parts: Array<{ text: string }> } }> }>(`${env.geminiURL}/v1beta/models/${model}:generateContent`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ contents: [{ parts: [{ text: 'hello' }] }] }),
  });

test.describe('mailpit helper', () => {
  test('sent mail is found by address with subject, text and links', async ({ mailpit, uniqueEmail }) => {
    const to = uniqueEmail('round-trip');
    await mailpit.sendTestEmail(to, 'Xác thực email', 'Bấm vào https://localhost/verify?token=abc123. Cảm ơn.');
    const mail = await mailpit.lastEmailTo(to);
    expect(mail.Subject).toBe('Xác thực email');
    expect(mail.To.map((t) => t.Address)).toEqual([to]);
    expect(mailpit.linksIn(mail)).toEqual(['https://localhost/verify?token=abc123']);
    expect(await mailpit.hasNoEmailTo(to)).toBe(false);
  });

  test('mail to one address is never returned for another (parallel isolation)', async ({ mailpit, uniqueEmail }) => {
    const a = uniqueEmail('iso');
    const b = uniqueEmail('iso');
    expect(a).not.toBe(b);
    await mailpit.sendTestEmail(a, 'for A', 'a');
    await mailpit.sendTestEmail(b, 'for B', 'b');
    expect((await mailpit.lastEmailTo(a)).Subject).toBe('for A');
    expect((await mailpit.lastEmailTo(b)).Subject).toBe('for B');
  });

  test('polls for a message that arrives later, and times out with a clear error when none comes', async ({ mailpit, uniqueEmail }) => {
    const later = uniqueEmail('later');
    setTimeout(() => void mailpit.sendTestEmail(later, 'late', 'x'), 700);
    const started = Date.now();
    expect((await mailpit.lastEmailTo(later, { timeoutMs: 10_000 })).Subject).toBe('late');
    expect(Date.now() - started).toBeGreaterThanOrEqual(500); // it really waited, it did not find it instantly

    const never = uniqueEmail('never');
    await expect(mailpit.lastEmailTo(never, { timeoutMs: 600 })).rejects.toThrow(/Timed out after 600 ms waiting for an email to .* in Mailpit/);
    expect(await mailpit.hasNoEmailTo(never)).toBe(true);
  });

  test('subject filter picks the right message', async ({ mailpit, uniqueEmail }) => {
    const to = uniqueEmail('subj');
    await mailpit.sendTestEmail(to, 'Chào mừng', 'w');
    await mailpit.sendTestEmail(to, 'Đặt lại mật khẩu', 'r');
    expect((await mailpit.lastEmailTo(to, { subjectIncludes: 'Chào' })).Subject).toBe('Chào mừng');
    await mailpit.deleteEmailsTo(to);
    expect(await mailpit.hasNoEmailTo(to)).toBe(true);
  });
});

test.describe('telegram helper', () => {
  test('messages are kept per chat, in order', async ({ telegram }) => {
    const a = telegram.uniqueChatId();
    const b = telegram.uniqueChatId();
    expect(a).not.toBe(b);
    await telegram.sendAsBot(a, 'một');
    await telegram.sendAsBot(b, 'khác');
    await telegram.sendAsBot(a, 'hai');
    expect((await telegram.messagesFor(a)).map((m) => m.text)).toEqual(['một', 'hai']);
    expect((await telegram.messagesFor(b)).map((m) => m.text)).toEqual(['khác']);
    expect(await telegram.messagesFor(telegram.uniqueChatId())).toEqual([]);
  });

  test('waitForMessages polls until the count is reached, and times out clearly', async ({ telegram }) => {
    const chat = telegram.uniqueChatId();
    setTimeout(() => void telegram.sendAsBot(chat, 'one'), 300);
    setTimeout(() => void telegram.sendAsBot(chat, 'two'), 700);
    const got = await telegram.waitForMessages(chat, { count: 2, timeoutMs: 10_000 });
    expect(got.map((m) => m.text)).toEqual(['one', 'two']);
    await expect(telegram.waitForMessages(telegram.uniqueChatId(), { timeoutMs: 500 })).rejects.toThrow(/Timed out after 500 ms waiting for 1 Telegram message/);
  });
});

test.describe('gemini helper', () => {
  test('scenarios change the fake\'s answer and requests are recorded', async ({ gemini }) => {
    expect(await gemini.getScenario()).toBe('ok');
    const ok = await generate();
    expect(JSON.parse(ok.candidates[0].content.parts[0].text)).toHaveProperty('overallBand');

    await gemini.setScenario('error500');
    await expect(generate()).rejects.toThrow(/-> 500/);

    await gemini.setScenario('badSchema');
    const bad = await generate();
    expect(JSON.parse(bad.candidates[0].content.parts[0].text)).toEqual({ unexpected: true });

    const recorded = await gemini.requests();
    expect(recorded.map((r) => r.scenario)).toEqual(['ok', 'error500', 'badSchema']);
  });

  test.describe.serial('state is reset between tests', () => {
    test('first test leaves a bad scenario and a recorded request behind', async ({ gemini }) => {
      await gemini.setScenario('error500');
      await expect(generate()).rejects.toThrow(/-> 500/);
      expect(await gemini.requests()).toHaveLength(1);
    });

    test('the next test starts from ok with no requests', async ({ gemini }) => {
      expect(await gemini.getScenario()).toBe('ok');
      expect(await gemini.requests()).toEqual([]);
    });
  });

  test('the cross-process lock is exclusive and released', async () => {
    const release = await acquireGeminiLock();
    let second = false;
    const waiting = acquireGeminiLock(10_000).then(async (r) => { second = true; await r(); });
    await new Promise((r) => setTimeout(r, 400));
    expect(second).toBe(false); // still blocked while the first holder has the lock
    await release();
    await waiting;
    expect(second).toBe(true);
  });
});

test.describe('seed API and not-yet-built fixtures', () => {
  test('seed.ping reaches the profile-gated endpoint and writes as the table owner', async ({ seed }) => {
    expect(await seed.ping()).toEqual({ status: 'ok', seedLogin: 'e9x_owner' });
  });

  test('the seed API refuses a request without the shared secret', async () => {
    for (const headers of [{}, { 'x-e2e-seed-secret': 'wrong' }] as Array<Record<string, string>>) {
      const r = await request(`${env.baseURL}/api/test/seed/ping`, { method: 'POST', headers });
      expect(r.status).toBe(403);
    }
  });

  test('seed.verifiedLearner makes a person who can sign in; verified:false leaves the mailbox unconfirmed', async ({ seed, uniqueEmail }) => {
    const ok = await seed.verifiedLearner({ email: uniqueEmail('seed-ok') });
    expect(ok.verified).toBe(true);
    expect(await seed.userInfo(ok.email)).toMatchObject({ count: 1, hasPassword: true, emailVerified: true, identities: 0 });
    const login = await json<{ accessToken: string }>(`${env.baseURL}/api/auth/login`, {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email: ok.email, password: ok.password }),
    });
    expect(login.accessToken.split('.')).toHaveLength(3);
    expect(await seed.requireVerified(ok.userId)).toBe(200);

    const pending = await seed.verifiedLearner({ email: uniqueEmail('seed-pending'), verified: false });
    expect(await seed.userInfo(pending.email)).toMatchObject({ emailVerified: false });
    expect(await seed.requireVerified(pending.userId)).toBe(403);
  });

  test('seed functions of unbuilt modules throw a clear error instead of faking data', async ({ seed }) => {
    const calls: Array<[string, () => unknown, RegExp]> = [
      ['seed.publishedToeicTest', () => seed.publishedToeicTest(), /not implemented until P2\.6/],
      ['seed.publishedIeltsTest', () => seed.publishedIeltsTest({ variant: 'AC' }), /not implemented until P2\.6/],
      ['seed.center', () => seed.center({ teachers: 1, learners: 2 }), /not implemented until P6\.1/],
      ['content.publishedQuestion', () => publishedQuestion('mcq'), /not implemented until P2\.3/],
      ['billing.paidOrder', () => paidOrder('x', 'y'), /not implemented until P5\.2/],
    ];
    for (const [name, call, message] of calls) {
      let error: unknown;
      try { await call(); } catch (e) { error = e; }
      expect(error, `${name} must throw`).toBeInstanceOf(NotImplementedError);
      expect((error as Error).message, name).toMatch(message);
    }
  });
});
