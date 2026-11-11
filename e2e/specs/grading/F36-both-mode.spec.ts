// F36 — Chế độ "Cả hai" (spec §16.2): "AI hiện ngay là sơ bộ → GV chấm → điểm GV thay, chính thức".
//
// Mode BOTH (the policy of this spec's own test): after the late-sync grace the AI grades the essay and the recording (the fake Gemini answers band 6.5) and the learner is told
// the score is PRELIMINARY; the attempt is also in the teachers' queue, where a teacher sees the AI's result next to the answer, scores each criterion on the real screen and
// saves. Their score is OFFICIAL and replaces the AI's, the deviation (teacher minus AI) is stored, and the learner is told the official score is ready. The page that shows the
// labels to the learner is P4.4 (e2e/partial-features.json): until then the spec reads the score rows through the seed API.
import { test, expect } from '../../fixtures';
import { staffed } from '../../fixtures/content';
import { ensureGradingSkill, publishedIeltsTest, sitWritingAndSpeaking } from '../../fixtures/ai-grading';
import { api } from '../../fixtures/org';
import { poll } from '../../helpers/env';

test.describe('F36 both mode', () => {
  test('F36 the AI score is preliminary at once; the teacher\'s score is official, replaces it and records the deviation', async ({ page, request, uniqueEmail, org, seed, auth, gemini }) => {
    // the late-sync grace (two minutes) is waited out for real; the fake-Gemini lock is held for the test and released with it
    test.setTimeout(540_000);
    const s = await staffed(['IELTS'], 'f36', { request, uniqueEmail, apiLogin: org.apiLogin });
    await ensureGradingSkill(s, 'IELTS_W_TASK2');
    await ensureGradingSkill(s, 'IELTS_S_PART1');
    const testVersionId = await publishedIeltsTest(s, `F36 ${Math.random().toString(36).slice(2, 8)}`);
    await seed.grading.policy({ testVersionId, mode: 'BOTH' });
    // Listening and Reading are converted by tables of this test, so that every skill of the attempt has a score and the announcements can be made
    await seed.grading.scoreTable({ testVersionId, skill: 'LISTENING', scaled: [0, 5.5] });
    await seed.grading.scoreTable({ testVersionId, skill: 'READING_AC', scaled: [0, 6.0] });

    const teacher = await seed.verifiedLearner({ email: uniqueEmail('f36-teacher'), displayName: 'Giáo viên Cả Hai' });
    await seed.staffGrant({ userId: teacher.userId, role: 'TEACHER', examCode: 'IELTS' });
    const learner = await seed.learnerWithPlan({ courses: 1 });
    const learnerToken = (await org.apiLogin({ email: learner.email, password: learner.password })).token;
    await gemini.reset();
    const attemptId = await sitWritingAndSpeaking(request, learnerToken, testVersionId, `F36 essay ${Math.random().toString(36).slice(2, 8)}: cities or villages.`);

    // the AI's scores are there, PRELIMINARY, and the learner is told so
    const preliminary = await poll('the AI\'s preliminary scores', async () => {
      const r = await seed.grading.scores(attemptId);
      return r.scores.filter((x) => x.source === 'AI' && x.kind === 'PRELIMINARY').length === 2 ? r : null;
    }, { timeoutMs: 360_000, intervalMs: 2_000 });
    for (const skill of ['WRITING', 'SPEAKING']) {
      expect(preliminary.scores.find((x) => x.skill === skill && x.source === 'AI'), skill).toMatchObject({ kind: 'PRELIMINARY', scaled: 6.5 });
    }
    expect(preliminary.scores.filter((x) => x.kind === 'OFFICIAL' && (x.skill === 'WRITING' || x.skill === 'SPEAKING')), 'no official score yet').toEqual([]);
    const inbox = async () => {
      const r = await api(learnerToken, 'GET', '/api/me/notifications?limit=50');
      expect(r.status, r.text).toBe(200);
      return r.body.items as Array<{ type: string; data?: { attemptId?: string } }>;
    };
    await poll('SCORE_PRELIMINARY', async () => ((await inbox()).some((n) => n.type === 'SCORE_PRELIMINARY' && n.data?.attemptId === attemptId) ? true : null), { timeoutMs: 30_000 });
    expect((await inbox()).some((n) => n.type === 'SCORE_OFFICIAL' && n.data?.attemptId === attemptId), 'not official yet').toBe(false);

    // the teacher claims it and grades both skills on the real screen, with the AI's result beside the answer
    const item = await poll('the item in the teachers\' queue', async () => (await seed.grading.queue(attemptId)).item, { timeoutMs: 60_000, intervalMs: 1_000 });
    expect(item).toMatchObject({ state: 'OPEN', reason: 'POLICY' });
    await auth.loginAs(page, teacher);
    await page.goto('/cms/grading-queue');
    const row = page.locator(`[data-item-id="${item.id}"]`);
    await row.getByRole('button', { name: 'Nhận bài' }).click();
    await row.getByRole('link', { name: 'Chấm bài' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Chấm bài' })).toBeVisible();
    const writing = page.getByRole('region', { name: 'Writing' });
    await expect(writing.getByText('AI: 6.5', { exact: true })).toBeVisible();
    await expect(writing.getByText('cities or villages', { exact: false })).toBeVisible();
    await writing.getByLabel('Task response (task_response)').fill('8');
    await writing.getByLabel('Coherence and cohesion (coherence)').fill('7');
    await writing.getByLabel('Nhận xét cho học viên').fill('Clear position, good paragraphing.');
    await writing.getByRole('button', { name: 'Lưu điểm kỹ năng này' }).click();
    await expect(writing.getByText('Đã chấm: 7.5')).toBeVisible();
    const speaking = page.getByRole('region', { name: 'Speaking' });
    await speaking.getByLabel('Task response (task_response)').fill('7');
    await speaking.getByLabel('Coherence and cohesion (coherence)').fill('7');
    await speaking.getByLabel('Nhận xét cho học viên').fill('Fluent, a few hesitations.');
    await speaking.getByRole('button', { name: 'Lưu điểm kỹ năng này' }).click();
    await expect(speaking.getByText('Đã chấm: 7')).toBeVisible();

    // the teacher's scores are OFFICIAL and replace the AI's (which stay as what the learner first saw); the deviation is stored
    const scores = await poll('the official scores', async () => {
      const r = await seed.grading.scores(attemptId);
      return r.scores.filter((x) => x.source === 'TEACHER' && x.kind === 'OFFICIAL').length === 2 ? r : null;
    }, { timeoutMs: 30_000 });
    expect(scores.scores.find((x) => x.skill === 'WRITING' && x.source === 'TEACHER')).toMatchObject({ kind: 'OFFICIAL', scaled: 7.5 });
    expect(scores.scores.find((x) => x.skill === 'SPEAKING' && x.source === 'TEACHER')).toMatchObject({ kind: 'OFFICIAL', scaled: 7 });
    expect(scores.scores.find((x) => x.skill === 'WRITING' && x.source === 'AI')).toMatchObject({ kind: 'PRELIMINARY', scaled: 6.5 });
    const done = await seed.grading.queue(attemptId);
    expect(done.item).toMatchObject({ state: 'DONE', claimed_by: teacher.userId });
    expect(done.reviews.find((r) => r.skill === 'WRITING')).toMatchObject({ band: 7.5, aiBand: 6.5, deviation: 1, comment: 'Clear position, good paragraphing.' });
    expect(done.reviews.find((r) => r.skill === 'SPEAKING')).toMatchObject({ band: 7, aiBand: 6.5, deviation: 0.5 });

    // and the learner is told the official score is ready
    await poll('SCORE_OFFICIAL', async () => ((await inbox()).some((n) => n.type === 'SCORE_OFFICIAL' && n.data?.attemptId === attemptId) ? true : null), { timeoutMs: 30_000 });
  });
});
