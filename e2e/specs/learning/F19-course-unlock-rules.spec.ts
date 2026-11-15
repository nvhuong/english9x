// F19 — Dựng khoá + luật mở khoá (spec §16.2): "Tạo khoá (chương, bài, đề), luật STRICT/COMPLETION/FREE, mã khoá 6 ký tự".
//
// Through the real stack: an AUTHOR builds a course on the screen (a six-character code the server checks, chapters made of published lessons and tests),
// the course is published only by a review, and the real lesson gate (entitlement first, then the unlock rule over the learner's progress) answers for a
// learner as the rule says. The first test states the learner's progress through seed.course.gate; the second one walks the learner's own screens (the
// chapter map, the lesson page, the chapter test) so that the progress is written by real lesson completions and real chapter-test attempts, for STRICT,
// COMPLETION and FREE.
import { test, expect } from '../../fixtures';
import { staffed } from '../../fixtures/content';
import { publishedCourse, publishedLesson, publishedToeicTestVersion, type CourseVersion } from '../../fixtures/course';
import { seed } from '../../fixtures/seed';

test.describe('F19 course and unlock rules', () => {
  test('F19 the author builds a STRICT course on the screen (the code is six capitals), a review publishes it, and the gate follows the rule, the progress and the entitlement', async ({ page, request, auth, org, uniqueEmail }) => {
    test.setTimeout(300_000);
    const s = await staffed(['TOEIC'], 'f19', { request, uniqueEmail, apiLogin: org.apiLogin }); // English9x's own staff: the personal context
    const mark = s.author.userId.slice(0, 6);
    const l1 = await publishedLesson(s, 'TOEIC', `Verbs ${mark}`);
    const l2 = await publishedLesson(s, 'TOEIC', `Nouns ${mark}`);
    const test1 = await publishedToeicTestVersion(s, `Chapter test ${mark}`);

    await auth.loginAs(page, s.authorLogin);
    await page.goto('/cms/courses/new');
    await expect(page.getByRole('heading', { level: 1, name: 'Khoá học mới' })).toBeVisible();
    await page.getByRole('combobox', { name: 'Kỳ thi' }).click();
    await page.getByRole('option', { name: 'TOEIC', exact: true }).click();
    await page.getByLabel('Tên khoá học').fill(`Part 5 ${mark}`);
    await page.getByLabel('Mô tả').fill('Grammar for Part 5.');
    await page.getByLabel('Tác giả hiển thị cho học viên').fill('Ms Lan');
    await page.getByRole('combobox', { name: 'Luật mở khoá' }).click();
    await page.getByRole('option', { name: /^Nghiêm ngặt/ }).click();
    // chapter 1: a lesson and the chapter test
    await page.getByRole('button', { name: 'Thêm chương' }).click();
    const ch1 = page.getByRole('group', { name: 'Chương 1' });
    await ch1.getByLabel('Tên chương').fill('Verbs');
    await ch1.getByRole('combobox', { name: 'Thêm bài học hoặc đề' }).click();
    await page.getByRole('option', { name: new RegExp(`Verbs ${mark}`) }).click();
    await ch1.getByRole('combobox', { name: 'Thêm bài học hoặc đề' }).click();
    await page.getByRole('option', { name: new RegExp(`Chapter test ${mark}`) }).click();
    // chapter 2: a lesson
    await page.getByRole('button', { name: 'Thêm chương' }).click();
    const ch2 = page.getByRole('group', { name: 'Chương 2' });
    await ch2.getByLabel('Tên chương').fill('Nouns');
    await ch2.getByRole('combobox', { name: 'Thêm bài học hoặc đề' }).click();
    await page.getByRole('option', { name: new RegExp(`Nouns ${mark}`) }).click();

    // the code: a lower-case or short one is refused by the server, on the field; nothing is created
    await page.getByLabel('Mã khoá học').fill('ab-12');
    await page.getByRole('button', { name: 'Tạo khoá học' }).click();
    await expect(page.getByText(/exactly 6 characters/)).toBeVisible();
    await expect(page).toHaveURL(/\/cms\/courses\/new$/);
    await page.getByLabel('Mã khoá học').fill('f19' + mark.slice(0, 3).toLowerCase()); // typed in lower case: the screen turns it into capitals
    await page.getByRole('button', { name: 'Tạo khoá học' }).click();
    await expect(page).toHaveURL(/\/cms\/courses\/[0-9a-f-]{36}$/);
    const courseId = page.url().split('/').pop()!;
    const saved = (await org.api(s.author.token, 'GET', `/api/cms/courses/${courseId}`, { org: s.orgId })).body as CourseVersion & { unlockRule: string };
    expect(saved.code).toMatch(/^[A-Z0-9]{6}$/);
    expect(saved.unlockRule).toBe('STRICT');
    expect(saved.chapters.map((c) => [c.title, c.passThreshold, c.lessons.map((l) => l.kind)])).toEqual([
      ['Verbs', 0.7, ['LESSON', 'CHAPTER_TEST']],
      ['Nouns', 0.7, ['LESSON']],
    ]);
    expect(saved.chapters[0]!.lessons.map((l) => l.refId)).toEqual([l1, test1]);
    // another course cannot take the code
    const dup = await org.api(s.author.token, 'POST', '/api/cms/courses', {
      org: s.orgId,
      body: { examCode: 'TOEIC', code: saved.code, title: 'Again', unlockRule: 'FREE', chapters: [{ title: 'c', lessons: [{ kind: 'LESSON', refId: l1 }] }] },
    });
    expect(dup.status).toBe(409);
    expect(dup.body.type).toBe('urn:e9x:problem:course-code-taken');

    // submit; the reviewer's decision publishes it (the review screens are P2.9's)
    await page.getByRole('button', { name: 'Gửi duyệt' }).click();
    await expect(page.getByText('Đã gửi duyệt.')).toBeVisible();
    await seed.reviewVersion({ type: 'course', versionId: saved.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
    await page.reload();
    await expect(page.getByText('Đã xuất bản', { exact: true })).toBeVisible();

    // the gate. A learner with an entitlement; another without.
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f19-learner') });
    const stranger = await seed.verifiedLearner({ email: uniqueEmail('f19-stranger') });
    await seed.billing.entitlement({ userId: learner.userId, courseId: courseId, endsInDays: 30 });
    const ids = saved.chapters.flatMap((c) => c.lessons.map((l) => l.id)); // lesson 1, chapter test, lesson 2
    const gate = async (rule: { who?: typeof learner; lessonId: string; done?: Array<{ lessonId: string; score?: number | null }>; course?: string }) =>
      (await seed.course.gate({ userId: (rule.who ?? learner).userId, orgId: s.orgId, lessonId: rule.lessonId, completed: rule.done })).access;
    expect(await gate({ lessonId: ids[0]! }), 'the first lesson is always open').toBe('ALLOWED');
    expect(await gate({ lessonId: ids[1]! })).toBe('LOCKED');
    expect(await gate({ lessonId: ids[2]! })).toBe('LOCKED');
    expect(await gate({ lessonId: ids[1]!, done: [{ lessonId: ids[0]!, score: null }] })).toBe('ALLOWED');
    const failedTest = [{ lessonId: ids[0]!, score: null }, { lessonId: ids[1]!, score: 0.5 }];
    expect(await gate({ lessonId: ids[2]!, done: failedTest }), 'a chapter test under the pass mark keeps the next chapter shut').toBe('LOCKED');
    expect(await gate({ lessonId: ids[2]!, done: [{ lessonId: ids[0]!, score: null }, { lessonId: ids[1]!, score: 0.7 }] }), 'at the pass mark it opens').toBe('ALLOWED');
    // no entitlement beats the lock: the answer says "buy", never "finish the previous lesson"
    expect(await gate({ who: stranger, lessonId: ids[0]! }), 'not even the open first lesson').toBe('NOT_ENTITLED');
    expect(await gate({ who: stranger, lessonId: ids[2]! })).toBe('NOT_ENTITLED');

    // COMPLETION ignores the score; FREE opens everything
    const completion = await publishedCourse(s, 'COMPLETION', { l1, test: test1, l2 });
    const completionIds = completion.chapters.flatMap((c) => c.lessons.map((l) => l.id));
    await seed.billing.entitlement({ userId: learner.userId, courseId: completion.courseId, endsInDays: 30 });
    const done = (lessons: string[], score: number | null) => lessons.map((lessonId) => ({ lessonId, score }));
    expect(await gate({ lessonId: completionIds[2]! })).toBe('LOCKED');
    expect(await gate({ lessonId: completionIds[2]!, done: done(completionIds.slice(0, 2), 0.1) }), 'finished is enough, whatever the score').toBe('ALLOWED');
    const free = await publishedCourse(s, 'FREE', { l1, test: test1, l2 });
    await seed.billing.entitlement({ userId: learner.userId, courseId: free.courseId, endsInDays: 30 });
    for (const lesson of free.chapters.flatMap((c) => c.lessons)) expect(await gate({ lessonId: lesson.id })).toBe('ALLOWED');
  });
  test('F19 on the learner\'s own screens the rule opens the map as real lesson completions and chapter tests say: STRICT needs a pass, COMPLETION needs only the attempt, FREE is open, and no plan is shut out', async ({ page, request, auth, org, uniqueEmail }) => {
    test.setTimeout(420_000);
    const s = await staffed(['TOEIC'], 'f19-map', { request, uniqueEmail, apiLogin: org.apiLogin });
    const mark = s.author.userId.slice(0, 6);
    const l1 = await publishedLesson(s, 'TOEIC', `Verbs ${mark}`);
    const l2 = await publishedLesson(s, 'TOEIC', `Nouns ${mark}`);
    const chapterTest = await publishedToeicTestVersion(s, `Chapter test ${mark}`);
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f19-walk') });
    const stranger = await seed.verifiedLearner({ email: uniqueEmail('f19-walk-stranger') });
    const course = async (rule: 'STRICT' | 'COMPLETION' | 'FREE') => {
      const c = await publishedCourse(s, rule, { l1, test: chapterTest, l2 }, `${rule} course ${mark}`);
      await seed.billing.entitlement({ userId: learner.userId, courseId: c.courseId, endsInDays: 30 });
      return { ...c, ids: c.chapters.flatMap((ch) => ch.lessons.map((l) => l.id)) }; // lesson 1, chapter test, lesson 2
    };
    const strict = await course('STRICT');
    const completion = await course('COMPLETION');
    const free = await course('FREE');
    const titles = { l1: `Verbs ${mark}`, test: `Chapter test ${mark}`, l2: `Nouns ${mark}` };
    await auth.loginAs(page, learner);

    const map = (c: { courseId: string }) => page.goto(`/learn/courses/${c.courseId}`);
    const link = (title: string) => page.getByRole('link', { name: title });
    /** Finishes a lesson the way the learner does: opens it from the map and presses "Đánh dấu đã học xong". */
    const finishLesson = async (c: { courseId: string }, title: string) => {
      await map(c);
      await link(title).click();
      await page.getByRole('button', { name: 'Đánh dấu đã học xong' }).click();
      await expect(page.getByText('Đã học xong bài này')).toBeVisible();
    };
    /** Sits the chapter test from the map: `right` = the answers the key wants (photo A, sentence D), else every answer wrong (C). */
    const sitTest = async (c: { courseId: string }, right: boolean) => {
      await map(c);
      await link(titles.test).click();
      await page.getByRole('group', { name: 'Câu 1' }).getByRole('radio', { name: right ? /^A\./ : /^C\./ }).check();
      await page.getByRole('group', { name: 'Câu 2' }).getByRole('radio', { name: right ? /^D\./ : /^C\./ }).check();
      await page.getByRole('button', { name: 'Nộp bài' }).click();
      await expect(page.getByTestId('attempt-result')).toContainText(right ? 'Đạt' : 'Chưa đạt');
    };
    const locked = page.getByText('Đang khoá', { exact: true });

    // ---- STRICT: only the first lesson is open; the lesson opens the test; a failed test keeps chapter 2 shut; a pass opens it
    await map(strict);
    await expect(page.getByText('Đạt bài kiểm tra chương để học tiếp.')).toBeVisible();
    await expect(link(titles.l1)).toBeVisible();
    await expect(link(titles.test)).toHaveCount(0);
    await expect(link(titles.l2)).toHaveCount(0);
    await expect(locked).toHaveCount(2);
    await page.goto(`/learn/lessons/${strict.ids[2]}`);
    await expect(page.getByText('Hãy hoàn thành các bài trước.'), 'a locked lesson cannot be opened by its address either').toBeVisible();
    await page.goto(`/learn/chapter-tests/${strict.ids[1]}`);
    await expect(page.getByText('Hãy hoàn thành các bài trước.')).toBeVisible();
    await finishLesson(strict, titles.l1);
    await map(strict);
    await expect(link(titles.test)).toBeVisible();
    await expect(locked, 'only chapter 2 waits').toHaveCount(1);
    await sitTest(strict, false);
    await map(strict);
    await expect(page.getByText('Cao nhất 0%')).toBeVisible();
    await expect(link(titles.l2), 'a chapter test under the pass mark keeps the next chapter shut').toHaveCount(0);
    await expect(locked).toHaveCount(1);
    await sitTest(strict, true);
    await map(strict);
    await expect(page.getByText('Cao nhất 100%')).toBeVisible();
    await expect(link(titles.l2), 'at the pass mark it opens').toBeVisible();
    await expect(locked).toHaveCount(0);

    // ---- COMPLETION: the score is ignored, finishing is enough
    await map(completion);
    await expect(page.getByText('Học xong từng bài để học tiếp.')).toBeVisible();
    await expect(locked).toHaveCount(2);
    await finishLesson(completion, titles.l1);
    await sitTest(completion, false);
    await map(completion);
    await expect(page.getByText('Cao nhất 0%')).toBeVisible();
    await expect(link(titles.l2), 'a failed attempt still finishes the chapter test').toBeVisible();
    await expect(locked).toHaveCount(0);

    // ---- FREE: everything is open from the start, in any order
    await map(free);
    await expect(page.getByText('Mở bài nào cũng được, theo thứ tự tuỳ ý.')).toBeVisible();
    await expect(locked).toHaveCount(0);
    await expect(link(titles.l2)).toBeVisible();
    await link(titles.l2).click();
    await expect(page.getByRole('heading', { level: 1, name: titles.l2 })).toBeVisible();

    // ---- no plan: the answer says "buy", never "finish the previous lesson", for the open first lesson too
    const other = await (await page.context().browser()!.newContext()).newPage();
    await auth.loginAs(other, stranger);
    await other.goto(`/learn/courses/${strict.courseId}`);
    await expect(other.getByText('Bạn cần có gói học cho khoá này.')).toBeVisible();
    await other.goto(`/learn/lessons/${strict.ids[0]}`);
    await expect(other.getByText('Bạn cần có gói học cho khoá này.')).toBeVisible();
    await other.goto(`/learn/lessons/${strict.ids[2]}`);
    await expect(other.getByText('Bạn cần có gói học cho khoá này.')).toBeVisible();
  });
});
