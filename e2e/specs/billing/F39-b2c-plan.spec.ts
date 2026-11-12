// F39 — Gói B2C (spec §16.2): "Mua gói, chọn khoá ≤ tối đa".
//
// The scenario runs on the real screens too (checkout with the plan and course pickers, the person's orders page) beside the real-API checks.
import AxeBuilder from '@axe-core/playwright';
import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';

async function axeClean(page: Page) {
  const r = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  expect(r.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}


test.describe('F39 B2C plan', () => {
  test('F39 an administrator sells a plan, a verified learner orders within the course limit at a locked price, an unverified one cannot, and an unpaid order expires', async ({ seed, org, uniqueEmail }) => {
    const admin = await seed.sysAdmin({ email: uniqueEmail('f39-admin') });
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f39-learner') });
    const unverified = await seed.verifiedLearner({ email: uniqueEmail('f39-unverified'), verified: false });
    const a = await org.apiLogin(admin);
    const l = await org.apiLogin(learner);
    const u = await org.apiLogin(unverified);
    const c1 = await seed.billing.course();
    const c2 = await seed.billing.course();

    // only the SYS_ADMIN sells plans; a learner sees plans on sale only
    const body = { name: `Gói F39 ${Date.now()}`, priceVnd: 499000, months: 3, maxCourses: 1, active: true };
    expect((await org.api(l.token, 'POST', '/api/admin/plans', { body })).status).toBe(403);
    const created = await org.api(a.token, 'POST', '/api/admin/plans', { body });
    expect(created.status).toBe(201);
    const planId: string = created.body.id;
    const hidden = await org.api(a.token, 'POST', '/api/admin/plans', { body: { ...body, name: `Ẩn ${Date.now()}`, active: false } });
    const onSale = (await org.api(l.token, 'GET', '/api/plans')).body as Array<{ id: string }>;
    expect(onSale.map((p) => p.id)).toContain(planId);
    expect(onSale.map((p) => p.id)).not.toContain(hidden.body.id);

    // more courses than the plan allows, and a course that is not published, are refused
    const tooMany = await org.api(l.token, 'POST', '/api/orders', { body: { planId, courseIds: [c1.courseId, c2.courseId] } });
    expect(tooMany.status).toBe(400);
    expect(tooMany.body.type).toBe('urn:e9x:problem:validation-failed');
    expect((await org.api(l.token, 'POST', '/api/orders', { body: { planId, courseIds: ['00000000-0000-4000-8000-000000000001'] } })).status).toBe(400);

    // an unverified email cannot buy (F01: the same rule at the real order endpoint)
    const refused = await org.api(u.token, 'POST', '/api/orders', { body: { planId, courseIds: [c1.courseId] } });
    expect(refused.status).toBe(403);
    expect(refused.body.type).toBe('urn:e9x:problem:email-not-verified');

    // the order: pending, the price of today, a code, a transfer note
    const ok = await org.api(l.token, 'POST', '/api/orders', { body: { planId, courseIds: [c1.courseId] } });
    expect(ok.status).toBe(201);
    expect(ok.body.orderCode).toMatch(/^[A-Z0-9]{6}$/);
    expect(ok.body.amountVnd).toBe(499000);
    expect(ok.body.transferNote).toBe(`E9X ${ok.body.orderCode} ${c1.code}`);
    const code: string = ok.body.orderCode;

    // the administrator changes the price: the order keeps what was agreed; nobody else reads it
    expect((await org.api(a.token, 'PUT', `/api/admin/plans/${planId}`, { body: { ...body, priceVnd: 999000 } })).status).toBe(200);
    const mine = await org.api(l.token, 'GET', `/api/orders/${code}`);
    expect(mine.status).toBe(200);
    expect(mine.body.status).toBe('PENDING_PAYMENT');
    expect(mine.body.amountVnd).toBe(499000);
    expect((await org.api(u.token, 'GET', `/api/orders/${code}`)).status).toBe(404);
    expect((await org.api(a.token, 'GET', `/api/orders/${code}`)).status).toBe(404);

    // an unpaid order older than 7 days expires; the system leaves an audit record that the SYS_ADMIN reads
    const old = await seed.billing.agedOrder({ userId: learner.userId, planId, ageDays: 8 });
    await seed.billing.runJob('expire_orders');
    await expect.poll(async () => (await org.api(l.token, 'GET', `/api/orders/${old.code}`)).body.status, { timeout: 40_000 }).toBe('EXPIRED');
    expect((await org.api(l.token, 'GET', `/api/orders/${code}`)).body.status).toBe('PENDING_PAYMENT');
    const log = await org.api(a.token, 'GET', `/api/admin/audit-log?action=order.expire&targetId=${old.orderId}`);
    expect(log.body.items).toHaveLength(1);
    expect(log.body.items[0].actorKind).toBe('SYSTEM');
    const placed = await org.api(a.token, 'GET', `/api/admin/audit-log?action=order.create&targetType=order&actor=${learner.userId}`);
    expect(placed.body.items.length).toBeGreaterThanOrEqual(1);
  });

  test('F39 on the checkout screen the course picker stops at the plan limit, an unverified learner is refused, and a verified one places the order and sees it in "Đơn của tôi"', async ({ page, browser, seed, auth, org, uniqueEmail }) => {
    const suffix = Date.now().toString(36);
    const admin = await seed.sysAdmin({ email: uniqueEmail('f39b-admin') });
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f39b-learner') });
    const unverified = await seed.verifiedLearner({ email: uniqueEmail('f39b-unverified'), verified: false });
    const a = await org.apiLogin(admin);
    const c1 = await seed.billing.course();
    const c2 = await seed.billing.course();
    const name = `Gói F39 UI ${suffix}`;
    expect((await org.api(a.token, 'POST', '/api/admin/plans', { body: { name, priceVnd: 499000, months: 3, maxCourses: 1, active: true } })).status).toBe(201);
    // There must be an active receiving account for the order page to show the transfer note; this test owns that setup rather than leaning on another spec (F40) having run
    // first — the one-active-account invariant is global, so relying on test order made this flaky once the suite's ordering changed.
    expect((await org.api(a.token, 'PUT', '/api/admin/payment-account', { body: { bankBin: '970436', accountNo: `9${Date.now().toString().slice(-9)}`, accountName: 'CONG TY ENGLISH9X' } })).status, 'activate a receiving account').toBe(200);

    // the picker allows one course (the plan's limit) and frees the other when it is un-ticked
    await auth.loginAs(page, learner);
    await page.goto('/checkout');
    await page.getByRole('radio', { name: new RegExp(name) }).check();
    await page.getByRole('checkbox', { name: new RegExp(c1.code) }).check();
    await axeClean(page);
    await expect(page.getByRole('checkbox', { name: new RegExp(c2.code) })).toBeDisabled();
    await expect(page.getByTestId('course-count')).toHaveText('Đã chọn 1/1 khoá');
    await page.getByRole('checkbox', { name: new RegExp(c1.code) }).uncheck();
    await expect(page.getByRole('checkbox', { name: new RegExp(c2.code) })).toBeEnabled();
    await page.getByRole('checkbox', { name: new RegExp(c1.code) }).check();
    await page.getByRole('button', { name: 'Đặt đơn và thanh toán' }).click();
    await expect(page).toHaveURL(/\/orders\/[A-Z0-9]{6}$/);
    const code = page.url().split('/').pop()!;
    await expect(page.getByTestId('order-status')).toHaveText('Chờ thanh toán');
    await expect(page.getByTestId('copy-value-note')).toHaveText(`E9X ${code} ${c1.code}`);

    // the order is in the person's orders
    await page.goto('/orders');
    await expect(page.getByTestId('my-order').filter({ hasText: code })).toContainText('Chờ thanh toán');
    await axeClean(page);

    // an unverified email is refused at the checkout screen (the endpoint answers email-not-verified)
    const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    const other = await ctx.newPage();
    await auth.loginAs(other, unverified);
    await other.goto('/checkout');
    await other.getByRole('radio', { name: new RegExp(name) }).check();
    await other.getByRole('checkbox', { name: new RegExp(c1.code) }).check();
    await other.getByRole('button', { name: 'Đặt đơn và thanh toán' }).click();
    await expect(other.getByText('Hãy xác thực email trước khi mua gói.')).toBeVisible();
    await expect(other).toHaveURL(/\/checkout$/);
    await ctx.close();
  });
});
