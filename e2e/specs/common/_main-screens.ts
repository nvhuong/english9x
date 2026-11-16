import { expect, type APIRequestContext } from '@playwright/test';
import { staffed } from '../../fixtures/content';
import { publishedCourse, publishedLesson, publishedToeicTestVersion } from '../../fixtures/course';
import { api, apiLogin } from '../../fixtures/org';
import { submitQuestion } from '../../fixtures/review';
import { seed } from '../../fixtures/seed';
import { mailpit } from '../../helpers/mailpit';
import { poll } from '../../helpers/env';

/**
 * The data behind the 8 main screens that F58 scans, made ONCE for the whole group through the real stack (real CMS and review, real exam API, real
 * scoring job, real order API); nothing is a mock of the product. Every person here is unique to the call.
 *
 *   01 catalogue      the course published below is listed (a guest sees it)
 *   02 chapter map    `learner` holds a plan for that course
 *   03 lesson         the first lesson of the course
 *   04 exam Reading   `reading`: an IELTS attempt whose Listening is finished, so Reading is the current section
 *   05 exam Speaking  `speaking`: an IELTS attempt in its Speaking section
 *   06 result         `result`: a TOEIC attempt, submitted, and scored (the late-sync grace is waited out for real)
 *   07 CMS queue      `reviewer` reads the queue with a question an author submitted
 *   08 admin orders   `admin` reads the queue with an order a learner placed
 */
export interface Person { email: string; password: string; userId: string }
export interface MainScreensWorld {
  course: { courseId: string; title: string; lessonId: string; lessonTitle: string };
  learner: Person;
  reading: { learner: Person; attemptId: string; title: string };
  speaking: { learner: Person; attemptId: string; title: string };
  result: { learner: Person; attemptId: string };
  reviewer: Person;
  admin: Person;
  order: { code: string };
}

const uniqueEmail = (label: string) => mailpit.uniqueEmail(label);

export async function buildMainScreensWorld(request: APIRequestContext): Promise<MainScreensWorld> {
  const mark = Math.random().toString(36).slice(2, 8);
  const s = await staffed(['TOEIC', 'IELTS'], 'f58-main', { request, uniqueEmail, apiLogin });

  // 01-03: an English9x course, published through the real review, and a learner with a plan for it
  const lessonTitle = `Verbs ${mark}`;
  const courseTitle = `Part 5 ${mark}`;
  const course = await publishedCourse(s, 'STRICT', {
    l1: await publishedLesson(s, 'TOEIC', lessonTitle),
    test: await publishedToeicTestVersion(s, `F58 chapter test ${mark}`),
    l2: await publishedLesson(s, 'TOEIC', `Nouns ${mark}`),
  }, courseTitle);
  const learner = await seed.verifiedLearner({ email: uniqueEmail('f58-learner'), displayName: 'Học viên Axe' });
  await seed.billing.entitlement({ userId: learner.userId, courseId: course.courseId, endsInDays: 30 });

  // 06: a TOEIC attempt, Part 1 right and Part 5 wrong, scored (the scoring job runs after the late-sync grace, so this is first: it takes minutes)
  const testVersionId = await publishedToeicTestVersion(s, `F58 result ${mark}`);
  const resulter = await seed.verifiedLearner({ email: uniqueEmail('f58-result'), displayName: 'Học viên Kết quả' });
  await seed.billing.entitlement({ userId: resulter.userId, courseId: course.courseId, endsInDays: 30 });
  const token = (await apiLogin(resulter)).token;
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
  const part5 = await api(token, 'POST', `/api/exam/attempts/${attemptId}/sections/P1/finish`);
  expect(part5.status, part5.text).toBe(200);
  await answer(part5.body, 'A');
  const submitted = await api(token, 'POST', `/api/exam/attempts/${attemptId}/submit`);
  expect(submitted.status, submitted.text).toBe(200);
  await poll('the scores', async () => ((await seed.grading.scores(attemptId)).graded ? true : null), { timeoutMs: 360_000, intervalMs: 3_000 });

  // 07: a question an author submitted waits in the queue of the reviewer (English9x's own staff, personal context)
  await submitQuestion({ ...s.authorLogin, userId: s.author.userId }, s.orgId, `Câu hỏi chờ duyệt ${mark}?`);
  const reviewer = { ...s.reviewerLogin, userId: s.reviewer.userId };

  // 08: an order waits for the administrator
  const admin = await seed.sysAdmin({ email: uniqueEmail('f58-admin'), displayName: 'Quản Trị Axe' });
  const adminToken = (await apiLogin(admin)).token;
  const account = await api(adminToken, 'PUT', '/api/admin/payment-account', { body: { bankBin: '970436', accountNo: `9${Date.now().toString().slice(-9)}`, accountName: 'CONG TY ENGLISH9X' } });
  expect(account.status, account.text).toBeLessThan(300);
  const plan = await api(adminToken, 'POST', '/api/admin/plans', { body: { name: `Gói F58 ${mark}`, priceVnd: 499000, months: 3, maxCourses: 1, active: true } });
  expect(plan.status, plan.text).toBe(201);
  const buyer = await seed.verifiedLearner({ email: uniqueEmail('f58-buyer'), displayName: 'Người Mua Axe' });
  const billingCourse = await seed.billing.course();
  const placed = await api((await apiLogin(buyer)).token, 'POST', '/api/orders', { body: { planId: plan.body.id, courseIds: [billingCourse.courseId] } });
  expect(placed.status, placed.text).toBe(201);

  // 04, 05: IELTS attempts, started last so that no section timer runs out during the minutes above
  const ielts = await seed.publishedIeltsTest({ variant: 'AC' });
  const sit = async (finish: string[]) => {
    const who = await seed.learnerWithPlan({ courses: 1 });
    const t = (await apiLogin(who)).token;
    const begun = await api(t, 'POST', '/api/exam/attempts', { body: { testVersionId: ielts.testVersionId } });
    expect(begun.status, begun.text).toBe(201);
    for (const section of finish) {
      const done = await api(t, 'POST', `/api/exam/attempts/${begun.body.attemptId}/sections/${section}/finish`);
      expect(done.status, done.text).toBe(200);
    }
    return { learner: who, attemptId: begun.body.attemptId as string, title: ielts.title };
  };
  const reading = await sit(['LISTENING']);
  const speaking = await sit(['LISTENING', 'READING', 'WRITING']);

  return {
    course: { courseId: course.courseId, title: courseTitle, lessonId: course.chapters[0]!.lessons[0]!.id, lessonTitle },
    learner,
    reading,
    speaking,
    result: { learner: resulter, attemptId },
    reviewer,
    admin,
    order: { code: placed.body.orderCode as string },
  };
}
