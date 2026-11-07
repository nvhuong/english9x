// F06 — Phân quyền theo kỳ thi (spec §16.2): "AUTHOR IELTS không thấy/không sửa được câu TOEIC; REVIEWER không duyệt được bản
// mình soạn".
//
// !! PARTIAL. The questions CMS (P2.8) and the review workflow (P1.6 / P2.9) do not exist yet, so "không thấy/không sửa được
// !! câu TOEIC" and "không duyệt được bản mình soạn" cannot be driven through a screen. This spec covers what exists, against the
// !! REAL server: the exam a role is granted for travels from the invitation to the grants, the server's own Authz (the one the
// !! CMS will call) answers per exam, a role does not carry into an organisation or into another role, and the screens an
// !! AUTHOR/REVIEWER can open are the right ones. e2e/partial-features.json lists what is pending and who owns it.
import { test, expect } from '../../fixtures';

test.describe('F06 exam-scoped roles', () => {
  test('F06 an AUTHOR of IELTS is an author for IELTS only; a REVIEWER of TOEIC reviews TOEIC only and never their own version', async ({ page, seed, auth, org, uniqueEmail }) => {
    const authorIelts = await seed.verifiedLearner({ email: uniqueEmail('f06-author') });
    const reviewerToeic = await seed.verifiedLearner({ email: uniqueEmail('f06-reviewer') });
    const both = await seed.verifiedLearner({ email: uniqueEmail('f06-both') });
    await seed.staffGrant({ userId: authorIelts.userId, role: 'AUTHOR', examCode: 'IELTS' });
    await seed.staffGrant({ userId: reviewerToeic.userId, role: 'REVIEWER', examCode: 'TOEIC' });
    await seed.staffGrant({ userId: both.userId, role: 'AUTHOR', examCode: 'IELTS' });
    await seed.staffGrant({ userId: both.userId, role: 'REVIEWER', examCode: 'TOEIC' });

    const can = async (userId: string, role: string, examCode: string | null) => (await seed.authz({ userId, role, examCode })).allowed;
    expect(await can(authorIelts.userId, 'AUTHOR', 'IELTS')).toBe(true);
    expect(await can(authorIelts.userId, 'AUTHOR', 'TOEIC')).toBe(false); // other exam
    expect(await can(authorIelts.userId, 'REVIEWER', 'IELTS')).toBe(false); // other role
    expect(await can(authorIelts.userId, 'AUTHOR', null)).toBe(true);
    expect(await can(reviewerToeic.userId, 'REVIEWER', 'TOEIC')).toBe(true);
    expect(await can(reviewerToeic.userId, 'REVIEWER', 'IELTS')).toBe(false);
    expect(await can(reviewerToeic.userId, 'AUTHOR', 'TOEIC')).toBe(false);
    // somebody who both writes IELTS and reviews TOEIC may not review what they wrote: there is no exception to ask for
    expect(await can(both.userId, 'AUTHOR', 'IELTS')).toBe(true);
    expect(await can(both.userId, 'REVIEWER', 'IELTS')).toBe(false);
    expect((await seed.authz({ userId: both.userId, role: 'REVIEWER', examCode: 'TOEIC' })).canReviewOwnVersion).toBe(false);
    // an English9x role does not follow its holder into an organisation they merely belong to
    const center = await seed.organization({ type: 'CENTER' });
    await seed.member({ orgId: center.orgId, userId: authorIelts.userId });
    expect((await seed.authz({ userId: authorIelts.userId, orgId: center.orgId, role: 'AUTHOR', examCode: 'IELTS' })).allowed).toBe(false);

    // the screens: the content area opens for them, the admin and organisation areas do not
    await auth.loginAs(page, authorIelts);
    const nav = page.getByRole('navigation', { name: 'Điều hướng chính' });
    await expect(nav.getByRole('link', { name: 'Biên soạn' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Quản trị' })).toHaveCount(0);
    await expect(nav.getByRole('link', { name: 'Tổ chức' })).toHaveCount(0);
    await nav.getByRole('link', { name: 'Biên soạn' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Chưa có việc cần làm' })).toBeVisible();
    await page.goto('/org/members');
    await expect(page.getByRole('heading', { level: 1, name: 'Bạn chưa có quyền xem trang này' })).toBeVisible();
    const { token } = await org.apiLogin(authorIelts);
    expect((await org.api(token, 'GET', '/api/me/context')).body.grants).toEqual([{ role: 'AUTHOR', orgId: null, examCode: 'IELTS' }]);
  });

  test('F06 roles are granted per exam through the invitation and the exam is validated', async ({ page, seed, auth, org, uniqueEmail }) => {
    const admin = await seed.sysAdmin({ email: uniqueEmail('f06-admin') });
    const { token } = await org.apiLogin(admin);
    const invite = (roles: unknown) => org.api(token, 'POST', '/api/admin/invitations', { body: { email: uniqueEmail('f06-invitee'), roles } });
    expect((await invite([{ role: 'AUTHOR', examCode: 'IELTS' }])).status).toBe(201);
    expect((await invite([{ role: 'AUTHOR' }])).status, 'an exam-scoped role needs its exam').toBe(400);
    expect((await invite([{ role: 'AUTHOR', examCode: 'KLINGON' }])).status, 'unknown exam').toBe(400);
    expect((await invite([{ role: 'SYS_ADMIN', examCode: 'IELTS' }])).status, 'SYS_ADMIN applies to every exam').toBe(400);
    // the screen shows each exam as its own choice
    await auth.loginAs(page, admin);
    await page.goto('/admin/staff');
    for (const exam of ['IELTS', 'TOEIC']) {
      for (const role of ['Biên soạn', 'Người duyệt', 'Giáo viên']) {
        await expect(page.getByRole('checkbox', { name: `${role} · ${exam}` })).toBeVisible();
      }
    }
  });
});
