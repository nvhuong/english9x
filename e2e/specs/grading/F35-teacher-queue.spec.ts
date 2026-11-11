// F35 — Hàng chờ giáo viên (spec §16.2): "GV nhận bài → GV khác không thấy → quá hạn → bài trở lại → quản trị chuyển bài".
//
// Mode TEACHER (the policy of this spec's own test): the learner's Writing and Speaking go to the teachers' queue of English9x once the late-sync grace is over (the job
// `grading_enqueue`), and the teachers of IELTS are told. One teacher claims it on the real screen; it is hidden from the other teacher, who cannot take it either. The deadline of
// the claim passes (the real default is 24 hours: the spec moves it into the past) and the real `release_expired_claim` job (every few seconds in the E2E stack) returns it to
// the queue; then the administrator hands it to a teacher. Teachers are English9x's own (the personal context): a centre's queue is exercised by the backend ITs.
import { test, expect } from '../../fixtures';
import { staffed } from '../../fixtures/content';
import { ensureGradingSkill, publishedIeltsTest, sitWritingAndSpeaking } from '../../fixtures/ai-grading';
import { api } from '../../fixtures/org';
import { poll } from '../../helpers/env';

test.describe('F35 teachers queue', () => {
  test('F35 a claimed submission is hidden from the other teachers, returns to the queue past its deadline and is handed over by the administrator', async ({ page, request, uniqueEmail, org, seed, auth }) => {
    // the late-sync grace (two minutes) is waited out for real
    test.setTimeout(480_000);
    const s = await staffed(['IELTS'], 'f35', { request, uniqueEmail, apiLogin: org.apiLogin });
    await ensureGradingSkill(s, 'IELTS_W_TASK2');
    await ensureGradingSkill(s, 'IELTS_S_PART1');
    const testVersionId = await publishedIeltsTest(s, `F35 ${Math.random().toString(36).slice(2, 8)}`);
    await seed.grading.policy({ testVersionId, mode: 'TEACHER' });

    const first = await seed.verifiedLearner({ email: uniqueEmail('f35-teacher1'), displayName: 'Giáo viên Một' });
    const second = await seed.verifiedLearner({ email: uniqueEmail('f35-teacher2'), displayName: 'Giáo viên Hai' });
    for (const t of [first, second]) await seed.staffGrant({ userId: t.userId, role: 'TEACHER', examCode: 'IELTS' });
    const admin = await seed.sysAdmin({ email: uniqueEmail('f35-admin') });
    const [token1, token2, adminToken] = [(await org.apiLogin(first)).token, (await org.apiLogin(second)).token, (await org.apiLogin(admin)).token];

    const learner = await seed.learnerWithPlan({ courses: 1 });
    const learnerToken = (await org.apiLogin({ email: learner.email, password: learner.password })).token;
    const attemptId = await sitWritingAndSpeaking(request, learnerToken, testVersionId, `F35 essay ${Math.random().toString(36).slice(2, 8)}.`);
    expect((await seed.grading.queue(attemptId)).item, 'nothing is queued while an answer may still arrive').toBeNull();

    const queued = await poll('the item in the teachers\' queue', async () => (await seed.grading.queue(attemptId)).item, { timeoutMs: 360_000, intervalMs: 2_000 });
    expect(queued).toMatchObject({ state: 'OPEN', reason: 'POLICY', grading_org_id: null });
    const inbox = async (token: string) => {
      const r = await api(token, 'GET', '/api/me/notifications?limit=50');
      expect(r.status, r.text).toBe(200);
      return r.body.items as Array<{ type: string; data?: { itemId?: string } }>;
    };
    await poll('the teachers are told', async () => ((await inbox(token1)).some((n) => n.type === 'GRADING_QUEUE_NEW' && n.data?.itemId === queued.id) ? true : null), { timeoutMs: 30_000 });

    // the first teacher claims it on the real screen
    await auth.loginAs(page, first);
    await page.goto('/cms/grading-queue');
    const row = page.locator(`[data-item-id="${queued.id}"]`);
    await expect(row).toBeVisible();
    await row.getByRole('button', { name: 'Nhận bài' }).click();
    await expect(row.getByText('Bài của bạn')).toBeVisible();
    await expect(row.getByRole('link', { name: 'Chấm bài' })).toHaveAttribute('href', `/cms/grading-queue/${queued.id}`);
    expect((await seed.grading.queue(attemptId)).item).toMatchObject({ state: 'CLAIMED', claimed_by: first.userId });

    // hidden from the other teacher, who cannot take it
    const seenBySecond = await api(token2, 'GET', '/api/teacher/queue');
    expect(seenBySecond.status, seenBySecond.text).toBe(200);
    expect(seenBySecond.body.map((i: any) => i.id)).not.toContain(queued.id);
    expect((await api(token2, 'POST', `/api/teacher/queue/${queued.id}/claim`)).status).toBe(404);
    expect((await api(token2, 'GET', `/api/teacher/queue/${queued.id}`)).status).toBe(404);

    // past the deadline: back in the queue, visible to the other teacher, no longer the first one's
    await seed.grading.expireClaim(attemptId);
    await poll('the claim returns to the queue', async () => ((await seed.grading.queue(attemptId)).item?.state === 'OPEN' ? true : null), { timeoutMs: 60_000, intervalMs: 1_000 });
    expect((await seed.grading.queue(attemptId)).item?.claimed_by).toBeNull();
    const backForSecond = await api(token2, 'GET', '/api/teacher/queue');
    expect(backForSecond.body.find((i: any) => i.id === queued.id)).toMatchObject({ state: 'OPEN', mine: false });
    expect((await api(token1, 'GET', `/api/teacher/queue/${queued.id}`)).status, 'the first teacher no longer holds it').toBe(409);

    // the administrator hands it to the second teacher, who now holds it
    const handed = await api(adminToken, 'POST', `/api/admin/queue/${queued.id}/reassign`, { body: { teacherId: second.userId } });
    expect(handed.status, handed.text).toBe(200);
    expect(handed.body).toMatchObject({ state: 'CLAIMED', claimedBy: second.userId });
    expect((await seed.grading.queue(attemptId)).item).toMatchObject({ state: 'CLAIMED', claimed_by: second.userId });
    const mineNow = await api(token2, 'GET', '/api/teacher/queue');
    expect(mineNow.body.find((i: any) => i.id === queued.id)).toMatchObject({ state: 'CLAIMED', mine: true });
    expect((await api(token1, 'GET', '/api/teacher/queue')).body.map((i: any) => i.id)).not.toContain(queued.id);
    // a person who is not a teacher of the exam cannot be given it
    const outsider = await seed.verifiedLearner({ email: uniqueEmail('f35-outsider') });
    expect((await api(adminToken, 'POST', `/api/admin/queue/${queued.id}/reassign`, { body: { teacherId: outsider.userId } })).status).toBe(422);
  });
});
