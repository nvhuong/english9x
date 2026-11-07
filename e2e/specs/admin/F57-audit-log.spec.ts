// F57 — Nhật ký (spec §16.2): "Xác nhận đơn, thu hồi suất, duyệt nội dung đều có trong audit log".
// What exists today is driven through the real screens and the real services: creating an organisation, changing a member's roles,
// inviting and revoking, accepting an invitation (all through the admin screens and the invitation page), and approving content
// (a real ReviewService run on a stand-in version, seed.reviewFlow, until the CMS review screens of P2.9 exist). The SYS_ADMIN then
// reads all of it on the audit-log screen, filters it, and nobody else can open it.
// PENDING (see e2e/partial-features.json): order confirmation (billing, P5.2) and seat revocation (billing, P5.3) have no module
// yet, so their records cannot be produced; those modules call AuditLog#record and extend this spec with the two rows.
import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';

const mainNav = (page: Page) => page.getByRole('navigation', { name: 'Điều hướng chính' });
const areaNav = (page: Page) => page.getByRole('navigation', { name: 'Điều hướng khu vực' });

async function openAuditLog(page: Page) {
  await mainNav(page).getByRole('link', { name: 'Quản trị' }).click();
  await areaNav(page).getByRole('link', { name: 'Nhật ký' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Nhật ký hệ thống' })).toBeVisible();
}

async function filterBy(page: Page, fields: { actor?: string; action?: string; targetId?: string }) {
  await page.getByRole('form', { name: 'Bộ lọc' }).getByRole('button', { name: 'Xoá bộ lọc' }).click();
  if (fields.actor) await page.getByLabel(/^Người thực hiện/).fill(fields.actor);
  if (fields.targetId) await page.getByLabel('Mã đối tượng').fill(fields.targetId);
  if (fields.action) {
    await page.getByRole('combobox', { name: 'Hành động' }).click();
    await page.getByRole('option', { name: fields.action, exact: true }).click();
  }
  await page.getByRole('button', { name: 'Lọc', exact: true }).click();
}

const rowsOf = (page: Page) => page.getByTestId('audit-row');

test.describe('F57 audit log', () => {
  test('F57 the actions that exist leave records the SYS_ADMIN reads, filters and cannot change', async ({ page, browser, seed, auth, org, mailpit, uniqueEmail }) => {
    const suffix = Date.now().toString(36);
    const orgName = `Trung tâm Nhật Ký ${suffix}`;
    const admin = await seed.sysAdmin({ email: uniqueEmail('f57-admin'), displayName: 'Quản Trị Nhật Ký' });
    const member = await seed.verifiedLearner({ email: uniqueEmail('f57-member'), displayName: 'Người Được Đổi Quyền' });
    const invitee = await seed.verifiedLearner({ email: uniqueEmail('f57-invitee'), displayName: 'Người Được Mời' });
    const dropped = uniqueEmail('f57-dropped');
    const author = await seed.verifiedLearner({ email: uniqueEmail('f57-author'), displayName: 'Người Soạn Nội Dung' });
    const reviewer = await seed.verifiedLearner({ email: uniqueEmail('f57-reviewer'), displayName: 'Người Duyệt Nội Dung' });
    await seed.staffGrant({ userId: author.userId, role: 'AUTHOR', examCode: 'IELTS' });
    await seed.staffGrant({ userId: reviewer.userId, role: 'REVIEWER', examCode: 'IELTS' });

    // 1) creating an organisation and 2) changing a member's roles, through the admin screens
    await auth.loginAs(page, admin);
    await mainNav(page).getByRole('link', { name: 'Quản trị' }).click();
    await areaNav(page).getByRole('link', { name: 'Tổ chức' }).click();
    await page.getByLabel(/^Tên tổ chức/).fill(orgName);
    await page.getByRole('combobox', { name: 'Loại tổ chức' }).click();
    await page.getByRole('option', { name: 'Trung tâm' }).click();
    await page.getByRole('button', { name: 'Tạo tổ chức' }).click();
    await expect(page.getByText(`Đã tạo tổ chức ${orgName}.`)).toBeVisible();
    const orgId = (await page.getByRole('link', { name: orgName, exact: true }).getAttribute('href'))!.split('/').pop()!;
    await seed.member({ orgId, userId: member.userId, roles: [{ role: 'LEARNER' }] });
    await page.getByRole('link', { name: orgName, exact: true }).click();
    const memberRow = page.getByRole('row', { name: /Người Được Đổi Quyền/ });
    await memberRow.getByRole('button', { name: /Sửa vai trò/ }).click();
    await page.getByRole('dialog').getByRole('checkbox', { name: 'Giáo viên · IELTS' }).check();
    await page.getByRole('dialog').getByRole('button', { name: 'Lưu' }).click();
    await expect(memberRow).toContainText('Giáo viên · IELTS');

    // 3) inviting English9x staff, 4) revoking one invitation, 5) accepting the other
    await page.getByRole('link', { name: '← Tất cả tổ chức' }).click();
    await areaNav(page).getByRole('link', { name: 'Tài khoản nội bộ' }).click();
    for (const email of [invitee.email, dropped]) {
      await page.getByLabel(/^Email/).fill(email);
      await page.getByRole('checkbox', { name: 'Biên soạn · IELTS' }).check();
      await page.getByRole('button', { name: 'Gửi lời mời' }).click();
      await expect(page.getByText(`Đã gửi lời mời tới ${email}.`)).toBeVisible();
      await page.getByRole('checkbox', { name: 'Biên soạn · IELTS' }).uncheck();
    }
    await page.getByRole('button', { name: `Thu hồi lời mời gửi ${dropped}` }).click();
    await expect(page.getByRole('row', { name: new RegExp(dropped) })).toContainText('Đã thu hồi');
    const link = mailpit.linksIn(await mailpit.lastEmailTo(invitee.email, { subjectIncludes: 'Lời mời tham gia English9x' })).find((l) => l.includes('/accept-invitation?token='))!;
    const token = new URL(link).searchParams.get('token')!;
    const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    const person = await ctx.newPage();
    await org.acceptInvitation(person, link, invitee);
    await ctx.close();

    // 6) approving content: the real review workflow, author and reviewer being two people
    const flow = await seed.reviewFlow({ authorId: author.userId, reviewerId: reviewer.userId, outcome: 'approved' });

    // the SYS_ADMIN reads it all on the screen
    await openAuditLog(page);
    await filterBy(page, { actor: admin.email });
    const created = rowsOf(page).filter({ hasText: orgName });
    await expect(created).toHaveCount(1);
    await expect(created).toContainText('Tạo tổ chức');
    await expect(created).toContainText('org.create');
    await expect(created).toContainText('Quản Trị Nhật Ký');
    await expect(created).toContainText(admin.email);
    await expect(created).toContainText('English9x');
    const roleRow = rowsOf(page).filter({ hasText: member.userId });
    await expect(roleRow).toHaveCount(1);
    await expect(roleRow).toContainText('Đổi quyền');
    await expect(roleRow).toContainText('LEARNER');
    await expect(roleRow).toContainText('LEARNER, TEACHER/IELTS');
    await expect(rowsOf(page).filter({ hasText: 'invitation.send' })).toHaveCount(2);
    // the log keeps no address (it could not be erased): the screen reads it from the invitation, which still exists here
    await expect(rowsOf(page).filter({ hasText: invitee.email })).toContainText('Gửi lời mời');
    const revoked = rowsOf(page).filter({ hasText: 'invitation.revoke' });
    await expect(revoked).toHaveCount(1);
    await expect(revoked).toContainText(dropped);

    // accepting is recorded as the person who accepted it, not the inviter
    await filterBy(page, { actor: invitee.email });
    await expect(rowsOf(page)).toHaveCount(1);
    await expect(rowsOf(page).first()).toContainText('Chấp nhận lời mời');
    await expect(rowsOf(page).first()).toContainText('Người Được Mời');

    // content approval: the author's submit and the reviewer's approval, each by its own person, under the same review id
    await filterBy(page, { targetId: flow.reviewId });
    await expect(rowsOf(page)).toHaveCount(2);
    const approve = rowsOf(page).filter({ hasText: 'review.approve' });
    await expect(approve).toContainText('Duyệt nội dung');
    await expect(approve).toContainText('Người Duyệt Nội Dung');
    await expect(approve).toContainText('IELTS');
    const submit = rowsOf(page).filter({ hasText: 'review.submit' });
    await expect(submit).toContainText('Gửi duyệt');
    await expect(submit).toContainText('Người Soạn Nội Dung');

    // a filter that matches nothing says so and offers the way back
    await filterBy(page, { actor: `nobody-${suffix}@example.test` });
    await expect(page.getByRole('heading', { name: 'Không có bản ghi nào khớp' })).toBeVisible();
    await page.getByRole('region', { name: 'Kết quả' }).getByRole('button', { name: 'Xoá bộ lọc' }).click();
    await expect(page.getByRole('heading', { name: 'Không có bản ghi nào khớp' })).toHaveCount(0);

    // no token, in the API answer either; and append-only is not something the API offers
    const { token: adminToken } = await org.apiLogin(admin);
    const all = await org.api(adminToken, 'GET', `/api/admin/audit-log?actor=${encodeURIComponent(admin.email)}&size=100`);
    expect(all.status).toBe(200);
    expect(JSON.stringify(all.body)).not.toContain(token);
    expect(JSON.stringify(all.body).toLowerCase()).not.toMatch(/token|password|secret|hash/);
    const sentRows = (await org.api(adminToken, 'GET', '/api/admin/audit-log?action=invitation.send&size=100')).body.items as Array<{ invitedEmail?: string; detail: Record<string, unknown> }>;
    expect(sentRows.some((r) => r.invitedEmail === invitee.email), 'the address is resolved from the invitation').toBe(true);
    expect(sentRows.every((r) => !('email' in r.detail)), 'no address is stored in any detail').toBe(true);
    for (const method of ['PUT', 'PATCH', 'DELETE', 'POST']) {
      const { status } = await org.api(adminToken, method, '/api/admin/audit-log', { body: {} });
      expect(status, `${method} is refused as a client error, not served and not a crash`).toBeGreaterThanOrEqual(400);
      expect(status, `${method} is refused as a client error, not served and not a crash`).toBeLessThan(500);
    }
    expect((await org.api(adminToken, 'GET', '/api/admin/audit-log?size=100000')).body.size).toBe(100);
  });

  test('F57 nobody but a SYS_ADMIN reaches the log: no menu entry, no page, no data', async ({ page, seed, auth, org, uniqueEmail }) => {
    const center = await seed.organization({ name: `Trung tâm F57 ${Date.now().toString(36)}`, type: 'CENTER' });
    const orgAdmin = await seed.verifiedLearner({ email: uniqueEmail('f57-orgadmin') });
    const author = await seed.verifiedLearner({ email: uniqueEmail('f57-plain-author') });
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f57-learner') });
    await seed.member({ orgId: center.orgId, userId: orgAdmin.userId, roles: [{ role: 'ORG_ADMIN' }] });
    await seed.staffGrant({ userId: author.userId, role: 'AUTHOR', examCode: 'IELTS' });
    await seed.reviewFlow({ authorId: author.userId, outcome: 'submitted' }); // there is something in the log to be refused

    for (const who of [orgAdmin, author, learner]) {
      const { token } = await org.apiLogin(who);
      expect((await org.api(token, 'GET', '/api/admin/audit-log')).status, `${who.email} reads the log`).toBe(403);
      expect((await org.api(token, 'GET', '/api/admin/audit-log/actions')).status).toBe(403);
    }
    // an ORG_ADMIN gets no organisation-level log either, in their own context
    expect((await org.api((await org.apiLogin(orgAdmin)).token, 'GET', '/api/admin/audit-log', { org: center.orgId })).status).toBe(403);

    await auth.loginAs(page, orgAdmin);
    await expect(mainNav(page).getByRole('link', { name: 'Quản trị' })).toHaveCount(0);
    await page.goto('/admin/audit-log');
    await expect(page.getByRole('heading', { level: 1, name: 'Bạn chưa có quyền xem trang này' })).toBeVisible();
    await expect(rowsOf(page)).toHaveCount(0);
  });

  test('F57 what is written into the log is shown as text', async ({ page, seed, auth, uniqueEmail }) => {
    const admin = await seed.sysAdmin({ email: uniqueEmail('f57-xss-admin') });
    const name = `<img src=x onerror="window.__pwned=1"> ${Date.now().toString(36)}`;
    await auth.loginAs(page, admin);
    await mainNav(page).getByRole('link', { name: 'Quản trị' }).click();
    await areaNav(page).getByRole('link', { name: 'Tổ chức' }).click();
    await page.getByLabel(/^Tên tổ chức/).fill(name);
    await page.getByRole('combobox', { name: 'Loại tổ chức' }).click();
    await page.getByRole('option', { name: 'Doanh nghiệp' }).click();
    await page.getByRole('button', { name: 'Tạo tổ chức' }).click();
    await expect(page.getByText(`Đã tạo tổ chức ${name}.`)).toBeVisible();
    await openAuditLog(page);
    await filterBy(page, { actor: admin.email });
    const row = rowsOf(page).first();
    await expect(row).toContainText(name);
    await expect(page.locator('img[src="x"]')).toHaveCount(0);
    expect(await page.evaluate(() => (window as unknown as { __pwned?: number }).__pwned)).toBeUndefined();
  });
});
