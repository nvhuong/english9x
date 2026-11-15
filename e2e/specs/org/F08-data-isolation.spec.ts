// F08 — Cách ly dữ liệu (spec §16.2): "Người tổ chức A mở URL lớp/khoá riêng của tổ chức B → 404".
//
// Completed through the real class (P6.2) and course (P3.1, P6.5) pages and endpoints: a person of A (ORG_ADMIN + AUTHOR) opens B's class URL and
// B's private course URLs in the browser and over HTTP and gets 404 with none of B's data; B's own admin gets 200 on the same URLs (so the 404 is
// isolation, not breakage). The organisation-owned pages (members, roles, invitations) keep their own checks below, and a SYS_ADMIN, who is
// allowed, sees that the data is there. The backend leak sweep (RlsLeakSweepIT) also covers every GET with an id.
import { test, expect } from '../../fixtures';
import { staffed } from '../../fixtures/content';
import { publishedCourse, publishedLesson, publishedToeicTestVersion } from '../../fixtures/course';

test.describe('F08 data isolation', () => {
  test('F08 a person of organisation A opening organisation B\'s URLs gets "not found" and none of B\'s data', async ({ page, seed, auth, org, uniqueEmail }) => {
    const suffix = Date.now().toString(36);
    const a = await seed.organization({ name: `Trung tâm A ${suffix}`, type: 'CENTER' });
    const b = await seed.organization({ name: `Trung tâm B ${suffix}`, type: 'CENTER' });
    const adminA = await seed.verifiedLearner({ email: uniqueEmail('f08-admin-a'), displayName: 'Quản trị viên A' });
    const adminB = await seed.verifiedLearner({ email: uniqueEmail('f08-admin-b'), displayName: 'Quản trị viên B' });
    const secretB = await seed.verifiedLearner({ email: uniqueEmail('f08-secret-b'), displayName: `Học viên Bí Mật B ${suffix}` });
    await seed.member({ orgId: a.orgId, userId: adminA.userId, roles: [{ role: 'ORG_ADMIN' }] });
    await seed.member({ orgId: b.orgId, userId: adminB.userId, roles: [{ role: 'ORG_ADMIN' }] });
    await seed.member({ orgId: b.orgId, userId: secretB.userId, roles: [{ role: 'TEACHER', examCode: 'IELTS' }] });

    // the browser: A's admin, in A, opens B's members page by its URL
    await auth.loginAs(page, adminA);
    await org.switchContext(page, `Trung tâm A ${suffix}`);
    const denied = page.waitForResponse((r) => r.request().method() === 'GET' && new URL(r.url()).pathname === `/api/orgs/${b.orgId}/role-grants`);
    await page.goto(`/org/${b.orgId}/members`);
    expect((await denied).status()).toBe(404);
    await expect(page.getByRole('heading', { level: 1, name: 'Không tìm thấy trang' })).toBeVisible();
    const html = await page.content();
    expect(html).not.toContain(secretB.email);
    expect(html).not.toContain('Học viên Bí Mật B');

    // the server, asked directly
    const { token } = await org.apiLogin(adminA);
    const asA = (method: string, path: string, body?: unknown, ctx: string | null = a.orgId) => org.api(token, method, path, { org: ctx, body });
    const probes = [
      await asA('GET', `/api/orgs/${b.orgId}/role-grants`),
      await asA('GET', `/api/orgs/${b.orgId}/invitations`),
      await asA('PUT', `/api/orgs/${b.orgId}/role-grants`, { userId: secretB.userId, roles: [{ role: 'ORG_ADMIN' }] }),
      await asA('POST', `/api/orgs/${b.orgId}/invitations`, { email: 'x@example.test', roles: [{ role: 'LEARNER' }] }),
    ];
    for (const r of probes) {
      expect(r.status).toBe(404);
      expect(r.text).not.toContain(secretB.email);
    }
    // ...naming B as the context is refused outright (A's admin is not a member), and so is leaving the context out
    expect((await asA('GET', `/api/orgs/${b.orgId}/role-grants`, undefined, b.orgId)).status).toBe(403);
    expect((await asA('GET', `/api/orgs/${b.orgId}/role-grants`, undefined, null)).status).toBe(403);
    expect((await asA('GET', '/api/admin/organizations', undefined, a.orgId)).status).toBe(403);
    // nothing changed in B
    const bAdminToken = (await org.apiLogin(adminB)).token;
    const members = (await org.api(bAdminToken, 'GET', `/api/orgs/${b.orgId}/role-grants`, { org: b.orgId })).body.members;
    expect(members.find((m: { userId: string }) => m.userId === secretB.userId).roles).toEqual([{ role: 'TEACHER', examCode: 'IELTS' }]);
    // A's own page works for A (the positive control that "not found" above is isolation, not breakage)
    expect((await asA('GET', `/api/orgs/${a.orgId}/role-grants`)).status).toBe(200);
  });

  test('F08 organisation A opening the class and the private course of organisation B gets "not found" in the browser and over HTTP', async ({ page, request, seed, auth, org, uniqueEmail }) => {
    test.setTimeout(300_000);
    const mark = Math.random().toString(36).slice(2, 8);
    const deps = { request, uniqueEmail, apiLogin: org.apiLogin };
    // B: a centre with a teacher, an ORG_ADMIN, a class and a private published course
    const b = await staffed(['TOEIC'], 'f08-b', deps, { inOrganisation: true });
    const bCourseTitle = `Khoá riêng của B ${mark}`;
    const bClassName = `Lớp bí mật của B ${mark}`;
    const l1 = await publishedLesson(b, 'TOEIC', `Bài B ${mark}`);
    const l2 = await publishedLesson(b, 'TOEIC', `Bài hai B ${mark}`);
    const t1 = await publishedToeicTestVersion(b, `Kiểm tra B ${mark}`);
    const bCourse = await publishedCourse(b, 'FREE', { l1, test: t1, l2 }, bCourseTitle);
    const adminB = await seed.verifiedLearner({ email: uniqueEmail('f08-class-admin-b') });
    const teacherB = await seed.verifiedLearner({ email: uniqueEmail('f08-class-teacher-b') });
    await seed.member({ orgId: b.orgId!, userId: adminB.userId, roles: [{ role: 'ORG_ADMIN' }] });
    await seed.member({ orgId: b.orgId!, userId: teacherB.userId, roles: [{ role: 'TEACHER', examCode: 'TOEIC' }] });
    const bAdminToken = (await org.apiLogin(adminB)).token;
    const madeClass = await org.api(bAdminToken, 'POST', `/api/orgs/${b.orgId}/classes`, { org: b.orgId, body: { name: bClassName, teacherId: teacherB.userId } });
    expect(madeClass.status, madeClass.text).toBe(201);
    const bClassId = madeClass.body.id as string;
    // A: a centre whose admin also authors there
    const a = await seed.organization({ type: 'CENTER' });
    const personA = await seed.verifiedLearner({ email: uniqueEmail('f08-class-a') });
    await seed.member({ orgId: a.orgId, userId: personA.userId, roles: [{ role: 'ORG_ADMIN' }, { role: 'AUTHOR', examCode: 'TOEIC' }] });
    const outsider = await seed.verifiedLearner({ email: uniqueEmail('f08-outsider') });

    // positive control: B's own admin and author reach all of it (so the 404s below are isolation)
    expect((await org.api(bAdminToken, 'GET', `/api/orgs/${b.orgId}/classes/${bClassId}`, { org: b.orgId })).status).toBe(200);
    expect((await org.api(b.author.token, 'GET', `/api/cms/courses/${bCourse.courseId}`, { org: b.orgId })).status).toBe(200);
    expect((await org.api(b.author.token, 'GET', `/api/catalog/courses/${bCourse.code}`, { org: b.orgId })).status).toBe(200);

    // the browser: A's person, in A's context, opens B's URLs
    await auth.loginAs(page, personA);
    await org.switchContext(page, a.name);
    const classResp = page.waitForResponse((r) => r.request().method() === 'GET' && new URL(r.url()).pathname === `/api/orgs/${a.orgId}/classes/${bClassId}`);
    await page.goto(`/org/classes/${bClassId}`);
    expect((await classResp).status()).toBe(404);
    await expect(page.getByRole('heading', { level: 1, name: 'Không tìm thấy trang' })).toBeVisible();
    const courseResp = page.waitForResponse((r) => r.request().method() === 'GET' && new URL(r.url()).pathname === `/api/cms/courses/${bCourse.courseId}`);
    await page.goto(`/cms/courses/${bCourse.courseId}`);
    expect((await courseResp).status()).toBe(404);
    await expect(page.getByText('Không tìm thấy khoá học', { exact: true })).toBeVisible();
    const outlineResp = page.waitForResponse((r) => r.request().method() === 'GET' && new URL(r.url()).pathname === `/api/catalog/courses/${bCourse.code}`);
    await page.goto(`/courses/${bCourse.code}`);
    expect((await outlineResp).status()).toBe(404);
    await expect(page.getByText('Không tìm thấy khoá học', { exact: true })).toBeVisible();
    const html = await page.content();
    for (const secret of [bClassName, bCourseTitle, `SECRET-LESSON-BODY of Bài B ${mark}`]) expect(html).not.toContain(secret);

    // HTTP: every way in is 404 (the id is not A's), or 403 where the context itself is refused (F45/F08 convention, nothing is looked up)
    const pa = (await org.apiLogin(personA)).token;
    const out = (await org.apiLogin(outsider)).token;
    const asA = (m: string, path: string, body?: unknown) => org.api(pa, m, path, { org: a.orgId, body });
    const classProbes = [
      await asA('GET', `/api/orgs/${b.orgId}/classes/${bClassId}`),
      await asA('GET', `/api/orgs/${b.orgId}/classes/${bClassId}/members`),
      await asA('GET', `/api/orgs/${b.orgId}/classes/${bClassId}/assignments`),
      await asA('POST', `/api/orgs/${b.orgId}/classes/${bClassId}/members`, { userIds: [personA.userId] }),
      await asA('GET', `/api/orgs/${a.orgId}/classes/${bClassId}`), // B's class id under A's own path
      await asA('GET', `/api/orgs/${a.orgId}/classes/${bClassId}/members`),
    ];
    for (const r of classProbes) {
      expect(r.status, r.text).toBe(404);
      expect(r.text).not.toContain(bClassName);
    }
    expect((await asA('GET', `/api/orgs/${b.orgId}/classes`)).status, 'B\'s class list in A\'s context').toBe(404);
    expect((await org.api(pa, 'GET', `/api/orgs/${b.orgId}/classes/${bClassId}`, { org: b.orgId })).status, 'naming B as the context without being in it').toBe(403);
    expect((await org.api(pa, 'GET', `/api/orgs/${b.orgId}/classes/${bClassId}`, { org: null })).status, 'no context at all').toBe(403);
    expect((await org.api(out, 'GET', `/api/orgs/${b.orgId}/classes/${bClassId}`, { org: null })).status, 'a person of no organisation').toBe(403);

    const courseProbes = [
      await asA('GET', `/api/cms/courses/${bCourse.courseId}`),
      await asA('GET', `/api/cms/courses/${bCourse.courseId}/versions`),
      await asA('POST', `/api/cms/courses/${bCourse.courseId}/submit`),
      await asA('GET', `/api/catalog/courses/${bCourse.code}`),
      await org.api(pa, 'GET', `/api/catalog/courses/${bCourse.code}`),
      await org.api(out, 'GET', `/api/catalog/courses/${bCourse.code}`),
      await org.api(out, 'GET', `/api/cms/courses/${bCourse.courseId}`),
    ];
    for (const r of courseProbes) expect(r.status, r.text).toBe(404);
    for (const token of [pa, out]) {
      const list = await org.api(token, 'GET', '/api/catalog/courses', { org: token === pa ? a.orgId : null });
      expect(list.text).not.toContain(bCourse.code);
    }
    const cmsList = await asA('GET', '/api/cms/courses');
    expect(cmsList.text).not.toContain(bCourse.code);
    // nothing changed in B
    expect((await org.api(bAdminToken, 'GET', `/api/orgs/${b.orgId}/classes/${bClassId}/members`, { org: b.orgId })).body).toEqual([]);
  });

  test('F08 a plain member of an organisation has no admin pages, and neither has anyone without the role', async ({ page, seed, auth, org, uniqueEmail }) => {
    const a = await seed.organization({ type: 'CENTER' });
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f08-learner') });
    await seed.member({ orgId: a.orgId, userId: learner.userId, roles: [{ role: 'LEARNER' }] });
    await auth.loginAs(page, learner);
    await page.goto('/org/members');
    await expect(page.getByRole('heading', { level: 1, name: 'Bạn chưa có quyền xem trang này' })).toBeVisible();
    await page.goto('/admin/organizations');
    await expect(page.getByRole('heading', { level: 1, name: 'Bạn chưa có quyền xem trang này' })).toBeVisible();
    const { token } = await org.apiLogin(learner);
    expect((await org.api(token, 'GET', `/api/orgs/${a.orgId}/role-grants`, { org: a.orgId })).status).toBe(403);
    expect((await org.api(token, 'GET', `/api/orgs/${a.orgId}/invitations`, { org: a.orgId })).status).toBe(403);
    expect((await org.api(token, 'PUT', `/api/orgs/${a.orgId}/role-grants`, { org: a.orgId, body: { userId: learner.userId, roles: [{ role: 'ORG_ADMIN' }] } })).status).toBe(403);
    // no self-promotion happened
    expect((await org.api(token, 'GET', '/api/me/context')).body.grants).toEqual([{ role: 'LEARNER', orgId: a.orgId, examCode: null }]);
  });

  test('F08 a SYS_ADMIN, who may, sees the organisation\'s data the others are refused (so the refusals are isolation, not emptiness)', async ({ page, seed, auth, uniqueEmail }) => {
    const suffix = Date.now().toString(36);
    const b = await seed.organization({ name: `Trung tâm B ${suffix}`, type: 'CENTER' });
    const member = await seed.verifiedLearner({ email: uniqueEmail('f08-member-b'), displayName: `Học viên Bí Mật B ${suffix}` });
    await seed.member({ orgId: b.orgId, userId: member.userId, roles: [{ role: 'LEARNER' }] });
    const admin = await seed.sysAdmin({ email: uniqueEmail('f08-sys') });
    await auth.loginAs(page, admin);
    await page.goto(`/admin/organizations/${b.orgId}`);
    await expect(page.getByRole('row', { name: new RegExp(`Học viên Bí Mật B ${suffix}`) })).toBeVisible();
    await page.goto('/admin/organizations/99999999-9999-4999-8999-999999999999');
    await expect(page.getByRole('heading', { level: 1, name: 'Không tìm thấy trang' })).toBeVisible();
  });
});
