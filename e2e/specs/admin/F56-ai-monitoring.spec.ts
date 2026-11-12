// F56 — Giám sát AI (spec §16.2): "Trang quản trị hiện job lỗi, chi phí theo ngày, độ lệch AI–GV".
//
// Through the real stack, in two runs of the AI grading: (1) the fake Gemini fails every call, so an `ai_grade` job ends FAILED; (2) it answers (band 6.5, priced tokens) in
// mode BOTH, and a teacher scores the attempt (7.5 and 7), which records the AI-vs-teacher deviation. The SYS_ADMIN opens the monitor page and sees the failed job, the cost of
// today per day and per person, the alert (the E2E stack sets e9x.grading.ai.daily-cost-alert to 5 VND, a response costs about 6), and the deviation per question type.
// Nobody else can read it.
import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { staffed } from '../../fixtures/content';
import { ensureGradingSkill, publishedIeltsTest, sitWritingAndSpeaking } from '../../fixtures/ai-grading';
import { api } from '../../fixtures/org';
import { poll } from '../../helpers/env';

async function axeClean(page: Page) {
  const r = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  expect(r.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

test.describe('F56 AI monitoring', () => {
  test('F56 the SYS_ADMIN sees the failed job, the cost per day and person with the alert, and the AI-vs-teacher deviation; nobody else can', async ({ page, browser, request, uniqueEmail, org, seed, auth, gemini }) => {
    // two runs of AI grading, each waits out the two-minute late-sync grace; the fake-Gemini lock is held for the test
    test.setTimeout(900_000);
    const s = await staffed(['IELTS'], 'f56', { request, uniqueEmail, apiLogin: org.apiLogin });
    await ensureGradingSkill(s, 'IELTS_W_TASK2');
    await ensureGradingSkill(s, 'IELTS_S_PART1');
    const testVersionId = await publishedIeltsTest(s, `F56 ${Math.random().toString(36).slice(2, 8)}`);
    await seed.grading.policy({ testVersionId, mode: 'BOTH' });
    await seed.grading.scoreTable({ testVersionId, skill: 'LISTENING', scaled: [0, 5.5] });
    await seed.grading.scoreTable({ testVersionId, skill: 'READING_AC', scaled: [0, 6.0] });
    const admin = await seed.sysAdmin({ email: uniqueEmail('f56-admin') });
    const teacher = await seed.verifiedLearner({ email: uniqueEmail('f56-teacher'), displayName: 'Giáo viên F56' });
    await seed.staffGrant({ userId: teacher.userId, role: 'TEACHER', examCode: 'IELTS' });

    // (1) Gemini fails every call: the job gives up
    await gemini.reset();
    await gemini.setScenario('error500');
    const failing = await seed.learnerWithPlan({ courses: 1 });
    const failingToken = (await org.apiLogin({ email: failing.email, password: failing.password })).token;
    const failedAttempt = await sitWritingAndSpeaking(request, failingToken, testVersionId, 'F56 essay that fails.');
    await poll('AI_FAILED', async () => ((await seed.grading.ai(failedAttempt)).state === 'AI_FAILED' ? true : null), { timeoutMs: 360_000, intervalMs: 2_000 });

    // (2) Gemini answers: the AI scores it, the teacher re-scores it
    await gemini.reset();
    const learner = await seed.learnerWithPlan({ courses: 1 });
    const learnerToken = (await org.apiLogin({ email: learner.email, password: learner.password })).token;
    const attemptId = await sitWritingAndSpeaking(request, learnerToken, testVersionId, `F56 essay ${Math.random().toString(36).slice(2, 8)}: cities or villages.`);
    await poll('the AI\'s preliminary scores', async () => ((await seed.grading.scores(attemptId)).scores.filter((x) => x.source === 'AI').length === 2 ? true : null), { timeoutMs: 360_000, intervalMs: 2_000 });
    const item = await poll('the item in the teachers\' queue', async () => (await seed.grading.queue(attemptId)).item, { timeoutMs: 60_000, intervalMs: 1_000 });
    await auth.loginAs(page, teacher);
    await page.goto('/cms/grading-queue');
    const row = page.locator(`[data-item-id="${item.id}"]`);
    await row.getByRole('button', { name: 'Nhận bài' }).click();
    await row.getByRole('link', { name: 'Chấm bài' }).click();
    const writing = page.getByRole('region', { name: 'Writing' });
    await writing.getByLabel('Task response (task_response)').fill('8');
    await writing.getByLabel('Coherence and cohesion (coherence)').fill('7');
    await writing.getByLabel('Nhận xét cho học viên').fill('Clear position.');
    await writing.getByRole('button', { name: 'Lưu điểm kỹ năng này' }).click();
    await expect(writing.getByText('Đã chấm: 7.5')).toBeVisible();
    const speaking = page.getByRole('region', { name: 'Speaking' });
    await speaking.getByLabel('Task response (task_response)').fill('7');
    await speaking.getByLabel('Coherence and cohesion (coherence)').fill('7');
    await speaking.getByLabel('Nhận xét cho học viên').fill('Fluent.');
    await speaking.getByRole('button', { name: 'Lưu điểm kỹ năng này' }).click();
    await expect(speaking.getByText('Đã chấm: 7')).toBeVisible();

    // the SYS_ADMIN's page
    const adminPage = await (await browser.newContext({ ignoreHTTPSErrors: true })).newPage();
    await auth.loginAs(adminPage, admin);
    await adminPage.goto('/admin/ai-monitor');
    await expect(adminPage.getByRole('heading', { level: 1, name: 'Giám sát chấm bài AI' })).toBeVisible();
    await expect(adminPage.getByTestId('cost-alert')).toBeVisible();
    await expect(adminPage.getByTestId('cost-day').filter({ has: adminPage.getByText('Vượt ngưỡng') }).first()).toBeVisible();
    await expect(adminPage.getByTestId('cost-person').filter({ hasText: learner.email })).not.toHaveCount(0);
    await expect(adminPage.getByTestId('failed-job').filter({ hasText: 'ai_grade' }).first()).toBeVisible();
    await expect(adminPage.getByTestId('deviation-row').filter({ hasText: /Writing|IELTS_W_TASK2/ }).first()).toBeVisible();
    await axeClean(adminPage);

    // the same over the API; a range with nothing in it raises no alert; nobody else reads it
    const adminToken = (await org.apiLogin(admin)).token;
    const body = await api(adminToken, 'GET', '/api/admin/ai-monitor');
    expect(body.status, body.text).toBe(200);
    expect(body.body.costAlert).toBe(true);
    expect(body.body.failedJobs.some((j: any) => j.type === 'ai_grade')).toBe(true);
    const types = body.body.deviation.map((d: any) => d.typeCode);
    expect(types).toContain('IELTS_W_TASK2');
    expect(types).toContain('IELTS_S_PART1');
    const old = await api(adminToken, 'GET', '/api/admin/ai-monitor?from=2020-01-01&to=2020-01-10');
    expect(old.body.costAlert).toBe(false);
    expect(old.body.failedJobs).toEqual([]);
    for (const who of [learnerToken, (await org.apiLogin(teacher)).token, s.author.token]) {
      expect((await api(who, 'GET', '/api/admin/ai-monitor')).status).toBe(403);
    }
  });
});
