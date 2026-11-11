// F22 — Học bài & luyện tập (spec §16.2): the learner opens a lesson from the map, answers the practice question and sees the explanation at once,
// finishes the lesson (which opens the next step under the STRICT rule), and the study clock counts only the gaps the server allows.
//
// Through the real stack: an AUTHOR publishes a lesson that embeds a TOEIC Part 5 question (the answer key and the explanation live only on the server),
// a STRICT course is published by the real review, a learner with a plan walks the screens: /learn -> the chapter map -> the lesson.
import { test, expect } from '../../fixtures';
import { staffed, publishedQuestion } from '../../fixtures/content';
import { publishedLesson, publishedToeicTestVersion, newCode } from '../../fixtures/course';
import { api } from '../../fixtures/org';
import { seed } from '../../fixtures/seed';

test.describe('F22 lesson and practice', () => {
  test('F22 the learner answers a practice question and is shown the explanation at once, finishes the lesson, and the chapter test opens', async ({ page, request, auth, org, uniqueEmail }) => {
    test.setTimeout(300_000);
    const s = await staffed(['TOEIC'], 'f22', { request, uniqueEmail, apiLogin: org.apiLogin });
    const mark = s.author.userId.slice(0, 6);
    const q = await publishedQuestion(s, 'TOEIC_P5_INCOMPLETE_SENTENCE');
    const made = await api(s.author.token, 'POST', '/api/cms/strategy-lessons', {
      org: s.orgId,
      body: {
        examCode: 'TOEIC',
        title: `Verbs ${mark}`,
        blocks: [{ type: 'paragraph', text: `Read the sentence ${mark} carefully.` }, { type: 'embedded-question', questionVersionId: q.versionId }],
        questionTypeCodes: ['TOEIC_P5_INCOMPLETE_SENTENCE'],
      },
    });
    expect(made.status, made.text).toBe(201);
    await seed.reviewVersion({ type: 'strategy_lesson', versionId: made.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
    const l2 = await publishedLesson(s, 'TOEIC', `Nouns ${mark}`);
    const chapterTest = await publishedToeicTestVersion(s, `Chapter test ${mark}`);
    const course = await api(s.author.token, 'POST', '/api/cms/courses', {
      org: s.orgId,
      body: {
        examCode: 'TOEIC', code: newCode(), title: `Part 5 ${mark}`, description: 'd', authorDisplay: 'Ms Lan', unlockRule: 'STRICT',
        chapters: [
          { title: 'Chapter 1', passThreshold: 0.7, lessons: [{ kind: 'LESSON', refId: made.body.versionId }, { kind: 'CHAPTER_TEST', refId: chapterTest }] },
          { title: 'Chapter 2', passThreshold: 0.7, lessons: [{ kind: 'LESSON', refId: l2 }] },
        ],
      },
    });
    expect(course.status, course.text).toBe(201);
    await seed.reviewVersion({ type: 'course', versionId: course.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });

    const learner = await seed.verifiedLearner({ email: uniqueEmail('f22-learner') });
    await seed.billing.entitlement({ userId: learner.userId, courseId: course.body.courseId, endsInDays: 30 });
    const stranger = await seed.verifiedLearner({ email: uniqueEmail('f22-stranger') });
    const strangerToken = (await org.apiLogin(stranger)).token;
    const lessonId = course.body.chapters[0].lessons[0].id as string;
    expect((await api(strangerToken, 'GET', `/api/learning/lessons/${lessonId}`)).status, 'no plan, no lesson').toBe(403);

    await auth.loginAs(page, learner);
    await page.goto('/learn');
    await page.getByRole('link', { name: course.body.code }).click();
    // the map: the chapter test and the second chapter are locked until the lesson is done
    await expect(page.getByRole('heading', { level: 1, name: `Part 5 ${mark}` })).toBeVisible();
    await expect(page.getByText(`Chapter test ${mark}`).first()).toBeVisible();
    await expect(page.getByText('Đang khoá')).toHaveCount(2);
    await page.getByRole('link', { name: `Verbs ${mark}` }).click();

    // practice: a wrong answer shows the right one and the explanation immediately
    await expect(page.getByText(`Read the sentence ${mark} carefully.`)).toBeVisible();
    await page.getByRole('radio', { name: /^A\./ }).check();
    await page.getByRole('button', { name: 'Kiểm tra' }).click();
    await expect(page.getByText('Chưa đúng')).toBeVisible();
    await expect(page.getByText('Đáp án: D')).toBeVisible();
    await expect(page.getByText('because')).toBeVisible();
    await expect(page.getByText('Luyện tập: đúng 0/1 câu đã làm (1 câu trong bài)')).toBeVisible();

    // finish: the chapter test opens on the map
    await page.getByRole('button', { name: 'Đánh dấu đã học xong' }).click();
    await expect(page.getByText('Đã học xong bài này')).toBeVisible();
    await page.getByRole('link', { name: 'Bản đồ khoá học' }).click();
    await expect(page.getByText('Đã xong')).toHaveCount(1);
    await expect(page.getByText('Đang khoá')).toHaveCount(1); // only chapter 2 waits for the chapter test
    await expect(page.getByRole('link', { name: `Chapter test ${mark}` })).toBeVisible();

    // the study clock: the first signal of a day credits nothing (there is no gap yet); the idle-gap rule itself is HeartbeatIT.idleGapNotCredited
    const token = (await org.apiLogin(learner)).token;
    const first = await api(token, 'POST', '/api/learning/heartbeat', { body: { courseId: course.body.courseId, lessonId, timeZone: 'Asia/Ho_Chi_Minh' } });
    expect(first.status, first.text).toBe(200);
    expect(first.body.todaySeconds).toBe(0);
    expect(first.body.streakDays).toBeGreaterThanOrEqual(1); // the completed lesson already counted for the day
    // the public config the page paces itself by
    const cfg = await api(token, 'GET', '/api/config/public');
    expect(cfg.body.learningHeartbeatIntervalSeconds).toBe(30);
  });
});
