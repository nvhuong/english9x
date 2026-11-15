// F31 — Nộp idempotent (spec §16.2): "Nộp đồng thời từ 2 tab → một kết quả".
//
// Through the real stack: one learner has the same attempt open in two tabs of one browser session. Both tabs open the "Nộp bài" dialog of the last part
// and confirm at the same moment. Both tabs end on the result page of the SAME submission (one submittedAt, reason LEARNER, not interrupted), and the
// scoring, which runs once per AttemptSubmitted, writes one score per skill, from the answers saved before the submission (Review Focus #2; the race
// itself, learner against the auto-submit job, is ConcurrentSubmitIT).
import { test, expect } from '../../fixtures';
import { poll } from '../../helpers/env';
import { enterExam, finishSection, navigator } from './_exam';

test.describe('F31 idempotent submit', () => {
  test('F31 submitting from two tabs at once gives one result', async ({ browser, seed, auth, org }) => {
    test.setTimeout(480_000);
    const exam = await seed.publishedShortToeicTest({ listening: 600, reading: 600 });
    await seed.grading.scoreTable({ testVersionId: exam.testVersionId, skill: 'LISTENING', scaled: [0, 5] });
    await seed.grading.scoreTable({ testVersionId: exam.testVersionId, skill: 'READING', scaled: [0, 5, 10] });
    const learner = await seed.learnerWithPlan({ courses: 1, examCode: 'TOEIC' });
    const [listening, reading] = exam.parts;

    const context = await browser.newContext({ ignoreHTTPSErrors: true });
    try {
      const one = await context.newPage();
      await auth.loginAs(one, learner);
      const attemptId = await enterExam(one, exam.testVersionId);
      await one.keyboard.press(String(listening!.questions[0]!.answer));
      await finishSection(one);
      await expect(one.getByText('Phần 2/2')).toBeVisible();

      // the second tab opens the same attempt (one resume of the two allowed) and is on P5 too
      const two = await context.newPage();
      await two.goto(`/exam/attempts/${attemptId}`);
      await expect(two.getByText('Phần 2/2')).toBeVisible();
      await expect(navigator(two)).toHaveCount(2);

      // both answers of P5 from the first tab, the second one wrong; leaving each question saves it
      await one.keyboard.press(String(reading!.questions[0]!.answer));
      await one.keyboard.press('ArrowRight');
      await one.keyboard.press(String(reading!.questions[1]!.answer) === 'A' ? 'B' : 'A');
      const saved = one.waitForResponse((r) => r.request().method() === 'PUT' && /\/responses\//.test(r.url()));
      await navigator(one).nth(0).click();
      expect((await saved).status()).toBe(204);

      for (const tab of [one, two]) {
        await tab.getByRole('button', { name: 'Nộp bài' }).click();
        await expect(tab.getByRole('dialog').getByRole('button', { name: 'Xác nhận' })).toBeVisible();
      }
      const submits = [one, two].map((tab) => tab.waitForResponse((r) => r.request().method() === 'POST' && /\/api\/exam\/attempts\/[^/]+\/submit$/.test(r.url())));
      await Promise.all([one, two].map((tab) => tab.getByRole('dialog').getByRole('button', { name: 'Xác nhận' }).click()));
      for (const r of await Promise.all(submits)) expect(r.status(), 'neither tab is refused: the second submit answers with the first result').toBe(200);
      const bodies = await Promise.all((await Promise.all(submits)).map((r) => r.json() as Promise<{ submittedAt: string; reason: string; status: string }>));
      expect(bodies[0]!.submittedAt, 'one submission: both answers carry its time').toBe(bodies[1]!.submittedAt);

      for (const tab of [one, two]) {
        const result = tab.getByTestId('exam-result');
        await expect(result).toHaveAttribute('data-status', 'SUBMITTED');
        await expect(result).toHaveAttribute('data-interrupted', 'false');
      }

      const { token } = await org.apiLogin({ email: learner.email, password: learner.password });
      const view = await org.api(token, 'GET', `/api/exam/attempts/${attemptId}`);
      expect(view.status, view.text).toBe(200);
      expect(view.body).toMatchObject({ status: 'SUBMITTED', result: { reason: 'LEARNER', interrupted: false, submittedAt: bodies[0]!.submittedAt } });

      const scores = await poll('the scores', async () => {
        const r = await seed.grading.scores(attemptId);
        return r.graded ? r : null;
      }, { timeoutMs: 360_000, intervalMs: 3_000 });
      expect(scores.scores.filter((s) => s.skill === 'LISTENING'), 'one score per skill: the attempt was scored once').toHaveLength(1);
      expect(scores.scores.filter((s) => s.skill === 'READING')).toHaveLength(1);
      expect(scores.scores.find((s) => s.skill === 'LISTENING')).toMatchObject({ raw: 1, rawMax: 1, scaled: 5 });
      expect(scores.scores.find((s) => s.skill === 'READING')).toMatchObject({ raw: 1, rawMax: 2, scaled: 5 });
    } finally {
      await context.close();
    }
  });
});
