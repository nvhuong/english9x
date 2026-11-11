// F25 — Làm trọn đề TOEIC (spec §16.2): "200 câu, đúng thứ tự phần, điểm quy đổi đúng".
//
// Through the real stack: a learner with a plan sits a PUBLISHED TOEIC test of 200 questions (parts P1-P7) in the exam room, answering from the keyboard
// (A/B/C/D and the right arrow) according to the seed's answer key, with some answers deliberately wrong; finishes each part, submits, and after the
// late-sync grace the scoring (P4.1) holds the raw score the key predicts and its conversion by the published table.
import { test, expect } from '../../fixtures';
import { poll } from '../../helpers/env';
import { answerByKeyboard, enterExam, expectAllAnswered, finishSection, navigator } from './_exam';

test.describe('F25 TOEIC full test', () => {
  test('F25 a learner answers 200 questions from the keyboard, part by part, submits, and is scored by the answer key', async ({ page, seed, auth }) => {
    test.setTimeout(600_000);
    const exam = await seed.publishedToeicTest();
    expect(exam.parts.flatMap((p) => p.questions)).toHaveLength(200);
    expect(exam.parts.map((p) => p.questions.length)).toEqual([6, 25, 39, 30, 30, 16, 54]);
    // 1 right = 5 per question (a table of 101 entries), same for both skills
    const scaled = Array.from({ length: 101 }, (_, i) => i * 5);
    await seed.grading.scoreTable({ testVersionId: exam.testVersionId, skill: 'LISTENING', scaled });
    await seed.grading.scoreTable({ testVersionId: exam.testVersionId, skill: 'READING', scaled });
    const learner = await seed.learnerWithPlan({ courses: 1, examCode: 'TOEIC' });
    await auth.loginAs(page, learner);
    const attemptId = await enterExam(page, exam.testVersionId);

    const wrong = (i: number) => i % 10 === 9; // the 10th, 20th... question of every part is answered wrongly
    const expected: Record<string, number> = { LISTENING: 0, READING: 0 };
    for (const [pi, part] of exam.parts.entries()) {
      await expect(page.getByText(`Phần ${pi + 1}/7`)).toBeVisible();
      await expect(navigator(page)).toHaveCount(part.questions.length);
      await answerByKeyboard(page, part.questions, { wrong });
      part.questions.forEach((_, i) => {
        if (!wrong(i)) expected[part.skill]! += 1;
      });
      await expectAllAnswered(page, part.questions.length);
      await finishSection(page);
      if (pi < exam.parts.length - 1) await expect(page.getByText(`Phần ${pi + 2}/7`)).toBeVisible();
    }
    await expect(page.getByTestId('exam-result')).toHaveAttribute('data-status', 'SUBMITTED');
    await expect(page.getByTestId('exam-result')).toHaveAttribute('data-interrupted', 'false');

    // the answers may still arrive for the late-sync grace (2 min); scoring starts after it
    const scores = await poll('the scores', async () => {
      const r = await seed.grading.scores(attemptId);
      return r.graded ? r : null;
    }, { timeoutMs: 360_000, intervalMs: 3_000 });
    for (const skill of ['LISTENING', 'READING']) {
      const s = scores.scores.find((x) => x.skill === skill)!;
      expect(s, skill).toMatchObject({ raw: expected[skill], rawMax: skill === 'LISTENING' ? 100 : 100, scaled: expected[skill]! * 5 });
    }
  });
});
