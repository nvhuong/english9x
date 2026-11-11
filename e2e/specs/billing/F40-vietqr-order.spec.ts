// F40 — Đơn VietQR (spec §16.2): "Đơn có QR đúng số tiền/nội dung; admin xác nhận → entitlement; từ chối → có lý do".
//
// The administrator sets the receiving account on the real screen; a learner places an order on the checkout screen and opens
// the real order page, which shows the QR, the account, the exact amount and the exact transfer note; the administrator confirms one order and rejects another
// on the real queue screen. Confirmation opens access (entitlements from now), is recorded in the audit log, and tells the learner (in-app and by mail); a
// rejection shows the reason (as text) to the learner. A second decision is refused.
//
// The learner places the order on the checkout screen (plan and course pickers) and the QR picture on the order page is decoded with jsQR; the payload is
// also checked field by field (amount, account, note, CRC). The scan with three real bank apps is a manual pre-release step.
import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';
import jsQR from 'jsqr';

const mainNav = (page: Page) => page.getByRole('navigation', { name: 'Điều hướng chính' });
const areaNav = (page: Page) => page.getByRole('navigation', { name: 'Điều hướng khu vực' });

/** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF) as the EMVCo field 63 uses it. */
function crc16(text: string): string {
  let crc = 0xffff;
  for (const ch of Buffer.from(text, 'ascii')) {
    crc ^= ch << 8;
    for (let i = 0; i < 8; i++) crc = crc & 0x8000 ? ((crc << 1) ^ 0x1021) & 0xffff : (crc << 1) & 0xffff;
  }
  return crc.toString(16).toUpperCase().padStart(4, '0');
}

function tlv(payload: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (let i = 0; i < payload.length; ) {
    const len = Number(payload.slice(i + 2, i + 4));
    out[payload.slice(i, i + 2)] = payload.slice(i + 4, i + 4 + len);
    i += 4 + len;
  }
  return out;
}

