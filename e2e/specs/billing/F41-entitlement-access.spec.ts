// F41 — Quyền học (spec §16.2): "Entitlement hết hạn → không vào học được nhưng xem được kết quả cũ".
//
// Part 1 (seed API and the learner's own API): the gate (billing.api.Entitlements) and the PLAN_EXPIRING / PLAN_EXPIRED notices.
// Part 2 (the real screens): a learner whose plan runs out while an attempt is under way and after one was scored. Once it has ended the lesson page and the course
// map say the learner needs a plan, a new exam is refused, the OLD RESULT page still shows the scores and the key, and the attempt that was under way is still
// finished in the exam room.
import { test, expect } from '../../fixtures';
import { staffed } from '../../fixtures/content';
import { publishedCourse, publishedLesson, publishedToeicTestVersion } from '../../fixtures/course';
import { api } from '../../fixtures/org';
import { poll } from '../../helpers/env';
import { finishSection } from '../exam/_exam';

test.describe('F41 entitlement access', () => {
  test('F41 an active plan opens new learning, an expired one does not but keeps the attempt that was under way and the history, and the learner is told twice', async ({ seed, org, uniqueEmail }) => {
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f41') });
    const l = await org.apiLogin(learner);
    const active = await seed.billing.course();
    const expired = await seed.billing.course();
    const soon = await seed.billing.course();
    await seed.billing.entitlement({ userId: learner.userId, courseId: active.courseId, startsInDays: -10, endsInDays: 30 });
    await seed.billing.entitlement({ userId: learner.userId, courseId: expired.courseId, startsInDays: -40, endsInDays: -1 });
    await seed.billing.entitlement({ userId: learner.userId, courseId: soon.courseId, startsInDays: -27, endsInDays: 3 });

    // the gate: new learning only while the plan is valid; an attempt begun while it was valid may be finished
    expect(await seed.billing.gate({ userId: learner.userId, courseId: active.courseId })).toMatchObject({ activeFor: true });
    expect(await seed.billing.gate({ userId: learner.userId, courseId: expired.courseId, attemptStartedDaysAgo: 10 })).toMatchObject({ activeFor: false, canContinueAttempt: true });
    expect(await seed.billing.gate({ userId: learner.userId, courseId: expired.courseId, attemptStartedDaysAgo: 60 })).toMatchObject({ activeFor: false, canContinueAttempt: false });
    const stranger = await seed.verifiedLearner({ email: uniqueEmail('f41-stranger') });
    expect(await seed.billing.gate({ userId: stranger.userId, courseId: active.courseId })).toMatchObject({ activeFor: false });

    // the history stays: the expired plan is still listed, as not active
    const mine = (await org.api(l.token, 'GET', '/api/me/entitlements')).body as Array<{ courseId: string; active: boolean }>;
    expect(mine).toHaveLength(3);
    expect(mine.find((e) => e.courseId === expired.courseId)?.active).toBe(false);
    expect(mine.find((e) => e.courseId === active.courseId)?.active).toBe(true);
    expect((await org.api((await org.apiLogin(stranger)).token, 'GET', '/api/me/entitlements')).body).toHaveLength(0);

    // the learner is told once when the plan is about to end and once when it has ended, however often the jobs run
    for (let i = 0; i < 2; i++) {
      await seed.billing.runJob('plan_expiry_reminder');
      await seed.billing.runJob('expire_entitlements');
    }
    const kinds = async () => ((await org.api(l.token, 'GET', '/api/me/notifications')).body.items ?? []).map((n: { type: string }) => n.type).sort();
    await expect.poll(kinds, { timeout: 40_000 }).toEqual(['PLAN_EXPIRED', 'PLAN_EXPIRING']);
    await new Promise((r) => setTimeout(r, 3_000)); // the jobs have run twice: a duplicate would have been made by now
    expect(await kinds()).toEqual(['PLAN_EXPIRED', 'PLAN_EXPIRING']);
  });

  test('F41 when the plan ends the lesson and a new exam are closed on the real screens, the old result still reads and the attempt under way is finished', async ({ page, request, auth, seed, org, uniqueEmail }) => {
    // two minutes of late-sync grace are waited out for real before an attempt is scored (the attempt finished after the plan ended is scored too)
    test.setTimeout(600_000);
    const s = await staffed(['TOEIC'], 'f41', { request, uniqueEmail, apiLogin: org.apiLogin });
    const mark = s.author.userId.slice(0, 6);
    const testVersionId = await publishedToeicTestVersion(s, `F41 exam ${mark}`);
    // a test the learner has no attempt under way in: opening the test of an attempt under way only resumes that attempt (by design)
    const newTestVersionId = await publishedToeicTestVersion(s, `F41 new exam ${mark}`);
    const course = await publishedCourse(s, 'FREE', {
      l1: await publishedLesson(s, 'TOEIC', `F41 lesson ${mark}`),
      test: await publishedToeicTestVersion(s, `F41 chapter test ${mark}`),
      l2: await publishedLesson(s, 'TOEIC', `F41 lesson two ${mark}`),
    }, `F41 course ${mark}`);
    const lessonId = course.chapters[0]!.lessons[0]!.id;

    const learner = await seed.verifiedLearner({ email: uniqueEmail('f41-screens') });
    const token = (await org.apiLogin(learner)).token;
    // the plan runs out 90 seconds from now: long enough to start two attempts over the API, short enough to be over before the first one is scored
    await seed.billing.entitlement({ userId: learner.userId, courseId: course.courseId, startsInDays: -10, endsInSeconds: 90 });
    expect(await seed.billing.gate({ userId: learner.userId, courseId: course.courseId })).toMatchObject({ activeFor: true });

    const sit = async () => {
      const started = await api(token, 'POST', '/api/exam/attempts', { body: { testVersionId } });
      expect(started.status, started.text).toBe(201);
      return started.body;
    };
    const answer = async (attemptId: string, view: any, value: string) => {
      await new Promise((r) => setTimeout(r, 1500));
      const answeredAt = new Date(Date.parse(view.serverNow) + 1000).toISOString();
      const put = await api(token, 'PUT', `/api/exam/attempts/${attemptId}/responses/${view.currentSection.groups[0].questions[0].questionId}`, { body: { answer: value, answeredAt } });
      expect(put.status, put.text).toBe(204);
    };
    // attempt 1 is sat and submitted (right on Part 1, wrong on Part 5); attempt 2 is started and left half-way
    const first = await sit();
    await answer(first.attemptId, first, 'A');
    const reading = await api(token, 'POST', `/api/exam/attempts/${first.attemptId}/sections/P1/finish`);
    expect(reading.status, reading.text).toBe(200);
    await answer(first.attemptId, reading.body, 'A');
    expect((await api(token, 'POST', `/api/exam/attempts/${first.attemptId}/submit`)).status).toBe(200);
    const second = await sit();
    await answer(second.attemptId, second, 'A');

    // the plan ends
    await poll('the plan to end', async () => ((await seed.billing.gate({ userId: learner.userId, courseId: course.courseId })).activeFor ? null : true), { timeoutMs: 120_000, intervalMs: 2_000 });
    await seed.billing.runJob('expire_entitlements');

    // the lesson and the map of the course say the learner needs a plan
    await auth.loginAs(page, learner);
    await page.goto(`/learn/lessons/${lessonId}`);
    await expect(page.getByText('Bạn cần có gói học cho khoá này.')).toBeVisible();
    await expect(page.getByText(`SECRET-LESSON-BODY of F41 lesson ${mark}`)).toHaveCount(0);
    await page.goto(`/learn/courses/${course.courseId}`);
    await expect(page.getByText('Bạn cần có gói học cho khoá này.')).toBeVisible();
    // a new exam is refused on the screen and by the server
    await page.goto(`/exam/start/${newTestVersionId}`);
    await expect(page.getByText('Bạn chưa có quyền làm đề này')).toBeVisible();
    const refused = await api(token, 'POST', '/api/exam/attempts', { body: { testVersionId: newTestVersionId } });
    expect(refused.status, refused.text).toBe(403);

    // the attempt that was under way when the plan ended is still finished in the exam room (first, while its section still has time) and then scored
    await page.goto(`/exam/attempts/${second.attemptId}`);
    await expect(page.getByRole('region', { name: 'Câu hỏi' })).toBeVisible();
    await finishSection(page);
    await expect(page.getByText('Phần 2/2')).toBeVisible();
    await finishSection(page);
    await expect(page.getByTestId('exam-result')).toHaveAttribute('data-status', 'SUBMITTED');
    await poll('the scores of the attempt that was finished after the plan ended', async () => ((await seed.grading.scores(second.attemptId)).graded ? true : null), { timeoutMs: 360_000, intervalMs: 3_000 });
    await page.goto(`/results/attempts/${second.attemptId}`);
    await expect(page.getByRole('heading', { level: 1, name: 'Kết quả của bạn' })).toBeVisible();
    await expect(page.getByTestId('skill-score').first()).toBeVisible();

    // the OLD result is still read once it is scored: the scores with their label, the key and the explanation
    await poll('the scores of the old attempt', async () => ((await seed.grading.scores(first.attemptId)).graded ? true : null), { timeoutMs: 360_000, intervalMs: 3_000 });
    await page.goto(`/results/attempts/${first.attemptId}`);
    await expect(page.getByRole('heading', { level: 1, name: 'Kết quả của bạn' })).toBeVisible();
    await expect(page.locator('[data-testid="skill-score"][data-skill="LISTENING"]')).toHaveAttribute('data-label', 'OFFICIAL');
    const questions = page.getByTestId('question-result');
    await expect(questions).toHaveCount(2);
    await expect(questions.filter({ has: page.getByText('Sai', { exact: true }) }).getByText(/Đáp án đúng: D/)).toBeVisible();
    await page.goto('/results/weakness');
    await expect(page.getByTestId('type-rate')).toHaveCount(2);
  });
});
