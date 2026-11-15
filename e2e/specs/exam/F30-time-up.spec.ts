// F30 — Hết giờ (spec §16.2): "Hết giờ phần → tự chuyển; hết giờ đề → tự nộp".
//
// Through the real stack, with a TOEIC test of two short parts (P2 Listening: 1 question, P5 Reading: 2 questions):
//   - room open: the learner answers and never presses "Kết thúc phần này" or "Nộp bài". When the clock of P2 runs out the room saves the answer still
//     on screen and moves to P5 by itself; when the clock of P5 runs out the attempt is submitted with reason TIME_UP. The answers that were on screen
//     at the deadline are the ones scored (Review Focus #2: the last saved answer counts).
//   - room closed: the learner starts the attempt, saves one answer and leaves. Nobody opens the room again: the auto_submit_attempt job submits it
//     at the deadline, which is what makes it scored (scoring listens to AttemptSubmitted, which only a saved submission publishes).
import { test, expect } from '../../fixtures';
import { poll } from '../../helpers/env';
import { enterExam, navigator } from './_exam';

/** 1 right answer = 5 points, for both skills (a TEST-scope table, so no other spec's table collides). */
async function tables(seed: typeof import('../../fixtures/seed').seed, testVersionId: string): Promise<void> {
  await seed.grading.scoreTable({ testVersionId, skill: 'LISTENING', scaled: [0, 5] });
  await seed.grading.scoreTable({ testVersionId, skill: 'READING', scaled: [0, 5, 10] });
}

async function scored(seed: typeof import('../../fixtures/seed').seed, attemptId: string) {
  // the answers may still arrive for the late-sync grace (2 min); scoring starts after it
  return poll('the scores', async () => {
    const r = await seed.grading.scores(attemptId);
    return r.graded ? r : null;
  }, { timeoutMs: 360_000, intervalMs: 3_000 });
}

test.describe('F30 time up', () => {
  test('F30 the room moves to the next part when its time is up and submits the attempt when the last part is over, scoring the answers on screen', async ({ page, seed, auth, org }) => {
    test.setTimeout(540_000);
    const exam = await seed.publishedShortToeicTest({ listening: 30, reading: 30 });
    await tables(seed, exam.testVersionId);
    const learner = await seed.learnerWithPlan({ courses: 1, examCode: 'TOEIC' });
    await auth.loginAs(page, learner);
    const attemptId = await enterExam(page, exam.testVersionId);
    const [listening, reading] = exam.parts;

    await expect(page.getByText('Phần 1/2')).toBeVisible();
    // the right answer, from the keyboard; the question is never left, so only the save at the deadline sends it
    await page.keyboard.press(String(listening!.questions[0]!.answer));
    await expect(page.getByText('Phần 2/2'), 'the room moves to P5 by itself when the 30 s of P2 are over').toBeVisible({ timeout: 45_000 });
    await expect(navigator(page)).toHaveCount(2);
    await expect(page.getByRole('button', { name: /Kết thúc phần này|Nộp bài/ })).toBeVisible();

    // P5: the first question right, the second wrong; the learner stays on the second one
    const right = String(reading!.questions[0]!.answer);
    const wrongOf = (a: string) => (a === 'A' ? 'B' : 'A');
    await page.keyboard.press(right);
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press(wrongOf(String(reading!.questions[1]!.answer)));

    const result = page.getByTestId('exam-result');
    await expect(result, 'the attempt is submitted by itself when the 30 s of P5 are over').toHaveAttribute('data-status', 'SUBMITTED', { timeout: 45_000 });
    await expect(result).toHaveAttribute('data-interrupted', 'false');
    await expect(page.getByRole('region', { name: 'Câu hỏi' })).toHaveCount(0);

    const { token } = await org.apiLogin({ email: learner.email, password: learner.password });
    const view = await org.api(token, 'GET', `/api/exam/attempts/${attemptId}`);
    expect(view.status, view.text).toBe(200);
    expect(view.body.status).toBe('SUBMITTED');
    expect(view.body.result.reason, 'nobody pressed "Nộp bài": the time did').toBe('TIME_UP');

    const scores = await scored(seed, attemptId);
    expect(scores.scores.find((s) => s.skill === 'LISTENING'), 'the P2 answer saved at the deadline counts').toMatchObject({ raw: 1, rawMax: 1, scaled: 5 });
    expect(scores.scores.find((s) => s.skill === 'READING'), 'both P5 answers on screen at the deadline count').toMatchObject({ raw: 1, rawMax: 2, scaled: 5 });
  });

  test('F30 with the room closed, the job submits the attempt at the deadline and it is scored', async ({ seed, org }) => {
    test.setTimeout(480_000);
    const exam = await seed.publishedShortToeicTest({ listening: 5, reading: 5 });
    await tables(seed, exam.testVersionId);
    const learner = await seed.learnerWithPlan({ courses: 1, examCode: 'TOEIC' });
    const { token } = await org.apiLogin({ email: learner.email, password: learner.password });

    const started = await org.api(token, 'POST', '/api/exam/attempts', { body: { testVersionId: exam.testVersionId } });
    expect(started.status, started.text).toBe(201);
    const attemptId = started.body.attemptId as string;
    const question = started.body.currentSection.groups[0].questions[0].questionId as string;
    const answeredAt = new Date(Date.parse(started.body.serverNow) + 1000).toISOString();
    await new Promise((r) => setTimeout(r, 1500)); // answeredAt is not in the server's future
    const put = await org.api(token, 'PUT', `/api/exam/attempts/${attemptId}/responses/${question}`, { body: { answer: exam.parts[0]!.questions[0]!.answer, answeredAt } });
    expect(put.status, put.text).toBe(204);
    // the learner leaves: no finish, no submit, no further request until the scores exist

    const scores = await scored(seed, attemptId);
    expect(scores.scores.find((s) => s.skill === 'LISTENING')).toMatchObject({ raw: 1, rawMax: 1 });
    expect(scores.scores.find((s) => s.skill === 'READING'), 'P5 was never opened: nothing right').toMatchObject({ raw: 0, rawMax: 2 });

    const view = await org.api(token, 'GET', `/api/exam/attempts/${attemptId}`);
    expect(view.status, view.text).toBe(200);
    expect(view.body).toMatchObject({ status: 'SUBMITTED', result: { reason: 'TIME_UP', interrupted: false } });
  });
});
