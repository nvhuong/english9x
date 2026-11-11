// F32 — Ghi âm Speaking (spec §16.2): "kiểm tra micro, đếm ngược chuẩn bị, ghi đúng thời lượng, tải lên".
//
// Through the real stack with a fake microphone stream: the learner reaches the Speaking section, checks the microphone, waits out the preparation, and the
// recording stops by itself at the length of the question and is uploaded to the attempt (204).
import { test, expect } from '../../fixtures';
import { enterExam, finishSection } from './_exam';

test.use({ permissions: ['microphone'] });

/**
 * A microphone made of a Web Audio oscillator. Chromium's own fake capture device (--use-fake-device-for-media-stream) never answers getUserMedia on macOS hosts
 * (it hangs; the audio service has no device to open), so the spec hands the page a MediaStream of its own: everything after the stream is the real recorder
 * (the level meter, MediaRecorder, the stop at the set length, the upload).
 */
const FAKE_MIC = () => {
  navigator.mediaDevices.getUserMedia = async () => {
    const ctx = new AudioContext();
    const osc = ctx.createOscillator();
    const dest = ctx.createMediaStreamDestination();
    osc.connect(dest);
    osc.start();
    return dest.stream;
  };
};

test.describe('F32 speaking recorder', () => {
  test('F32 mic check, preparation, a recording that stops at the set length, and its upload', async ({ page, seed, auth }) => {
    test.setTimeout(240_000);
    const exam = await seed.publishedIeltsTest({ variant: 'AC' });
    const learner = await seed.learnerWithPlan({ courses: 1 });
    await auth.loginAs(page, learner);
    await page.addInitScript(FAKE_MIC);
    await enterExam(page, exam.testVersionId);
    for (const n of [1, 2, 3]) {
      await expect(page.getByText(`Phần ${n}/4`)).toBeVisible();
      await finishSection(page);
    }
    await expect(page.getByText('Phần 4/4')).toBeVisible();

    const recorder = page.getByRole('group', { name: 'Ghi âm bài nói' });
    await recorder.getByRole('button', { name: 'Kiểm tra micro' }).click();
    await expect(recorder.getByRole('button', { name: 'Bắt đầu' })).toBeVisible();
    const upload = page.waitForResponse((r) => /\/api\/exam\/attempts\/[^/]+\/recordings\//.test(r.url()) && r.request().method() === 'POST', { timeout: 90_000 });
    const started = Date.now();
    await recorder.getByRole('button', { name: 'Bắt đầu' }).click();
    await expect(recorder).toHaveAttribute('data-phase', 'preparing'); // 1 second of preparation
    await expect(recorder).toHaveAttribute('data-phase', 'recording');
    const recordingFrom = Date.now();
    const response = await upload; // stops by itself after speakSeconds = 30 (the schema minimum)
    expect(response.status()).toBe(204);
    const length = Date.now() - recordingFrom;
    expect(length, `recorded for ~30 s, took ${length} ms after ${Date.now() - started} ms in all`).toBeGreaterThan(29_000);
    expect(length).toBeLessThan(40_000);
    await expect(recorder).toHaveAttribute('data-phase', 'done');
    await expect(page.getByRole('button', { name: /Câu 1, đã trả lời/ })).toBeVisible();
  });
});
