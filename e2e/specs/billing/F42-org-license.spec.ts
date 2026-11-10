// F42 — Gói suất tổ chức (spec §16.2): the SYS_ADMIN sells N seats for M months over chosen courses to an organisation. A CONTRACT licence is
// active at once; a QR licence files an order the organisation's ORG_ADMIN pays and the SYS_ADMIN confirms (the P5.2 queue), and only then is it
// active. The organisation's ORG_ADMIN reads it; nobody else creates or reads it.
//
// Driven through the real screens (the SYS_ADMIN's licence form and order queue, the ORG_ADMIN's licence page) and the real API.
import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';

const mainNav = (page: Page) => page.getByRole('navigation', { name: 'Điều hướng chính' });
const areaNav = (page: Page) => page.getByRole('navigation', { name: 'Điều hướng khu vực' });

test.describe('F42 organisation licence', () => {
  test('F42 a contract licence is active at once; a QR licence is active only after its order is confirmed', async ({ page, browser, seed, auth, org, uniqueEmail }) => {
    const suffix = Date.now().toString(36);
    const orgName = `Doanh nghiệp F42 ${suffix}`;
    const admin = await seed.sysAdmin({ email: uniqueEmail('f42-admin'), displayName: 'Quản Trị Giấy Phép' });
    const boss = await seed.verifiedLearner({ email: uniqueEmail('f42-boss'), displayName: 'Quản Trị Doanh Nghiệp' });
    const staff = await seed.verifiedLearner({ email: uniqueEmail('f42-staff'), displayName: 'Nhân Viên F42' });
    const company = await seed.organization({ name: orgName, type: 'ENTERPRISE' });
    await seed.member({ orgId: company.orgId, userId: boss.userId, roles: [{ role: 'ORG_ADMIN' }] });
    await seed.member({ orgId: company.orgId, userId: staff.userId, roles: [{ role: 'LEARNER' }] });
    const c1 = await seed.billing.course();
    const c2 = await seed.billing.course();
    const a = await org.apiLogin(admin);
    const b = await org.apiLogin(boss);
    const s = await org.apiLogin(staff);
    const licenseBody = { orgId: company.orgId, seats: 2, months: 6, courseIds: [c1.courseId, c2.courseId], priceVnd: 12000000, activation: 'CONTRACT', contractNo: `HD-${suffix}` };

    // nobody but the SYS_ADMIN sells a licence
    for (const t of [b.token, s.token]) expect((await org.api(t, 'POST', '/api/admin/org-licenses', { body: licenseBody })).status).toBe(403);
    expect((await org.api(b.token, 'POST', '/api/admin/org-licenses', { body: licenseBody, org: company.orgId })).status).toBe(403);
    expect((await org.api(s.token, 'GET', '/api/admin/org-licenses')).status).toBe(403);

    // 1) a CONTRACT licence on the real form: active at once, with its months counted from now
    await auth.loginAs(page, admin);
    await mainNav(page).getByRole('link', { name: 'Quản trị' }).click();
    await areaNav(page).getByRole('link', { name: 'Bán giấy phép suất' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Giấy phép tổ chức' })).toBeVisible();
    await page.getByRole('button', { name: 'Tạo giấy phép' }).click();
    await expect(page.getByText('Kiểm tra lại các trường', { exact: false })).toBeVisible();
    await page.getByRole('combobox', { name: 'Tổ chức' }).click();
    await page.getByRole('option', { name: orgName, exact: true }).click();
    await page.getByLabel('Số suất', { exact: true }).fill('2');
    await page.getByLabel('Số tháng', { exact: true }).fill('6');
    await page.getByLabel('Giá (đ)', { exact: true }).fill('12000000');
    await page.getByLabel('Số hợp đồng', { exact: true }).fill(`HD-${suffix}`);
    await page.getByRole('checkbox', { name: new RegExp(`^${c1.code}`) }).check();
    await page.getByRole('checkbox', { name: new RegExp(`^${c2.code}`) }).check();
    await page.getByRole('button', { name: 'Tạo giấy phép' }).click();
    await expect(page.getByText('Đã tạo giấy phép và kích hoạt ngay.')).toBeVisible();
    const contractRow = page.getByTestId('license-row').filter({ hasText: `HD-${suffix}` });
    await expect(contractRow).toContainText(orgName);
    await expect(contractRow).toContainText('Đang hiệu lực');
    await expect(contractRow).toContainText('0/2');

    const licenses = await org.api(a.token, 'GET', '/api/admin/org-licenses');
    const contract = (licenses.body as Array<any>).find((l) => l.contractNo === `HD-${suffix}`);
    expect(contract.status).toBe('ACTIVE');
    expect(contract.priceVnd).toBe(12000000);
    expect(contract.orderCode).toBeNull();
    const months = (new Date(contract.validUntil).getTime() - new Date(contract.validFrom).getTime()) / 86_400_000;
    expect(months).toBeGreaterThan(180);
    expect(months).toBeLessThan(186);

    // the audit log holds the licence, with no address
    const created = await org.api(a.token, 'GET', '/api/admin/audit-log?action=license.create&targetType=license&size=100');
    const row = (created.body.items as Array<{ targetId: string; detail: Record<string, unknown> }>).find((r) => r.targetId === contract.id)!;
    expect(row.detail.activation).toBe('CONTRACT');
    expect(row.detail.contractNo).toBe(`HD-${suffix}`);
    expect(JSON.stringify(row.detail), 'the record keeps no address').not.toContain('@');

    // 2) the ORG_ADMIN reads it on the real page, in their own organisation's context
    const bossCtx = await browser.newContext({ ignoreHTTPSErrors: true });
    const bossPage = await bossCtx.newPage();
    await auth.loginAs(bossPage, boss);
    await org.switchContext(bossPage, orgName);
    await mainNav(bossPage).getByRole('link', { name: 'Tổ chức' }).click();
    await areaNav(bossPage).getByRole('link', { name: 'Giấy phép suất' }).click();
    await expect(bossPage.getByTestId('license-seats')).toHaveText('Đã dùng 0/2 suất');
    await expect(bossPage.getByTestId('license-card')).toContainText('Đang hiệu lực');
    // a plain member and another organisation's administrator do not
    expect((await org.api(s.token, 'GET', `/api/orgs/${company.orgId}/licenses`, { org: company.orgId })).status).toBe(403);
    const other = await seed.organization({ name: `Trung tâm khác F42 ${suffix}`, type: 'CENTER' });
    const stranger = await seed.verifiedLearner({ email: uniqueEmail('f42-stranger') });
    await seed.member({ orgId: other.orgId, userId: stranger.userId, roles: [{ role: 'ORG_ADMIN' }] });
    const st = await org.apiLogin(stranger);
    expect((await org.api(st.token, 'GET', `/api/orgs/${company.orgId}/licenses`, { org: other.orgId })).status).toBe(404);
    expect((await org.api(st.token, 'GET', `/api/orgs/${other.orgId}/licenses`, { org: other.orgId })).body).toEqual([]);

    // 3) a QR licence: an order for the ORG_ADMIN, not active until the SYS_ADMIN confirms it on the queue
    const qr = await org.api(a.token, 'POST', '/api/admin/org-licenses', { body: { ...licenseBody, activation: 'QR', contractNo: undefined, seats: 3, priceVnd: 7500000, courseIds: [c1.courseId] } });
    expect(qr.status, qr.text).toBe(201);
    expect(qr.body.license.status).toBe('PENDING_PAYMENT');
    const code: string = qr.body.license.orderCode;
    expect(qr.body.transferNote).toContain(code);
    expect((await org.api(b.token, 'POST', `/api/orgs/${company.orgId}/licenses/${qr.body.license.id}/seats`, { org: company.orgId, body: { userId: staff.userId, courseIds: [c1.courseId] } })).status, 'no seats before payment').toBe(409);
    const mine = await org.api(b.token, 'GET', `/api/orders/${code}`);
    expect(mine.status).toBe(200);
    expect(mine.body.amountVnd).toBe(7500000);
    expect(mine.body.status).toBe('PENDING_PAYMENT');
    await bossPage.reload();
    await expect(bossPage.getByText(`Giấy phép chờ thanh toán đơn ${code}`, { exact: false })).toBeVisible();

    await areaNav(page).getByRole('link', { name: 'Đơn hàng' }).click();
    await page.getByLabel('Tìm kiếm', { exact: false }).fill(code);
    await page.getByRole('button', { name: 'Lọc', exact: true }).click();
    const orderRow = page.getByTestId('order-row').filter({ hasText: code });
    await expect(orderRow).toContainText('Quản Trị Doanh Nghiệp');
    await orderRow.getByRole('button', { name: `Xác nhận đơn ${code}` }).click();
    await page.getByRole('dialog').getByLabel('Mã giao dịch ngân hàng', { exact: false }).fill('FT42');
    await page.getByRole('dialog').getByRole('button', { name: 'Xác nhận đã nhận tiền' }).click();
    await expect(page.getByTestId('order-row').filter({ hasText: code })).toHaveCount(0);

    await bossPage.reload();
    await expect(bossPage.getByTestId('license-card')).toHaveCount(2);
    const activated = (await org.api(b.token, 'GET', `/api/orgs/${company.orgId}/licenses`, { org: company.orgId })).body.find((l: any) => l.orderCode === code);
    expect(activated.status).toBe('ACTIVE');
    expect(activated.validUntil).toBeTruthy();
    const changed = await org.api(a.token, 'GET', '/api/admin/audit-log?action=license.change&targetType=license&size=100');
    expect((changed.body.items as Array<{ targetId: string }>).some((r) => r.targetId === activated.id)).toBe(true);
    // the payer got no personal entitlement from a licence order, and a second confirmation is refused
    expect((await org.api(b.token, 'GET', '/api/me/entitlements')).body).toHaveLength(0);
    expect((await org.api(a.token, 'POST', `/api/admin/orders/${code}/confirm`, { body: {} })).status).toBe(409);
    await bossCtx.close();
  });
});
