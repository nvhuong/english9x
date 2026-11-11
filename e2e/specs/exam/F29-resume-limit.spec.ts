// F29 — Vào lại bài thi (spec §16.2): "vào lại tối đa 2 lần, lần thứ ba bài được nộp ở trạng thái bị ngắt".
//
// Through the real stack: the page of the room is reloaded three times. Each reload resumes the attempt with the answers in place; the third one is
// beyond the limit (e9x.exam.default-max-resumes = 2), so the attempt is submitted as interrupted and the room shows that instead of the questions.
import { test, expect } from '../../fixtures';
import { enterExam, navigator } from './_exam';

test.describe('F29 resume limit', () => {
  test('F29 two reloads resume the attempt with the answers, the third submits it as interrupted', async ({ page, seed, auth }) => {
    test.setTimeout(240_000);
    const exam = await seed.publishedIeltsTest({ variant: 'GT' });
    const learner = await seed.learnerWithPlan({ courses: 1 });
    await auth.loginAs(page, learner);
    await enterExam(page, exam.testVersionId);
    await navigator(page).nth(3).click();
    await page.getByRole('textbox').fill('kept');
    await navigator(page).nth(0).click(); // leaving the question saves it

    for (const n of [1, 2]) {
      await page.reload();
      await expect(page.getByRole('region', { name: 'Câu hỏi' }), `reload ${n}`).toBeVisible();
      await navigator(page).nth(3).click();
      await expect(page.getByRole('textbox'), `reload ${n}`).toHaveValue('kept');
    }
    await page.reload();
    const result = page.getByTestId('exam-result');
    await expect(result).toHaveAttribute('data-status', 'SUBMITTED');
    await expect(result).toHaveAttribute('data-interrupted', 'true');
    await expect(page.getByRole('heading', { level: 1, name: 'Bài thi bị ngắt' })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Câu hỏi' })).toHaveCount(0);
  });
});
