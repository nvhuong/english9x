// F20 — Danh mục cho khách (spec §16.2): "Khách xem danh mục khoá học (mô tả, mục lục, tác giả), không xem được nội dung bài".
//
// Through the real stack, with nobody signed in: English9x's PUBLISHED course is listed and has an outline page (chapters, lesson titles, author); the page and the
// API carry nothing of what is inside a lesson (the text of its blocks, the file it plays); a course of an organisation that was not shared, and a draft, are not there.
import { test, expect } from '../../fixtures';
import { staffed } from '../../fixtures/content';
import { publishedCourse, publishedLesson, publishedToeicTestVersion } from '../../fixtures/course';
import { api } from '../../fixtures/org';
import { env } from '../../helpers/env';

test.describe('F20 guest catalog', () => {
  test('F20 a guest browses the catalog and the outline of a course, and sees nothing of the lessons themselves; a private course of an organisation is not there', async ({ page, request, org, uniqueEmail }) => {
    test.setTimeout(300_000);
    const mark = Math.random().toString(36).slice(2, 8);
    // English9x's own staff publish a course
    const e9 = await staffed(['TOEIC'], 'f20-e9', { request, uniqueEmail, apiLogin: org.apiLogin });
    const l1 = await publishedLesson(e9, 'TOEIC', `Verbs ${mark}`);
    const l2 = await publishedLesson(e9, 'TOEIC', `Nouns ${mark}`);
    const t1 = await publishedToeicTestVersion(e9, `Test ${mark}`);
    const course = await publishedCourse(e9, 'STRICT', { l1, test: t1, l2 }, `Part 5 ${mark}`);
    // an organisation publishes its own course, which is not shared
    const center = await staffed(['TOEIC'], 'f20-org', { request, uniqueEmail, apiLogin: org.apiLogin }, { inOrganisation: true });
    const c1 = await publishedLesson(center, 'TOEIC', `Private ${mark}`);
    const c2 = await publishedLesson(center, 'TOEIC', `Private two ${mark}`);
    const ct = await publishedToeicTestVersion(center, `Private test ${mark}`);
    const privateCourse = await publishedCourse(center, 'FREE', { l1: c1, test: ct, l2: c2 }, `Private course ${mark}`);

    // nobody signed in: the catalog lists English9x's course, by exam
    await page.goto('/courses');
    await expect(page.getByRole('heading', { level: 1, name: 'Khoá học' })).toBeVisible();
    const card = page.getByRole('listitem').filter({ hasText: `Part 5 ${mark}` });
    await expect(card).toBeVisible();
    await expect(card).toContainText('Ms Lan');
    await expect(card).toContainText('What this course teaches.');
    await expect(page.getByText(`Private course ${mark}`), 'an organisation\'s course is not in the public catalog').toHaveCount(0);
    await page.getByRole('combobox', { name: 'Kỳ thi' }).click();
    await page.getByRole('option', { name: 'IELTS', exact: true }).click();
    await expect(page.getByText(`Part 5 ${mark}`)).toHaveCount(0);
    await page.getByRole('combobox', { name: 'Kỳ thi' }).click();
    await page.getByRole('option', { name: 'TOEIC', exact: true }).click();

    // the outline: chapters and lesson titles, author; no way into a lesson
    await card.getByRole('link', { name: `Part 5 ${mark}` }).click();
    await expect(page).toHaveURL(new RegExp(`/courses/${course.code}$`));
    await expect(page.getByRole('heading', { level: 1, name: `Part 5 ${mark}` })).toBeVisible();
    await expect(page.getByText('Chương 1: Chapter 1')).toBeVisible();
    await expect(page.getByText(`Verbs ${mark}`)).toBeVisible();
    await expect(page.getByText(`Test ${mark}`)).toBeVisible();
    await expect(page.getByText('Chương 2: Chapter 2')).toBeVisible();
    await expect(page.getByText(`Nouns ${mark}`)).toBeVisible();
    await expect(page.getByText('Ms Lan').first()).toBeVisible();
    await expect(page.getByText(/SECRET-LESSON-BODY/)).toHaveCount(0);
    await expect(page.locator('audio, video')).toHaveCount(0);

    // the API says the same, and no more
    const raw = await request.get(`${env.baseURL}/api/catalog/courses/${course.code}`);
    expect(raw.status()).toBe(200);
    const text = await raw.text();
    for (const forbidden of ['SECRET-LESSON-BODY', e9.audioId, 'blocks', 'refId', 'answerKey']) expect(text, forbidden).not.toContain(forbidden);
    const list = await (await request.get(`${env.baseURL}/api/catalog/courses?exam=TOEIC`)).json() as Array<{ code: string; chapters: number; lessons: number }>;
    expect(list.find((c) => c.code === course.code)).toMatchObject({ chapters: 2, lessons: 3 });
    // not found: a code nobody has, and the code of an organisation's course that was not shared
    expect((await request.get(`${env.baseURL}/api/catalog/courses/NOSUCH`)).status()).toBe(404);
    expect((await request.get(`${env.baseURL}/api/catalog/courses/${privateCourse.code}`)).status()).toBe(404);
    await page.goto(`/courses/${privateCourse.code}`);
    await expect(page.getByText('Không tìm thấy khoá học')).toBeVisible();
    // a draft is not there either
    const draft = await api(e9.author.token, 'POST', '/api/cms/courses', {
      org: null,
      body: { examCode: 'TOEIC', code: `D${mark.slice(0, 5).toUpperCase().padEnd(5, '0')}`, title: 'Draft only', unlockRule: 'FREE', chapters: [{ title: 'c', lessons: [{ kind: 'LESSON', refId: l1 }] }] },
    });
    expect(draft.status, draft.text).toBe(201);
    expect((await request.get(`${env.baseURL}/api/catalog/courses/${draft.body.code}`)).status()).toBe(404);
    // the organisation's own person reads the private course by its code
    expect((await api(center.author.token, 'GET', `/api/catalog/courses/${privateCourse.code}`, { org: center.orgId })).status).toBe(200);
  });
});
