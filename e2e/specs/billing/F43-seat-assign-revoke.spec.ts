// F43 — Gán/thu hồi suất (spec §16.2): the ORG_ADMIN assigns a seat to a MEMBER for courses of the licence, the person learns until the licence ends,
// taking the seat back ends access at once and frees the seat; no more seats than the licence has, no course outside it, nobody outside the organisation.
//
// Driven through the real licence page of the ORG_ADMIN (assign, revoke with a confirmation) and the real API for what the page cannot show
// (another person's entitlements, the refusals, the audit record).
import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';

const mainNav = (page: Page) => page.getByRole('navigation', { name: 'Điều hướng chính' });
const areaNav = (page: Page) => page.getByRole('navigation', { name: 'Điều hướng khu vực' });

test.describe('F43 seat assign and revoke', () => {
  test('F43 assigning gives access until the licence ends, revoking ends it at once and frees the seat, the limits hold', async ({ page, seed, auth, org, uniqueEmail }) => {
    const suffix = Date.now().toString(36);
    const orgName = `Doanh nghiệp F43 ${suffix}`;
    const admin = await seed.sysAdmin({ email: uniqueEmail('f43-admin') });
    const boss = await seed.verifiedLearner({ email: uniqueEmail('f43-boss'), displayName: 'Quản Trị F43' });
    const ann = await seed.verifiedLearner({ email: uniqueEmail('f43-ann'), displayName: 'An Được Gán Suất' });
    const bao = await seed.verifiedLearner({ email: uniqueEmail('f43-bao'), displayName: 'Bảo Chờ Suất' });
    const outsider = await seed.verifiedLearner({ email: uniqueEmail('f43-out'), displayName: 'Người Ngoài Tổ Chức' });
    const company = await seed.organization({ name: orgName, type: 'ENTERPRISE' });
    for (const u of [ann, bao]) await seed.member({ orgId: company.orgId, userId: u.userId, roles: [{ role: 'LEARNER' }] });
    await seed.member({ orgId: company.orgId, userId: boss.userId, roles: [{ role: 'ORG_ADMIN' }] });
    const inLicense = await seed.billing.course();
    const alsoInLicense = await seed.billing.course();
    const notInLicense = await seed.billing.course();
    const a = await org.apiLogin(admin);
    const b = await org.apiLogin(boss);
    const an = await org.apiLogin(ann);
    const created = await org.api(a.token, 'POST', '/api/admin/org-licenses', {
      body: { orgId: company.orgId, seats: 1, months: 3, courseIds: [inLicense.courseId, alsoInLicense.courseId], priceVnd: 3000000, activation: 'CONTRACT', contractNo: `HD-${suffix}` },
    });
    expect(created.status, created.text).toBe(201);
    const license = created.body.license;
    const seats = `/api/orgs/${company.orgId}/licenses/${license.id}/seats`;

    // 1) assigning on the real page
    await auth.loginAs(page, boss);
    await org.switchContext(page, orgName);
    await mainNav(page).getByRole('link', { name: 'Tổ chức' }).click();
    await areaNav(page).getByRole('link', { name: 'Giấy phép suất' }).click();
    await expect(page.getByTestId('license-seats')).toHaveText('Đã dùng 0/1 suất');
    const form = page.getByRole('form', { name: 'Gán suất' });
    await form.getByRole('button', { name: 'Gán suất' }).click();
    await expect(form.getByText('Chọn một thành viên.')).toBeVisible();
    await form.getByRole('combobox', { name: 'Thành viên' }).click();
    await page.getByRole('option', { name: /An Được Gán Suất/ }).click();
    await form.getByRole('checkbox', { name: new RegExp(`^${inLicense.code}`) }).check();
    await form.getByRole('button', { name: 'Gán suất' }).click();
    await expect(page.getByText('Đã gán suất cho An Được Gán Suất.')).toBeVisible();
    await expect(page.getByTestId('license-seats')).toHaveText('Đã dùng 1/1 suất');
    const row = page.getByTestId('seat-row').filter({ hasText: 'An Được Gán Suất' });
    await expect(row).toContainText(inLicense.code);

    // the person learns: access until the licence ends, and no further
    const ents = await org.api(an.token, 'GET', '/api/me/entitlements');
    expect(ents.body).toHaveLength(1);
    expect(ents.body[0].active).toBe(true);
    expect(ents.body[0].courseId).toBe(inLicense.courseId);
    expect(new Date(ents.body[0].validUntil).toISOString()).toBe(new Date(license.validUntil).toISOString());
    expect((await seed.billing.gate({ userId: ann.userId, courseId: inLicense.courseId })).activeFor).toBe(true);
    expect((await seed.billing.gate({ userId: ann.userId, courseId: notInLicense.courseId })).activeFor).toBe(false);

    // 2) the limits: the last seat is taken, a course outside the licence, somebody outside the organisation, a second seat for the same person
    const full = await org.api(b.token, 'POST', seats, { org: company.orgId, body: { userId: bao.userId, courseIds: [inLicense.courseId] } });
    expect(full.status).toBe(409);
    expect(full.body.type).toBe('urn:e9x:problem:no-seat-available');
    expect((await org.api(b.token, 'POST', seats, { org: company.orgId, body: { userId: ann.userId, courseIds: [inLicense.courseId] } })).status).toBe(409);
    expect((await org.api(b.token, 'POST', seats, { org: company.orgId, body: { userId: bao.userId, courseIds: [notInLicense.courseId] } })).body.type).toBe('urn:e9x:problem:course-not-in-license');
    expect((await org.api(b.token, 'POST', seats, { org: company.orgId, body: { userId: outsider.userId, courseIds: [inLicense.courseId] } })).body.type).toBe('urn:e9x:problem:not-an-org-member');
    // only the organisation's ORG_ADMIN: not the member, not the SYS_ADMIN in the English9x context
    expect((await org.api(an.token, 'POST', seats, { org: company.orgId, body: { userId: bao.userId, courseIds: [inLicense.courseId] } })).status).toBe(403);
    expect((await org.api(a.token, 'POST', seats, { body: { userId: bao.userId, courseIds: [inLicense.courseId] } })).status).toBe(403);

    // 3) taking it back on the page, after a confirmation: access ends at once, the seat is free
    await row.getByRole('button', { name: 'Thu hồi suất của An Được Gán Suất' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toContainText('An Được Gán Suất sẽ mất quyền học các khoá này ngay lập tức');
    expect((await org.api(an.token, 'GET', '/api/me/entitlements')).body[0].active, 'nothing changed before the confirmation').toBe(true);
    await dialog.getByRole('button', { name: 'Thu hồi suất', exact: true }).click();
    await expect(page.getByText('Đã thu hồi suất của An Được Gán Suất.')).toBeVisible();
    await expect(page.getByTestId('license-seats')).toHaveText('Đã dùng 0/1 suất');
    await expect(page.getByTestId('seat-row')).toHaveCount(0);
    const after = await org.api(an.token, 'GET', '/api/me/entitlements');
    expect(after.body).toHaveLength(1);
    expect(after.body[0].active).toBe(false);
    expect((await seed.billing.gate({ userId: ann.userId, courseId: inLicense.courseId })).activeFor).toBe(false);
    expect((await org.api(b.token, 'DELETE', `${seats}/${ann.userId}`, { org: company.orgId })).status, 'a second revoke finds no seat').toBe(404);

    // 4) the freed seat goes to somebody else (two courses of the licence)
    const second = await org.api(b.token, 'POST', seats, { org: company.orgId, body: { userId: bao.userId, courseIds: [inLicense.courseId, alsoInLicense.courseId] } });
    expect(second.status, second.text).toBe(201);
    const bt = await org.apiLogin(bao);
    expect((await org.api(bt.token, 'GET', '/api/me/entitlements')).body.filter((e: any) => e.active)).toHaveLength(2);

    // 5) the audit log holds both, filed under the seat, with ids and no address
    const assigned = await org.api(a.token, 'GET', '/api/admin/audit-log?action=seat.assign&targetType=seat&size=100');
    const mineAssigned = (assigned.body.items as Array<{ targetId: string; detail: Record<string, any> }>).filter((r) => r.detail.licenseId === license.id);
    expect(mineAssigned).toHaveLength(2);
    expect(mineAssigned.map((r) => r.detail.userId).sort()).toEqual([ann.userId, bao.userId].sort());
    const revoked = await org.api(a.token, 'GET', '/api/admin/audit-log?action=seat.revoke&targetType=seat&size=100');
    const mineRevoked = (revoked.body.items as Array<{ targetId: string; detail: Record<string, any> }>).filter((r) => r.detail.licenseId === license.id);
    expect(mineRevoked).toHaveLength(1);
    expect(mineRevoked[0]!.detail.userId).toBe(ann.userId);
    expect(mineRevoked[0]!.detail.courseIds).toEqual([inLicense.courseId]);
    expect(mineRevoked[0]!.targetId).toBe(mineAssigned.find((r) => r.detail.userId === ann.userId)!.targetId);
    expect(JSON.stringify([assigned.body, revoked.body])).not.toContain('@example.test');
  });
});