test.describe('F40 VietQR order', () => {
  test('F40 the QR carries the account, amount and note; confirming opens access and is audited; rejecting shows the reason; a second decision is refused', async ({ page, browser, seed, auth, org, mailpit, uniqueEmail }) => {
    const suffix = Date.now().toString(36);
    const admin = await seed.sysAdmin({ email: uniqueEmail('f40-admin'), displayName: 'Quản Trị Đơn' });
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f40-learner'), displayName: 'Người Mua Gói' });
    const a = await org.apiLogin(admin);
    const l = await org.apiLogin(learner);
    const course = await seed.billing.course();
    const plan = await org.api(a.token, 'POST', '/api/admin/plans', { body: { name: `Gói F40 ${suffix}`, priceVnd: 499000, months: 3, maxCourses: 1, active: true } });
    expect(plan.status).toBe(201);
    const accountNo = `9${Date.now().toString().slice(-9)}`;

    // 1) the administrator sets the receiving account on the screen (only a SYS_ADMIN reaches it)
    expect((await org.api(l.token, 'PUT', '/api/admin/payment-account', { body: { bankBin: '970436', accountNo: '1', accountName: 'X' } })).status).toBe(403);
    await auth.loginAs(page, admin);
    await mainNav(page).getByRole('link', { name: 'Quản trị' }).click();
    await areaNav(page).getByRole('link', { name: 'Tài khoản nhận tiền' }).click();
    await page.getByLabel('Mã ngân hàng (BIN)', { exact: false }).fill('12');
    await page.getByRole('button', { name: 'Lưu tài khoản' }).click();
    await expect(page.getByText('BIN gồm đúng 6 chữ số.')).toBeVisible();
    await page.getByLabel('Mã ngân hàng (BIN)', { exact: false }).fill('970436');
    await page.getByLabel('Số tài khoản', { exact: true }).fill(accountNo);
    await page.getByLabel('Tên chủ tài khoản', { exact: true }).fill('CONG TY ENGLISH9X');
    await page.getByRole('button', { name: 'Lưu tài khoản' }).click();
    await expect(page.getByText('Đã lưu tài khoản nhận tiền.')).toBeVisible();
    await expect(page.getByTestId('current-account')).toContainText(accountNo);

    // 2) the learner picks the plan and the course on the checkout screen, places the order and reads the QR on the order page
    const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    const learnerPage = await ctx.newPage();
    await auth.loginAs(learnerPage, learner);
    await learnerPage.goto('/checkout');
    await learnerPage.getByRole('radio', { name: new RegExp(`Gói F40 ${suffix}`) }).check();
    await learnerPage.getByRole('checkbox', { name: new RegExp(course.code) }).check();
    await learnerPage.getByRole('button', { name: 'Đặt đơn và thanh toán' }).click();
    await expect(learnerPage).toHaveURL(/\/orders\/[A-Z0-9]{6}$/);
    const code = learnerPage.url().split('/').pop()!;
    const placed = await org.api(l.token, 'GET', `/api/orders/${code}`);
    expect(placed.status).toBe(200);
    expect(placed.body.transferNote).toBe(`E9X ${code} ${course.code}`);
    const payload: string = placed.body.qrPayload;
    const top = tlv(payload);
    expect(top['54']).toBe('499000');
    expect(top['63']).toBe(crc16(payload.slice(0, -4)));
    expect(tlv(tlv(tlv(top['38'])['01'])['01'])['01'] ?? tlv(tlv(top['38'])['01'])['01']).toBe(accountNo);
    expect(tlv(top['62'])['08']).toBe(placed.body.transferNote);
    expect(placed.body.qrPngUrl).toBe(`/api/orders/${code}/qr.png`);

    await expect(learnerPage.getByRole('heading', { level: 1, name: `Đơn ${code}` })).toBeVisible();
    await expect(learnerPage.getByTestId('order-status')).toHaveText('Chờ thanh toán');
    const qr = learnerPage.getByRole('img', { name: `Mã QR thanh toán đơn ${code}` });
    await expect(qr).toBeVisible();
    await expect.poll(() => qr.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBe(512);
    await expect(learnerPage.getByTestId('copy-value-note')).toHaveText(placed.body.transferNote);
    await expect(learnerPage.getByTestId('copy-value-amount')).toHaveText('499000');
    await expect(learnerPage.getByTestId('copy-value-account')).toHaveText(accountNo);
    // decode the QR picture ON THE PAGE (pixels read in the browser, decoded with jsQR here): it carries exactly what the order says
    const pixels = await qr.evaluate((img: HTMLImageElement) => {
      const canvas = document.createElement('canvas');
      canvas.width = img.naturalWidth;
      canvas.height = img.naturalHeight;
      const c2d = canvas.getContext('2d')!;
      c2d.drawImage(img, 0, 0);
      const d = c2d.getImageData(0, 0, canvas.width, canvas.height);
      return { width: d.width, height: d.height, data: Array.from(d.data) };
    });
    const decoded = jsQR(Uint8ClampedArray.from(pixels.data), pixels.width, pixels.height);
    expect(decoded, 'the QR on the page can be decoded').not.toBeNull();
    expect(decoded!.data).toBe(payload);
    expect(tlv(decoded!.data)['54']).toBe('499000');
    expect(tlv(tlv(decoded!.data)['62'])['08']).toBe(`E9X ${code} ${course.code}`);
    // the image is served only to its owner
    const otherToken = (await org.apiLogin(await seed.verifiedLearner({ email: uniqueEmail('f40-other') }))).token;
    expect((await org.api(otherToken, 'GET', `/api/orders/${code}`)).status).toBe(404);

    // the administrator was told of the new order
    const adminNotes = await org.api(a.token, 'GET', '/api/me/notifications?size=50');
    expect(JSON.stringify(adminNotes.body)).toContain('ORDER_PENDING');

    // 3) the administrator confirms it on the queue screen
    await areaNav(page).getByRole('link', { name: 'Đơn hàng' }).click();
    await page.getByLabel('Tìm kiếm', { exact: false }).fill(code);
    await page.getByRole('button', { name: 'Lọc', exact: true }).click();
    const row = page.getByTestId('order-row').filter({ hasText: code });
    await expect(row).toHaveCount(1);
    await expect(row).toContainText(learner.email);
    await expect(row).toContainText(placed.body.transferNote);
    await row.getByRole('button', { name: `Xác nhận đơn ${code}` }).click();
    await page.getByRole('dialog').getByLabel('Mã giao dịch ngân hàng', { exact: false }).fill('FT26111012345');
    await page.getByRole('dialog').getByRole('button', { name: 'Xác nhận đã nhận tiền' }).click();
    await expect(page.getByTestId('order-row').filter({ hasText: code })).toHaveCount(0);

    // the learner sees it, holds the entitlement from now, and was told in the app and by mail
    await learnerPage.reload();
    await expect(learnerPage.getByTestId('order-status')).toHaveText('Đã xác nhận');
    await expect(learnerPage.getByText('Thanh toán đã được xác nhận.', { exact: false })).toBeVisible();
    const ents = await org.api(l.token, 'GET', '/api/me/entitlements');
    expect(ents.status).toBe(200);
    expect(ents.body).toHaveLength(1);
    expect(ents.body[0].active).toBe(true);
    await expect.poll(async () => JSON.stringify((await org.api(l.token, 'GET', '/api/me/notifications?size=50')).body), { timeout: 40_000 }).toContain('ORDER_CONFIRMED');
    const mail = await mailpit.lastEmailTo(learner.email, { subjectIncludes: 'Đơn hàng đã được xác nhận', timeoutMs: 40_000 });
    expect(mail.Text).toContain('Bạn có thể bắt đầu học ngay.');

    // a second decision on the same order is refused (409) and changes nothing
    expect((await org.api(a.token, 'POST', `/api/admin/orders/${code}/confirm`, { body: {} })).status).toBe(409);
    expect((await org.api(a.token, 'POST', `/api/admin/orders/${code}/reject`, { body: { reason: 'late' } })).status).toBe(409);
    expect((await org.api(a.token, 'GET', '/api/me/entitlements')).body).toHaveLength(0);
    expect((await org.api(l.token, 'GET', '/api/me/entitlements')).body).toHaveLength(1);
    // nobody but a SYS_ADMIN decides
    expect((await org.api(l.token, 'POST', `/api/admin/orders/${code}/confirm`, { body: {} })).status).toBe(403);

    // 4) a rejection needs a reason, and the learner reads it as text
    const second = await org.api(l.token, 'POST', '/api/orders', { body: { planId: plan.body.id, courseIds: [course.courseId] } });
    const code2: string = second.body.orderCode;
    expect((await org.api(a.token, 'POST', `/api/admin/orders/${code2}/reject`, { body: { reason: '   ' } })).status).toBe(422);
    await page.reload();
    await page.getByLabel('Tìm kiếm', { exact: false }).fill(code2);
    await page.getByRole('button', { name: 'Lọc', exact: true }).click();
    const row2 = page.getByTestId('order-row').filter({ hasText: code2 });
    await row2.getByRole('button', { name: `Từ chối đơn ${code2}` }).click();
    const submit = page.getByRole('dialog').getByRole('button', { name: 'Từ chối đơn', exact: true });
    await expect(submit).toBeDisabled();
    await page.getByRole('dialog').getByLabel('Lý do', { exact: false }).fill('<b>Sai số tiền</b>');
    await submit.click();
    await expect(page.getByTestId('order-row').filter({ hasText: code2 })).toHaveCount(0);
    await learnerPage.goto(`/orders/${code2}`);
    await expect(learnerPage.getByTestId('order-status')).toHaveText('Bị từ chối');
    await expect(learnerPage.getByTestId('reject-reason')).toHaveText('Lý do: <b>Sai số tiền</b>');
    await expect(learnerPage.locator('b')).toHaveCount(0);
    await expect.poll(async () => JSON.stringify((await org.api(l.token, 'GET', '/api/me/notifications?size=50')).body), { timeout: 40_000 }).toContain('ORDER_REJECTED');

    // 5) both decisions and the account change are in the audit log, without the account number and without the reason
    const confirmed = await org.api(a.token, 'GET', '/api/admin/audit-log?action=order.confirm&targetType=order&size=100');
    const mine = (confirmed.body.items as Array<{ detail: Record<string, unknown> }>).filter((r) => r.detail.orderCode === code);
    expect(mine).toHaveLength(1);
    expect(mine[0]!.detail.amountVnd).toBe(499000);
    expect(mine[0]!.detail.bankRef).toBe('FT26111012345');
    const rejected = await org.api(a.token, 'GET', '/api/admin/audit-log?action=order.reject&targetType=order&size=100');
    const rej = (rejected.body.items as Array<{ detail: Record<string, unknown> }>).filter((r) => r.detail.orderCode === code2);
    expect(rej).toHaveLength(1);
    expect(rej[0]!.detail.rejectReasonLength).toBe('<b>Sai số tiền</b>'.length);
    expect(JSON.stringify([confirmed.body, rejected.body])).not.toContain('Sai số tiền');
    const changed = await org.api(a.token, 'GET', '/api/admin/audit-log?action=payment-account.update&size=100');
    expect(changed.body.items.length).toBeGreaterThanOrEqual(1);
    expect(JSON.stringify(changed.body)).not.toContain(accountNo);
    await ctx.close();
  });
});
