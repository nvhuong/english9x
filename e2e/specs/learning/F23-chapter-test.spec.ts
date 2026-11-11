// F23 — Kiểm tra chương (spec §16.2): the test may be sat only as many times as the course allows, the score is kept as the best of the attempts, and a
// pass opens the next chapter (STRICT).
//
// Through the real stack: a STRICT course whose chapter test allows two attempts; the learner fails once on the screen, passes the second time, finds
// the best score on the result and on the map, and is refused a third attempt.
import { test, expect } from '../../fixtures';
import { staffed } from '../../fixtures/content';
import { publishedLesson, publishedToeicTestVersion, newCode } from '../../fixtures/course';
import { api } from '../../fixtures/org';
import { seed } from '../../fixtures/seed';

test.describe('F23 chapter test', () => {
  test('F23 two attempts at most, the best score is kept, a pass opens the next chapter', async ({ page, request, auth, org, uniqueEmail }) => {
    test.setTimeout(300_000);
    const s = await staffed(['TOEIC'], 'f23', { request, uniqueEmail, apiLogin: org.apiLogin });
    const mark = s.author.userId.slice(0, 6);
    const l1 = await publishedLesson(s, 'TOEIC', `Verbs ${mark}`);
    const l2 = await publishedLesson(s, 'TOEIC', `Nouns ${mark}`);
    const chapterTest = await publishedToeicTestVersion(s, `Chapter test ${mark}`);
    const course = await api(s.author.token, 'POST', '/api/cms/courses', {
      org: s.orgId,
      body: {
        examCode: 'TOEIC', code: newCode(), title: `Part 5 ${mark}`, description: 'd', authorDisplay: 'Ms Lan', unlockRule: 'STRICT',
        chapters: [
          { title: 'Chapter 1', passThreshold: 0.7, lessons: [{ kind: 'LESSON', refId: l1 }, { kind: 'CHAPTER_TEST', refId: chapterTest, maxAttempts: 2 }] },
          { title: 'Chapter 2', passThreshold: 0.7, lessons: [{ kind: 'LESSON', refId: l2 }] },
        ],
      },
    });
    expect(course.status, course.text).toBe(201);
    await seed.reviewVersion({ type: 'course', versionId: course.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
    const [lesson1, test1] = course.body.chapters[0].lessons.map((l: { id: string }) => l.id) as string[];

    const learner = await seed.verifiedLearner({ email: uniqueEmail('f23-learner') });
    await seed.billing.entitlement({ userId: learner.userId, courseId: course.body.courseId, endsInDays: 30 });
    const token = (await org.apiLogin(learner)).token;
    expect((await api(token, 'POST', `/api/learning/lessons/${lesson1}/complete`)).status).toBe(200);

    await auth.loginAs(page, learner);
    await page.goto(`/learn/chapter-tests/${test1}`);
    await expect(page.getByText('Lượt làm: 0/2.')).toBeVisible();
    // attempt 1: every answer wrong
    await page.getByRole('group', { name: 'Câu 1' }).getByRole('radio', { name: /^C\./ }).check();
    await page.getByRole('group', { name: 'Câu 2' }).getByRole('radio', { name: /^C\./ }).check();
    await page.getByRole('button', { name: 'Nộp bài' }).click();
    const result = page.getByTestId('attempt-result');
    await expect(result).toContainText('Chưa đạt');
    await expect(result).toContainText('Còn 1 lượt');
    // attempt 2: the right answers (photo A, sentence D)
    await page.reload();
    await expect(page.getByText('Lượt làm: 1/2.')).toBeVisible();
    await page.getByRole('group', { name: 'Câu 1' }).getByRole('radio', { name: /^A\./ }).check();
    await page.getByRole('group', { name: 'Câu 2' }).getByRole('radio', { name: /^D\./ }).check();
    await page.getByRole('button', { name: 'Nộp bài' }).click();
    await expect(result).toContainText('Đạt');
    await expect(result).toContainText('Điểm cao nhất 100%');
    // a third attempt is refused, on the screen and by the server; the best score stays
    await page.reload();
    await expect(page.getByText('Bạn đã dùng hết số lần làm bài kiểm tra này.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Nộp bài' })).toHaveCount(0);
    const third = await api(token, 'POST', `/api/learning/chapter-tests/${test1}/attempts`, { body: { answers: [] } });
    expect(third.status).toBe(409);
    // the pass opened chapter 2 and the map shows the best score
    await page.goto(`/learn/courses/${course.body.courseId}`);
    await expect(page.getByText('Cao nhất 100%')).toBeVisible();
    await expect(page.getByText('Đang khoá')).toHaveCount(0);
  });
});
