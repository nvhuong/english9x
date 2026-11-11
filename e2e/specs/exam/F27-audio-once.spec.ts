// F27 — Âm thanh Listening chỉ phát một lần (spec §16.2): "không tua, không tạm dừng, không phát lại sau khi tải lại".
//
// Through the real stack: the room offers the recording of the Listening section with a volume control and nothing else (no pause, seek, speed, native controls);
// the server gives the file out once, so asking for it again is refused with 410 and, after a reload of the page, the room says the recording was played
// and offers no player.
import { test, expect } from '../../fixtures';
import { enterExam } from './_exam';

test.describe('F27 audio once', () => {
  test('F27 the recording has no pause or seek, and is not offered a second time', async ({ page, seed, auth, org }) => {
    test.setTimeout(240_000);
    const exam = await seed.publishedIeltsTest({ variant: 'AC' });
    const learner = await seed.learnerWithPlan({ courses: 1 });
    await auth.loginAs(page, learner);
    const audioRequests: number[] = [];
    page.on('response', (r) => {
      if (/\/api\/exam\/attempts\/[^/]+\/audio\//.test(r.url())) audioRequests.push(r.status());
    });
    const attemptId = await enterExam(page, exam.testVersionId);

    const player = page.getByRole('group', { name: 'Bản ghi âm bài nghe' });
    await expect(player).toBeVisible();
    const start = player.getByRole('button', { name: 'Bắt đầu phát' });
    if (await start.isVisible()) await start.click(); // the browser may refuse to start sound by itself
    await expect(player.getByRole('status')).toContainText(/Đang phát|Đã phát xong/);
    await expect(player.locator('audio')).toHaveCount(1);
    expect(await player.locator('audio').getAttribute('controls')).toBeNull();
    await expect(player.getByRole('slider')).toHaveCount(1); // the volume
    await expect(player.getByRole('button', { name: /dừng|tạm|tua|phát lại/i })).toHaveCount(0);
    expect(audioRequests).toEqual([200]);

    // a second request for the same file (a script, a second tab) is refused by the server
    const token = (await org.apiLogin({ email: learner.email, password: learner.password })).token;
    const mediaId = await page.evaluate(() => performance.getEntriesByType('resource').map((e) => e.name).find((n) => /\/api\/exam\/attempts\/[^/]+\/audio\//.test(n)) ?? '');
    expect(mediaId).toContain(`/api/exam/attempts/${attemptId}/audio/`);
    const again = await org.api(token, 'GET', new URL(mediaId).pathname);
    expect(again.status, again.text).toBe(410);

    // a reload comes back to the room (a resume) but the recording is not offered again
    await page.reload();
    await expect(page.getByRole('region', { name: 'Câu hỏi' })).toBeVisible();
    await expect(page.getByText(/đã được phát một lần/)).toBeVisible();
    await expect(page.locator('audio')).toHaveCount(0);
  });
});
