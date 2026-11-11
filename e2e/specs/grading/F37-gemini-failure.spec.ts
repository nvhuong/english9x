// F37 — Gemini lỗi (spec §16.2): "fake-gemini trả lỗi liên tục → AI_FAILED → quản trị được báo → chuyển giáo viên".
//
// The fake Gemini answers HTTP 500 to every call. The `ai_grade` job fails and is retried after the delays of e9x.grading.ai.retry-delays (the E2E stack sets them to
// 2s,2s,2s instead of 30s,2m,10m), so Gemini is asked four times in all; then the job gives up: the attempt is AI_FAILED, no score is written (the learner still sees
// "Đang chấm"), and every SYS_ADMIN gets "AI grading failed" in the inbox. "Chuyển giáo viên" (the administrator moves the attempt to the teachers' queue) is P4.3: see
// e2e/partial-features.json.
import { test, expect } from '../../fixtures';
import { staffed } from '../../fixtures/content';
import { ensureGradingSkill, publishedIeltsTest, sitWritingAndSpeaking } from '../../fixtures/ai-grading';
import { api } from '../../fixtures/org';
import { poll } from '../../helpers/env';

test.describe('F37 Gemini failure', () => {
  test('F37 Gemini failing on every call ends in AI_FAILED after the retries, no score is written, and the administrator is told', async ({ request, uniqueEmail, org, seed, gemini }) => {
    // grace of two minutes + four calls two seconds apart; the fake-Gemini lock is held for the test only
    test.setTimeout(480_000);
    const s = await staffed(['IELTS'], 'f37', { request, uniqueEmail, apiLogin: org.apiLogin });
    await ensureGradingSkill(s, 'IELTS_W_TASK2');
    await ensureGradingSkill(s, 'IELTS_S_PART1');
    const testVersionId = await publishedIeltsTest(s, `F37 ${Math.random().toString(36).slice(2, 8)}`);
    const admin = await seed.sysAdmin({ email: uniqueEmail('f37-admin') });
    const adminToken = (await org.apiLogin(admin)).token;
    const learner = await seed.learnerWithPlan({ courses: 1 });
    const token = (await org.apiLogin({ email: learner.email, password: learner.password })).token;
    await gemini.reset();
    await gemini.setScenario('error500');
    const attemptId = await sitWritingAndSpeaking(request, token, testVersionId, 'F37 essay: this will never be graded.');

    const failed = await poll('AI_FAILED', async () => {
      const r = await seed.grading.ai(attemptId);
      return r.state ? r : null;
    }, { timeoutMs: 360_000, intervalMs: 2_000 });
    expect(failed.state).toBe('AI_FAILED');
    expect(failed.evaluations).toEqual([]);
    expect((await gemini.requests()).length, 'the first run and three retries').toBe(4);

    const scores = await seed.grading.scores(attemptId);
    expect(scores.scores.filter((x) => x.skill === 'WRITING' || x.skill === 'SPEAKING'), 'no score for the learner: still "Đang chấm"').toEqual([]);

    const inbox = await poll('the administrator\'s notification', async () => {
      const r = await api(adminToken, 'GET', '/api/me/notifications?limit=50');
      expect(r.status, r.text).toBe(200);
      return r.body.items.find((n: any) => n.type === 'AI_GRADING_FAILED' && n.data?.attemptId === attemptId);
    }, { timeoutMs: 30_000 });
    expect(inbox.read).toBe(false);
  });
});
