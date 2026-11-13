// F51 — Nội dung riêng của trung tâm (spec §14): a CENTER authors content with the ordinary CMS in its own context. A PUBLISHED course that was not
// shared is the centre's own: its staff see it (CMS list and by code); another centre and a B2C learner see it nowhere (public catalogue, by code, CMS).
import { test, expect } from '../../fixtures';
import { staffed } from '../../fixtures/content';
import { publishedCourse, publishedLesson, publishedToeicTestVersion } from '../../fixtures/course';

test.describe('F51 centre private content', () => {
  test('F51 a centre sees its private course in its own context; another centre and a B2C learner cannot reach it', async ({ page, request, seed, auth, org, uniqueEmail }) => {
    test.setTimeout(300_000);
    const mark = Math.random().toString(36).slice(2, 8);
    const a = await staffed(['TOEIC'], 'f51-a', { request, uniqueEmail, apiLogin: org.apiLogin }, { inOrganisation: true });
    const l1 = await publishedLesson(a, 'TOEIC', `Riêng một ${mark}`);
    const l2 = await publishedLesson(a, 'TOEIC', `Riêng hai ${mark}`);
    const t1 = await publishedToeicTestVersion(a, `Bài kiểm tra riêng ${mark}`);
    const title = `Khoá riêng F51 ${mark}`;
    const course = await publishedCourse(a, 'FREE', { l1, test: t1, l2 }, title);
    const b = await staffed(['TOEIC'], 'f51-b', { request, uniqueEmail, apiLogin: org.apiLogin }, { inOrganisation: true });
    const b2c = await seed.verifiedLearner({ email: uniqueEmail('f51-b2c') });
    const b2cLogin = await org.apiLogin(b2c);
    const byCode = `/api/catalog/courses/${course.code}`;
    const listCodes = async (token: string, ctx: string | null) => ((await org.api(token, 'GET', '/api/catalog/courses', { org: ctx })).body as Array<{ code: string }>).map((c) => c.code);

    // the centre's own staff reach it by code and through the CMS, in the centre's context
    expect((await org.api(a.author.token, 'GET', byCode, { org: a.orgId })).status).toBe(200);
    const own = await org.api(a.author.token, 'GET', `/api/cms/courses/${course.courseId}`, { org: a.orgId });
    expect(own.status, own.text).toBe(200);
    expect(own.body.sharedStatus).toBe('NONE');

    // and on the real CMS page, under the centre's context
    await auth.loginAs(page, a.authorLogin);
    await org.switchContext(page, a.orgName!);
    await page.goto('/cms/courses');
    await expect(page.getByRole('link', { name: title })).toBeVisible();
    await page.getByRole('link', { name: title }).click();
    await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();

    // another centre, and a B2C learner, never do: not by code, not in the catalogue, not in the CMS (backend, not the screen, says so)
    for (const [who, token, ctx] of [['another centre', b.author.token, b.orgId], ['a B2C learner', b2cLogin.token, null]] as const) {
      expect((await org.api(token, 'GET', byCode, { org: ctx })).status, `${who} by code`).toBe(404);
      expect(await listCodes(token, ctx), `${who} catalogue`).not.toContain(course.code);
    }
    expect([403, 404]).toContain((await org.api(b.author.token, 'GET', `/api/cms/courses/${course.courseId}`, { org: b.orgId })).status);
    expect([403, 404]).toContain((await org.api(b2cLogin.token, 'GET', `/api/cms/courses/${course.courseId}`)).status);
    // another centre's list of CMS courses does not carry it
    const theirs = await org.api(b.author.token, 'GET', '/api/cms/courses', { org: b.orgId });
    expect(JSON.stringify(theirs.body)).not.toContain(course.code);
    // not even the owner's public catalogue carries it: private content stays in the centre's tools
    expect(await listCodes(a.author.token, a.orgId)).not.toContain(course.code);
  });
});
