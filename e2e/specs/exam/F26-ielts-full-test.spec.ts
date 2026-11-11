// F26 — Làm trọn đề IELTS, AC và GT (spec §16.2): "bốn kỹ năng đúng thứ tự, điểm Listening và Reading theo bảng".
//
// Through the real stack, once for Academic and once for General Training: a learner sits the four sections in order. Listening has its recording, a
// multiple-choice (checkboxes), typed gaps, matching (selects) and a short answer; Reading has the passage beside the questions (True/False/Not Given,
// Yes/No/Not Given, short answer); Writing has two tasks with a word count; Speaking is left (F32 records). After the late-sync grace the scoring (P4.1)
// holds the raw Listening and Reading scores the key predicts, converted by the table of the variant's own Reading skill.
import { test, expect } from '../../fixtures';
import { poll } from '../../helpers/env';
import { answerIelts, enterExam, finishSection, navigator, partOf } from './_exam';

for (const variant of ['AC', 'GT'] as const) {
  test.describe(`F26 IELTS ${variant} full test`, () => {
    test(`F26 a learner sits the four sections of an ${variant} test in order and is scored on Listening and Reading by the key`, async ({ page, seed, auth }) => {
      test.setTimeout(480_000);
      const exam = await seed.publishedIeltsTest({ variant });
      const readingSkill = variant === 'AC' ? 'READING_AC' : 'READING_GT';
      await seed.grading.scoreTable({ testVersionId: exam.testVersionId, skill: 'LISTENING', scaled: [0, 5, 6, 7, 8] });
      await seed.grading.scoreTable({ testVersionId: exam.testVersionId, skill: readingSkill, scaled: [0, 5, 6, 7] });
      const learner = await seed.learnerWithPlan({ courses: 1 });
      await auth.loginAs(page, learner);
      const attemptId = await enterExam(page, exam.testVersionId);

      // ---- Listening: the recording is offered (played once), then every kind of question
      await expect(page.getByText('Phần 1/4')).toBeVisible();
      await expect(page.getByRole('group', { name: 'Bản ghi âm bài nghe' })).toBeVisible();
      const listening = partOf(exam.parts, 'LISTENING').questions;
      for (const [i, q] of listening.entries()) {
        await answerIelts(page, q, i + 1);
        if (i < listening.length - 1) await page.getByRole('button', { name: 'Câu sau' }).click(); // not the arrow key: focus is in a typed gap, where it moves the caret
      }
      await expect(page.getByRole('group', { name: 'Danh sách câu hỏi' }).getByRole('button', { name: /chưa trả lời/ })).toHaveCount(0);
      await finishSection(page);

      // ---- Reading: passage next to the question; the second answer is wrong on purpose
      await expect(page.getByText('Phần 2/4')).toBeVisible();
      await expect(page.getByRole('region', { name: 'Bài đọc' })).toContainText('The bridge was finished in 1932.');
      const reading = partOf(exam.parts, 'READING').questions;
      for (const [i, q] of reading.entries()) {
        await answerIelts(page, q, i + 1, { wrong: i === 1 });
        if (i < reading.length - 1) await page.getByRole('button', { name: 'Câu sau' }).click(); // not the arrow key: focus is in a typed gap, where it moves the caret
      }
      await finishSection(page);

      // ---- Writing: two tasks, a live word count, no spell-check
      await expect(page.getByText('Phần 3/4')).toBeVisible();
      const box = page.getByRole('textbox');
      await expect(box).toHaveAttribute('spellcheck', 'false');
      await box.fill('one two three four five');
      await expect(page.getByTestId('word-count')).toHaveText('5 từ');
      await navigator(page).nth(1).click();
      await page.getByRole('textbox').fill('alpha beta');
      await expect(page.getByTestId('word-count')).toHaveText('2 từ');
      await finishSection(page);

      // ---- Speaking: left; submit
      await expect(page.getByText('Phần 4/4')).toBeVisible();
      await finishSection(page);
      await expect(page.getByTestId('exam-result')).toHaveAttribute('data-status', 'SUBMITTED');

      const scores = await poll('the scores', async () => {
        const r = await seed.grading.scores(attemptId);
        return r.graded ? r : null;
      }, { timeoutMs: 300_000, intervalMs: 3_000 });
      expect(scores.scores.find((x) => x.skill === 'LISTENING')).toMatchObject({ raw: 4, rawMax: 4, scaled: 8 });
      expect(scores.scores.find((x) => x.skill === 'READING')).toMatchObject({ raw: 2, rawMax: 3, scaled: 6 });
    });
  });
}
