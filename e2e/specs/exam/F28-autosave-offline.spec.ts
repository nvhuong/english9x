// F28 — Tự lưu và mất mạng (spec §16.2): "mất mạng vẫn làm được, có mạng lại thì gửi theo thứ tự, không mất câu trả lời".
//
// Through the real stack: the learner answers, the browser goes offline, the room keeps taking answers and says how many wait; when the connection returns
// they are sent and the status goes back to "saved". After a reload (the room is resumed) the answers are in the boxes again, the offline ones included.
import { test, expect } from '../../fixtures';
import { enterExam, navigator } from './_exam';

test.describe('F28 autosave and offline', () => {
  test('F28 answers written offline are queued and sent in order when the connection returns, and survive a reload', async ({ page, context, seed, auth }) => {
    test.setTimeout(240_000);
    const exam = await seed.publishedIeltsTest({ variant: 'AC' });
    const learner = await seed.learnerWithPlan({ courses: 1 });
    await auth.loginAs(page, learner);
    await enterExam(page, exam.testVersionId);

    // question 4 of Listening is a short answer; written online first, then changed offline twice
    await navigator(page).nth(3).click();
    const box = page.getByRole('textbox');
    await box.fill('first');
    await expect(page.getByRole('status').filter({ hasText: 'Đã lưu' })).toBeVisible({ timeout: 15_000 });

    await context.setOffline(true);
    await box.fill('second');
    await navigator(page).nth(0).click(); // a change of question hands the answer to the queue at once
    await page.getByRole('checkbox', { name: /^B\./ }).check();
    await expect(page.getByRole('status').filter({ hasText: /Mất kết nối: [12] câu trả lời/ })).toBeVisible({ timeout: 15_000 });
    await navigator(page).nth(3).click();
    await box.fill('third');

    await context.setOffline(false);
    await expect(page.getByRole('status').filter({ hasText: 'Đã lưu' })).toBeVisible({ timeout: 30_000 });

    // the server holds the LAST answer, and the multiple choice made offline
    await page.reload();
    await expect(page.getByRole('region', { name: 'Câu hỏi' })).toBeVisible();
    await navigator(page).nth(3).click();
    await expect(page.getByRole('textbox')).toHaveValue('third');
    await navigator(page).nth(0).click();
    await expect(page.getByRole('checkbox', { name: /^B\./ })).toBeChecked();
  });
});
