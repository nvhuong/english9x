// F05 — Tạo tổ chức (spec §16.2): "SYS_ADMIN tạo trung tâm và doanh nghiệp → mời ORG_ADMIN → doanh nghiệp không có menu nội
// dung/giáo viên". Everything through the real screens and mail. The enterprise difference is asserted three ways: the role
// boxes the screens offer, the areas the header offers once the person holds the roles a center can give, and the server's
// refusal (409) of an enterprise teacher/author/reviewer when asked directly.
import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';

async function openOrganizations(page: Page) {
  await page.getByRole('navigation', { name: 'Điều hướng chính' }).getByRole('link', { name: 'Quản trị' }).click();
  await page.getByRole('navigation', { name: 'Điều hướng khu vực' }).getByRole('link', { name: 'Tổ chức' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Tổ chức' })).toBeVisible();
}

async function createOrganization(page: Page, name: string, type: 'Trung tâm' | 'Doanh nghiệp') {
  await page.getByLabel(/^Tên tổ chức/).fill(name);
  await page.getByRole('combobox', { name: 'Loại tổ chức' }).click();
  await page.getByRole('option', { name: type }).click();
  await page.getByRole('button', { name: 'Tạo tổ chức' }).click();
  await expect(page.getByText(`Đã tạo tổ chức ${name}.`)).toBeVisible();
  await expect(page.getByRole('link', { name, exact: true })).toBeVisible();
}

test.describe('F05 create organizations', () => {
  test('F05 a SYS_ADMIN creates a center and an enterprise and invites an ORG_ADMIN to each', async ({ page, browser, seed, auth, org, mailpit, uniqueEmail }) => {
    const suffix = Date.now().toString(36);
    const centerName = `Trung tâm Ánh Dương ${suffix}`;
    const enterpriseName = `Công ty Bình Minh ${suffix}`;
    const admin = await seed.sysAdmin({ email: uniqueEmail('f05-admin') });
    const centerAdmin = await seed.verifiedLearner({ email: uniqueEmail('f05-center'), displayName: 'Lê Quản Trị Trung Tâm' });
    const enterpriseAdmin = await seed.verifiedLearner({ email: uniqueEmail('f05-enterprise'), displayName: 'Vũ Quản Trị Doanh Nghiệp' });

    await auth.loginAs(page, admin);
    await openOrganizations(page);
    // the form checks in the browser first
    await page.getByRole('button', { name: 'Tạo tổ chức' }).click();
    await expect(page.getByText('Vui lòng nhập tên tổ chức.')).toBeVisible();
    await expect(page.getByText('Hãy chọn loại tổ chức.')).toBeVisible();
    await createOrganization(page, centerName, 'Trung tâm');
    await createOrganization(page, enterpriseName, 'Doanh nghiệp');
    const table = page.getByRole('table', { name: 'Các tổ chức' });
    await expect(table.getByRole('row', { name: new RegExp(centerName) })).toContainText('Trung tâm');
    await expect(table.getByRole('row', { name: new RegExp(enterpriseName) })).toContainText('Doanh nghiệp');

    // enterprise: the invite form offers learner and org admin only
    await page.getByRole('link', { name: enterpriseName, exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: `Thành viên của ${enterpriseName}` })).toBeVisible();
    await expect(page.getByRole('checkbox', { name: 'Quản trị tổ chức' })).toBeVisible();
    await expect(page.getByRole('checkbox', { name: 'Học viên' })).toBeVisible();
    for (const role of [/Giáo viên/, /Biên soạn/, /Người duyệt/]) {
      await expect(page.getByRole('checkbox', { name: role })).toHaveCount(0);
    }
    await page.getByLabel(/^Email/).fill(enterpriseAdmin.email);
    await page.getByRole('checkbox', { name: 'Quản trị tổ chức' }).check();
    await page.getByRole('button', { name: 'Gửi lời mời' }).click();
    await expect(page.getByText(`Đã gửi lời mời tới ${enterpriseAdmin.email}.`)).toBeVisible();

    // center: it also offers teachers, authors and reviewers per exam
    await page.getByRole('link', { name: '← Tất cả tổ chức' }).click();
    await page.getByRole('link', { name: centerName, exact: true }).click();
    await expect(page.getByRole('checkbox', { name: 'Giáo viên · IELTS' })).toBeVisible();
    await expect(page.getByRole('checkbox', { name: 'Biên soạn · TOEIC' })).toBeVisible();
    await page.getByLabel(/^Email/).fill(centerAdmin.email);
    await page.getByRole('checkbox', { name: 'Quản trị tổ chức' }).check();
    await page.getByRole('button', { name: 'Gửi lời mời' }).click();
    await expect(page.getByText(`Đã gửi lời mời tới ${centerAdmin.email}.`)).toBeVisible();

    // both admins accept in their own browsers
    const ctxE = await browser.newContext({ ignoreHTTPSErrors: true });
    const ctxC = await browser.newContext({ ignoreHTTPSErrors: true });
    const pe = await ctxE.newPage();
    const pc = await ctxC.newPage();
    const linkOf = async (email: string) => mailpit.linksIn(await mailpit.lastEmailTo(email, { subjectIncludes: 'Lời mời' })).find((l) => l.includes('/accept-invitation?token='))!;
    await org.acceptInvitation(pe, await linkOf(enterpriseAdmin.email), enterpriseAdmin);
    await org.acceptInvitation(pc, await linkOf(centerAdmin.email), centerAdmin);
    const orgIdOf = async (email: string) => {
      const me = (await org.api((await org.apiLogin({ email, password: email === enterpriseAdmin.email ? enterpriseAdmin.password : centerAdmin.password })).token, 'GET', '/api/me/context')).body;
      return me.organizations[0].id as string;
    };
    const enterpriseId = await orgIdOf(enterpriseAdmin.email);
    const centerId = await orgIdOf(centerAdmin.email);

    // the enterprise admin: switches into the enterprise, has the Tổ chức area, and no content menu
    await pe.getByRole('link', { name: 'Về trang chủ' }).click();
    await org.switchContext(pe, enterpriseName);
    const navE = pe.getByRole('navigation', { name: 'Điều hướng chính' });
    await expect(navE.getByRole('link', { name: 'Tổ chức' })).toBeVisible();
    await expect(navE.getByRole('link', { name: 'Biên soạn' })).toHaveCount(0);
    await navE.getByRole('link', { name: 'Tổ chức' }).click();
    await pe.getByRole('navigation', { name: 'Điều hướng khu vực' }).getByRole('link', { name: 'Thành viên' }).click();
    const rowE = pe.getByRole('row', { name: /Vũ Quản Trị Doanh Nghiệp/ });
    await expect(rowE).toContainText('Quản trị tổ chức');
    await rowE.getByRole('button', { name: /Sửa vai trò/ }).click();
    const dialogE = pe.getByRole('dialog');
    await expect(dialogE.getByRole('checkbox', { name: 'Quản trị tổ chức' })).toBeChecked();
    for (const role of [/Giáo viên/, /Biên soạn/, /Người duyệt/]) {
      await expect(dialogE.getByRole('checkbox', { name: role })).toHaveCount(0);
    }
    await dialogE.getByRole('button', { name: 'Huỷ' }).click();

    // the server refuses an enterprise teacher/author/reviewer even when the screens are bypassed
    const tokenE = (await org.apiLogin(enterpriseAdmin)).token;
    for (const role of ['TEACHER', 'AUTHOR', 'REVIEWER']) {
      const r = await org.api(tokenE, 'PUT', `/api/orgs/${enterpriseId}/role-grants`, { org: enterpriseId, body: { userId: enterpriseAdmin.userId, roles: [{ role: 'ORG_ADMIN' }, { role, examCode: 'IELTS' }] } });
      expect(r.status, role).toBe(409);
      expect(r.body.type).toBe('urn:e9x:problem:role-not-allowed');
    }

    // the center admin gives themselves a teacher role per exam in the same screen: the Tổ chức and Biên soạn areas both open
    await pc.getByRole('link', { name: 'Về trang chủ' }).click();
    await org.switchContext(pc, centerName);
    await pc.getByRole('navigation', { name: 'Điều hướng chính' }).getByRole('link', { name: 'Tổ chức' }).click();
    await pc.getByRole('navigation', { name: 'Điều hướng khu vực' }).getByRole('link', { name: 'Thành viên' }).click();
    const rowC = pc.getByRole('row', { name: /Lê Quản Trị Trung Tâm/ });
    await rowC.getByRole('button', { name: /Sửa vai trò/ }).click();
    const dialogC = pc.getByRole('dialog');
    await dialogC.getByRole('checkbox', { name: 'Giáo viên · IELTS' }).check();
    await dialogC.getByRole('button', { name: 'Lưu' }).click();
    await expect(rowC).toContainText('Giáo viên · IELTS');
    await expect(rowC).toContainText('Quản trị tổ chức');
    await expect(pc.getByRole('navigation', { name: 'Điều hướng chính' }).getByRole('link', { name: 'Biên soạn' })).toBeVisible();
    await ctxE.close();
    await ctxC.close();
  });

  test('F05 only a SYS_ADMIN reaches the organisation screens and the creation endpoint', async ({ page, seed, auth, org, uniqueEmail }) => {
    const person = await seed.verifiedLearner({ email: uniqueEmail('f05-plain') });
    await auth.loginAs(page, person);
    await expect(page.getByRole('navigation', { name: 'Điều hướng chính' }).getByRole('link', { name: 'Quản trị' })).toHaveCount(0);
    await page.goto('/admin/organizations');
    await expect(page.getByRole('heading', { level: 1, name: 'Bạn chưa có quyền xem trang này' })).toBeVisible();
    const { token } = await org.apiLogin(person);
    expect((await org.api(token, 'POST', '/api/admin/organizations', { body: { name: 'Lén', type: 'CENTER' } })).status).toBe(403);
    expect((await org.api(token, 'GET', '/api/admin/organizations')).status).toBe(403);
  });
});
