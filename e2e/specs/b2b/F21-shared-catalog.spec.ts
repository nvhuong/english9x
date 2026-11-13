// F21 — Kho khoá học chung (spec §14, ruling P6.5): a centre offers a PUBLISHED course of its own to the English9x shared catalogue. Only an English9x
// REVIEWER of the exam approves or declines: the owner (its author, its admin, even its own reviewer) cannot self-approve and a bare SYS_ADMIN is not a reviewer.
// Approved, the course is visible to B2C and to every other centre; a decline is the REJECTED status, from which the owner offers it again.
//
// The offer and the decisions are driven through the real screens; every refusal is asserted on the API (the backend is the boundary, not the hidden button).
import { test, expect } from '../../fixtures';
import { staffed } from '../../fixtures/content';
import { publishedCourse, publishedLesson, publishedToeicTestVersion } from '../../fixtures/course';

test.describe('F21 shared catalogue', () => {
  test('F21 the owner cannot self-approve and a SYS_ADMIN cannot approve; an English9x reviewer approves into B2C; a decline is REJECTED and can be offered again', async ({ page, browser, request, seed, auth, org, uniqueEmail }) => {
    test.setTimeout(420_000);
    const mark = Math.random().toString(36).slice(2, 8);
    const a = await staffed(['TOEIC'], 'f21-a', { request, uniqueEmail, apiLogin: org.apiLogin }, { inOrganisation: true });
    const l1 = await publishedLesson(a, 'TOEIC', `Ngữ pháp ${mark}`);
    const l2 = await publishedLesson(a, 'TOEIC', `Từ vựng ${mark}`);
    const t1 = await publishedToeicTestVersion(a, `Kiểm tra ${mark}`);
    const title = `Khoá chia sẻ F21 ${mark}`;
    const course = await publishedCourse(a, 'FREE', { l1, test: t1, l2 }, title);
    const b = await staffed(['TOEIC'], 'f21-b', { request, uniqueEmail, apiLogin: org.apiLogin }, { inOrganisation: true });
    const orgAdmin = await seed.verifiedLearner({ email: uniqueEmail('f21-admin'), displayName: 'Quản Trị Trung Tâm A' });
    await seed.member({ orgId: a.orgId!, userId: orgAdmin.userId, roles: [{ role: 'ORG_ADMIN' }] });
    const sysAdmin = await seed.sysAdmin({ email: uniqueEmail('f21-sys') });
    const e9Reviewer = await seed.verifiedLearner({ email: uniqueEmail('f21-e9rev'), displayName: 'Người Duyệt English9x' });
    await seed.staffGrant({ userId: e9Reviewer.userId, role: 'REVIEWER', examCode: 'TOEIC' });
    const b2c = await seed.verifiedLearner({ email: uniqueEmail('f21-b2c') });
    const ad = await org.apiLogin(orgAdmin);
    const sys = await org.apiLogin(sysAdmin);
    const rev = await org.apiLogin(e9Reviewer);
    const b2cLogin = await org.apiLogin(b2c);
    const id = course.courseId;
    const status = async () => (await org.api(a.author.token, 'GET', `/api/cms/courses/${id}`, { org: a.orgId })).body.sharedStatus as string;
    const catalog = async (token: string, ctx: string | null) => ((await org.api(token, 'GET', '/api/catalog/courses', { org: ctx })).body as Array<{ code: string }>).map((c) => c.code);
    const decide = (token: string, ctx: string | null, what: 'approve' | 'reject') => org.api(token, 'POST', `/api/admin/shared-catalog/${id}/${what}`, { org: ctx });

    // 1) the centre's author offers the published course on the real course page
    expect(await status()).toBe('NONE');
    await auth.loginAs(page, a.authorLogin);
    await org.switchContext(page, a.orgName!);
    await page.goto(`/cms/courses/${id}`);
    await expect(page.getByTestId('shared-panel')).toContainText('Chưa gửi');
    await page.getByRole('button', { name: 'Gửi lên kho chung' }).click();
    await expect(page.getByText('Đã gửi khoá học lên kho chung, đang chờ duyệt.')).toBeVisible();
    await expect(page.getByTestId('shared-panel')).toContainText('Đang chờ duyệt');
    expect(await status()).toBe('PENDING');
    // a second offer while pending is a conflict
    expect((await org.api(a.author.token, 'POST', `/api/cms/courses/${id}/share`, { org: a.orgId })).status).toBe(409);
    // another centre's author cannot offer it (not theirs)
    expect([403, 404]).toContain((await org.api(b.author.token, 'POST', `/api/cms/courses/${id}/share`, { org: b.orgId })).status);

    // 2) nobody on the owner's side approves: the author, the centre's admin, the centre's own reviewer, another centre's reviewer, a bare SYS_ADMIN, a B2C learner
    const outsiders: Array<[string, string, string | null]> = [
      ['owner author', a.author.token, a.orgId],
      ['owner admin', ad.token, a.orgId],
      ['owner reviewer', a.reviewer.token, a.orgId],
      ['another centre reviewer', b.reviewer.token, b.orgId],
      ['SYS_ADMIN', sys.token, null],
      ['B2C learner', b2cLogin.token, null],
    ];
    for (const [who, token, ctx] of outsiders) {
      for (const what of ['approve', 'reject'] as const) {
        const r = await decide(token, ctx, what);
        // the centre's own reviewer is a member of the owner's organisation, so the row is visible to them: the database refuses the change and the API answers
        // 409 share-conflict (not 403/404) - still a refusal that changes nothing, asserted below
        expect(who === 'owner reviewer' ? [403, 404, 409] : [403, 404], `${who} ${what} -> ${r.status} ${r.text}`).toContain(r.status);
      }
      const queue = await org.api(token, 'GET', '/api/admin/shared-catalog', { org: ctx });
      if (who.endsWith('reviewer')) {
        // a reviewer role inside the centre passes the role check, but Row-Level Security shows it only its own centre's rows: never another centre's course
        const owners = queue.status === 200 ? (queue.body as Array<{ ownerOrgId: string }>).map((i) => i.ownerOrgId) : [];
        expect([200, 403, 404], `${who} queue`).toContain(queue.status);
        expect(owners.every((o) => o === ctx), `${who} sees only its own centre's rows`).toBe(true);
      } else {
        expect([403, 404], `${who} queue`).toContain(queue.status);
      }
    }
    expect(await status(), 'nothing was decided').toBe('PENDING');
    // not visible to B2C or another centre while pending
    expect(await catalog(b2cLogin.token, null)).not.toContain(course.code);
    expect(await catalog(b.author.token, b.orgId)).not.toContain(course.code);

    // 3) a SYS_ADMIN reaching the queue page is refused (no REVIEWER grant: the menu entry is absent and the server answers 403)
    const sysCtx = await browser.newContext({ ignoreHTTPSErrors: true });
    const sysPage = await sysCtx.newPage();
    await auth.loginAs(sysPage, sysAdmin);
    await sysPage.goto('/cms/shared-catalog');
    await expect(sysPage.getByRole('heading', { level: 1, name: 'Kho khoá học chung' })).toHaveCount(0);
    await sysCtx.close();

    // 4) the English9x reviewer declines on the real queue (with the centre named), and the decline is the REJECTED status
    const revCtx = await browser.newContext({ ignoreHTTPSErrors: true });
    const revPage = await revCtx.newPage();
    await auth.loginAs(revPage, e9Reviewer);
    await revPage.goto('/cms/shared-catalog');
    await expect(revPage.getByRole('heading', { level: 1, name: 'Kho khoá học chung' })).toBeVisible();
    const row = revPage.getByTestId('shared-row').filter({ hasText: title });
    await expect(row).toContainText(a.orgName!);
    await row.getByRole('button', { name: `Từ chối khoá ${title}` }).click();
    await revPage.getByRole('dialog').getByRole('button', { name: 'Từ chối khoá' }).click();
    await expect(revPage.getByText(`Đã từ chối khoá ${title}`, { exact: false })).toBeVisible();
    await expect(revPage.getByTestId('shared-row').filter({ hasText: title })).toHaveCount(0);
    expect(await status()).toBe('REJECTED');
    expect(await catalog(b2cLogin.token, null), 'a rejected course is not public').not.toContain(course.code);

    // 5) the owner sees the rejection and offers it again (REJECTED -> PENDING)
    await page.reload();
    await expect(page.getByTestId('shared-panel')).toContainText('Bị từ chối');
    await page.getByRole('button', { name: 'Gửi lại' }).click();
    await expect(page.getByTestId('shared-panel')).toContainText('Đang chờ duyệt');
    expect(await status()).toBe('PENDING');
    // withdrawing a pending request returns it to NONE, and it can be offered again
    await page.getByRole('button', { name: 'Rút lại yêu cầu' }).click();
    await expect(page.getByTestId('shared-panel')).toContainText('Chưa gửi');
    expect(await status()).toBe('NONE');
    await page.getByRole('button', { name: 'Gửi lên kho chung' }).click();
    await expect(page.getByTestId('shared-panel')).toContainText('Đang chờ duyệt');

    // 6) the English9x reviewer approves on the real queue: B2C and another centre now see the course; it left the queue
    await revPage.reload();
    const again = revPage.getByTestId('shared-row').filter({ hasText: title });
    await again.getByRole('button', { name: `Duyệt khoá ${title}` }).click();
    await expect(revPage.getByText(`Đã duyệt khoá ${title} vào kho chung.`)).toBeVisible();
    await expect(revPage.getByTestId('shared-row').filter({ hasText: title })).toHaveCount(0);
    expect(await status()).toBe('APPROVED');
    expect(await catalog(b2cLogin.token, null), 'B2C sees the approved shared course').toContain(course.code);
    expect(await catalog(b.author.token, b.orgId), 'another centre sees it too').toContain(course.code);
    expect((await org.api(rev.token, 'GET', '/api/admin/shared-catalog')).body.map((i: { courseId: string }) => i.courseId)).not.toContain(id);
    // an approved course is no longer pending: offering it again is a conflict
    expect((await org.api(a.author.token, 'POST', `/api/cms/courses/${id}/share`, { org: a.orgId })).status).toBe(409);
    await revCtx.close();
  });
});
