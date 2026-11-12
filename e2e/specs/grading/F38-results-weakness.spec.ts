// F38 — Kết quả + điểm yếu (spec §16.2): "Đáp án chỉ lộ sau khi nộp; gợi ý bài phương pháp theo dạng câu yếu".
//
// Through the real stack: an AUTHOR publishes a TOEIC test (a photo question and a sentence question, with explanations) and two courses, each with method lessons
// tagged with the Part 5 question type; the learner holds a plan for ONE of them. The learner answers Part 1 right and Part 5 wrong over the exam room's API.
//   - while the attempt is going, and after it is submitted but before the scoring ran, the result endpoint gives no answer key, explanation or transcript (409, then SCORING);
//   - once scored (after the late-sync grace) the result page shows the scores with their label, each question with the key and the explanation;
//   - the weakness page lists Part 5 at 0% and Part 1 at 100%, and recommends the method lesson of the course the learner holds, never the other course's;
//   - nobody else can read the attempt (404).
import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { staffed } from '../../fixtures/content';
import { publishedCourse, publishedLesson, publishedToeicTestVersion } from '../../fixtures/course';
import { api } from '../../fixtures/org';
import { poll } from '../../helpers/env';
import { seed } from '../../fixtures/seed';

async function axeClean(page: Page) {
  const r = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  expect(r.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

const SECRETS = ['because', 'Script of the recording', '"slots"', 'correctAnswer'];

test.describe('F38 results and weakness', () => {
  test('F38 the key shows only after the attempt was scored, the weak question type is recommended with a method lesson the learner may open', async ({ page, auth, request, org, uniqueEmail }) => {
    // two minutes of late-sync grace (e9x.exam.late-sync-grace) are waited out for real: the scoring job is queued for the end of it
    test.setTimeout(480_000);
    const s = await staffed(['TOEIC'], 'f38', { request, uniqueEmail, apiLogin: org.apiLogin });
    const mark = s.author.userId.slice(0, 6);
    const testVersionId = await publishedToeicTestVersion(s, `F38 exam ${mark}`);

    // the method lessons: the learner's course teaches "Gap method", the other course (no plan) teaches "Hidden method"; both are tagged with the Part 5 type
    const mine = await publishedCourse(s, 'FREE', {
      l1: await publishedLesson(s, 'TOEIC', `Gap method ${mark}`),
      test: await publishedToeicTestVersion(s, `F38 chapter test A ${mark}`),
      l2: await publishedLesson(s, 'TOEIC', `Gap practice ${mark}`),
    }, `F38 my course ${mark}`);
    await publishedCourse(s, 'FREE', {
      l1: await publishedLesson(s, 'TOEIC', `Hidden method ${mark}`),
      test: await publishedToeicTestVersion(s, `F38 chapter test B ${mark}`),
      l2: await publishedLesson(s, 'TOEIC', `Hidden practice ${mark}`),
    }, `F38 not mine ${mark}`);

    const learner = await seed.verifiedLearner({ email: uniqueEmail('f38-learner') });
    await seed.billing.entitlement({ userId: learner.userId, courseId: mine.courseId, endsInDays: 30 });
    const token = (await org.apiLogin(learner)).token;
    const stranger = await seed.verifiedLearner({ email: uniqueEmail('f38-stranger') });
    const strangerToken = (await org.apiLogin(stranger)).token;

    // sit the test: Part 1 right ("A"), Part 5 wrong ("A" where the key is D)
    const started = await api(token, 'POST', '/api/exam/attempts', { body: { testVersionId } });
    expect(started.status, started.text).toBe(201);
    const attemptId = started.body.attemptId as string;
    const questionOf = (view: any) => view.currentSection.groups[0].questions[0].questionId as string;
    const answer = async (view: any, value: string) => {
      await new Promise((r) => setTimeout(r, 1500));
      const answeredAt = new Date(Date.parse(view.serverNow) + 1000).toISOString();
      const put = await api(token, 'PUT', `/api/exam/attempts/${attemptId}/responses/${questionOf(view)}`, { body: { answer: value, answeredAt } });
      expect(put.status, put.text).toBe(204);
    };
    await answer(started.body, 'A');

    // still going: the endpoint gives nothing of the solution side, and it is not the stranger's to read
    const going = await api(token, 'GET', `/api/results/attempts/${attemptId}`);
    expect(going.status, going.text).toBe(409);
    for (const secret of SECRETS) expect(going.text).not.toContain(secret);
    expect((await api(strangerToken, 'GET', `/api/results/attempts/${attemptId}`)).status).toBe(404);

    const reading = await api(token, 'POST', `/api/exam/attempts/${attemptId}/sections/P1/finish`);
    expect(reading.status, reading.text).toBe(200);
    await answer(reading.body, 'A');
    const submitted = await api(token, 'POST', `/api/exam/attempts/${attemptId}/submit`);
    expect(submitted.status, submitted.text).toBe(200);

    // submitted, inside the grace: an answer can still arrive, so no key yet
    const scoring = await api(token, 'GET', `/api/results/attempts/${attemptId}`);
    expect(scoring.status, scoring.text).toBe(200);
    expect(scoring.body.state).toBe('SCORING');
    expect(scoring.body.questions).toEqual([]);
    for (const secret of SECRETS) expect(scoring.text).not.toContain(secret);

    await poll('the scores', async () => ((await seed.grading.scores(attemptId)).graded ? true : null), { timeoutMs: 360_000, intervalMs: 3_000 });

    // the result page: scores with their label, every question with the key and the explanation
    await auth.loginAs(page, learner);
    await page.goto(`/results/attempts/${attemptId}`);
    await expect(page.getByRole('heading', { level: 1, name: 'Kết quả của bạn' })).toBeVisible();
    const skills = page.getByTestId('skill-score');
    await expect(skills.filter({ has: page.getByText('LISTENING') })).toHaveAttribute('data-label', 'OFFICIAL');
    await expect(skills.filter({ has: page.getByText('READING') })).toHaveAttribute('data-label', 'OFFICIAL');
    const questions = page.getByTestId('question-result');
    await expect(questions).toHaveCount(2);
    await expect(questions.filter({ has: page.getByText('Đúng', { exact: true }) })).toHaveCount(1);
    const wrong = questions.filter({ has: page.getByText('Sai', { exact: true }) });
    await expect(wrong).toHaveCount(1);
    await expect(wrong.getByText('because')).toBeVisible();
    await expect(wrong.getByText(/Đáp án đúng: D/)).toBeVisible();
    await expect(page.getByText('Script of the recording, for the review page.')).toBeAttached();
    await axeClean(page);

    // the weakness page: Part 5 is the weak one, the method lesson of MY course is offered, the other course's is not
    await page.goto('/results/weakness');
    const rates = page.getByTestId('type-rate');
    await expect(rates).toHaveCount(2);
    await expect(rates.first()).toHaveAttribute('data-type', 'TOEIC_P5_INCOMPLETE_SENTENCE');
    await expect(rates.first().getByText('0%')).toBeVisible();
    await expect(rates.nth(1).getByText('100%')).toBeVisible();
    const rec = page.getByTestId('recommendation');
    await expect(rec).toHaveCount(1);
    await expect(rec.getByRole('link', { name: `Mở: Gap method ${mark}` })).toBeVisible();
    await expect(page.getByText(`Hidden method ${mark}`)).toHaveCount(0);
    await expect(page.getByText(`Hidden practice ${mark}`)).toHaveCount(0);
    await axeClean(page);
    await rec.getByRole('link', { name: `Mở: Gap method ${mark}` }).click();
    await expect(page).toHaveURL(/\/learn\/lessons\//);

    // the same numbers over the API, and nothing of them for somebody else
    const weak = await api(token, 'GET', '/api/results/weakness?exam=TOEIC');
    expect(weak.status, weak.text).toBe(200);
    expect(weak.body.recommendations.flatMap((r: any) => r.lessons.map((l: any) => l.title))).not.toContain(`Hidden method ${mark}`);
    const theirs = await api(strangerToken, 'GET', '/api/results/weakness?exam=TOEIC');
    expect(theirs.body.types).toEqual([]);
    expect(theirs.body.recommendations).toEqual([]);
    expect((await api(strangerToken, 'GET', `/api/results/attempts/${attemptId}`)).status).toBe(404);
  });
});
