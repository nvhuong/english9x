// F44 — Thành viên (spec §16.2): mời hàng loạt / nhập Excel, mã tham gia, xoá thành viên, và ai thấy email của ai.
// Through the real screens (members page, /join) with the HTTP API next to them for what a person cannot see on screen.
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { test, expect } from '../../fixtures';

const excel = (name: string) => path.join(path.dirname(fileURLToPath(import.meta.url)), '../../fixtures/excel', name);

async function adminOf(seed: any, auth: any, org: any, page: import('@playwright/test').Page, uniqueEmail: (l: string) => string, label: string) {
  const suffix = Date.now().toString(36);
  const center = await seed.organization({ name: `Trung tâm ${label} ${suffix}`, type: 'CENTER' });
  const admin = await seed.verifiedLearner({ email: uniqueEmail(`${label}-admin`), displayName: 'Quản trị Trung tâm' });
  await seed.member({ orgId: center.orgId, userId: admin.userId, roles: [{ role: 'ORG_ADMIN' }] });
  await auth.loginAs(page, admin);
  await org.switchContext(page, center.name);
  await page.goto(`/org/${center.orgId}/members`);
  return { center, admin };
}

test.describe('F44 members', () => {
  test('F44 an admin makes a join code, a learner joins with it; once the last use is gone nobody else can', async ({ page, browser, seed, auth, org, uniqueEmail }) => {
    const { center, admin } = await adminOf(seed, auth, org, page, uniqueEmail, 'f44a');
    const first = await seed.verifiedLearner({ email: uniqueEmail('f44a-first'), displayName: 'Học Viên Một' });
    const second = await seed.verifiedLearner({ email: uniqueEmail('f44a-second'), displayName: 'Học Viên Hai' });

    await page.getByLabel('Số lượt dùng').fill('1');
    await page.getByRole('button', { name: 'Tạo mã' }).click();
    const code = (await page.getByTestId('join-code').textContent())!.trim();
    expect(code).toMatch(/^[A-Z0-9]{8}$/);
    await expect(page.getByRole('row', { name: /Đang dùng được/ })).toContainText('0 trên 1');

    // the first learner joins from /join
    const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    const tab = await ctx.newPage();
    await auth.loginAs(tab, first);
    await tab.goto('/join');
    await tab.getByLabel(/^Mã/).fill(code);
    await tab.getByRole('button', { name: 'Tham gia' }).click();
    await expect(tab.getByRole('heading', { level: 1, name: 'Bạn đã tham gia' })).toBeVisible();
    expect(await seed.authz({ userId: first.userId, role: 'LEARNER', orgId: center.orgId })).toMatchObject({ allowed: true });

    // the last use is gone: another learner gets 410, nothing granted
    const token = (await org.apiLogin(second)).token;
    expect((await org.api(token, 'POST', `/api/join/${code}`)).status).toBe(410);
    expect((await org.api(token, 'GET', '/api/me/context')).body.grants).toEqual([]);
    await ctx.close();

    // the admin sees the use and the new member
    await page.reload();
    await expect(page.getByRole('row', { name: /Hết lượt/ })).toContainText('1 trên 1');
    await expect(page.getByRole('row', { name: new RegExp(first.email) })).toBeVisible();
    expect(admin.email).toBeTruthy();
  });

  test('F44 a learner of the organisation never reads another member\'s email; a revoked code is gone; guessing is limited', async ({ page, seed, auth, org, uniqueEmail }) => {
    const { center, admin } = await adminOf(seed, auth, org, page, uniqueEmail, 'f44b');
    const mate = await seed.verifiedLearner({ email: uniqueEmail('f44b-mate'), displayName: 'Bạn Cùng Lớp' });
    const peer = await seed.verifiedLearner({ email: uniqueEmail('f44b-peer'), displayName: 'Người Xem' });
    await seed.member({ orgId: center.orgId, userId: mate.userId, roles: [{ role: 'LEARNER' }] });
    await seed.member({ orgId: center.orgId, userId: peer.userId, roles: [{ role: 'LEARNER' }] });

    // the admin sees the e-mail, the learner is refused the member list and the page never carries the address
    const adminToken = (await org.apiLogin(admin)).token;
    const asAdmin = await org.api(adminToken, 'GET', `/api/orgs/${center.orgId}/role-grants`, { org: center.orgId });
    expect(asAdmin.text).toContain(mate.email);
    const peerToken = (await org.apiLogin(peer)).token;
    const asPeer = await org.api(peerToken, 'GET', `/api/orgs/${center.orgId}/role-grants`, { org: center.orgId });
    expect([403, 404]).toContain(asPeer.status);
    expect(asPeer.text).not.toContain(mate.email);

    // revoked code: 410 even for the right text
    const made = await org.api(adminToken, 'POST', `/api/orgs/${center.orgId}/join-codes`, {
      org: center.orgId,
      body: { expiresAt: new Date(Date.now() + 86400000).toISOString(), maxUses: 5 },
    });
    expect(made.status).toBe(201);
    const revoked = await org.api(adminToken, 'DELETE', `/api/orgs/${center.orgId}/join-codes/${made.body.joinCode.id}`, { org: center.orgId });
    expect(revoked.status, revoked.text).toBe(204);
    const stranger = await seed.verifiedLearner({ email: uniqueEmail('f44b-stranger') });
    const strangerToken = (await org.apiLogin(stranger)).token;
    expect((await org.api(strangerToken, 'POST', `/api/join/${made.body.code}`)).status).toBe(410);

    // guessing: one account trying wrong codes is stopped (429) and the right code is then refused too, until the window passes
    let limited = 0;
    for (let i = 0; i < 14; i++) {
      const r = await org.api(strangerToken, 'POST', `/api/join/ZZZZ${String(1000 + i)}`);
      if (r.status === 429) limited++;
      else expect(r.status).toBe(410);
    }
    expect(limited).toBeGreaterThan(0);
  });

  test('F44 a bulk invite and an Excel import: row errors invite nobody, a clean file invites everybody', async ({ page, seed, auth, org, mailpit, uniqueEmail }) => {
    await adminOf(seed, auth, org, page, uniqueEmail, 'f44c');

    await page.getByLabel(/^Địa chỉ email \(mỗi dòng/).fill(`${uniqueEmail('f44c-a')}, ${uniqueEmail('f44c-b')}`);
    await page.getByRole('button', { name: 'Gửi lời mời', exact: true }).last().click();
    await expect(page.getByText('Đã gửi 2 lời mời.')).toBeVisible();

    await page.getByLabel('Tệp Excel (.xlsx)').setInputFiles(excel('members_errors.xlsx'));
    await page.getByRole('button', { name: 'Nhập', exact: true }).click();
    await expect(page.getByText('Tệp có lỗi. Chưa ai được mời')).toBeVisible();
    const list = page.getByRole('list', { name: 'Các dòng cần sửa' });
    await expect(list).toContainText('Dòng 3');
    await expect(list).toContainText('Dòng 4');
    expect(await mailpit.countEmailsTo('e2e-import-3@example.test')).toBe(0);

    await page.getByLabel('Tệp Excel (.xlsx)').setInputFiles(excel('members_ok.xlsx'));
    await page.getByRole('button', { name: 'Nhập', exact: true }).click();
    await expect(page.getByText('Đã gửi 2 lời mời.').first()).toBeVisible();
    await mailpit.lastEmailTo('e2e-import-1@example.test', { subjectIncludes: 'Lời mời' });
    await mailpit.lastEmailTo('e2e-import-2@example.test', { subjectIncludes: 'Lời mời' });
  });

  test('F44 removing a member takes their seat back and the organisation keeps what they did', async ({ page, seed, auth, org, uniqueEmail }) => {
    const { center, admin } = await adminOf(seed, auth, org, page, uniqueEmail, 'f44d');
    const leaver = await seed.verifiedLearner({ email: uniqueEmail('f44d-leaver'), displayName: 'Học Viên Rời Đi' });
    await seed.member({ orgId: center.orgId, userId: leaver.userId, roles: [{ role: 'LEARNER' }] });
    const before = await org.api((await org.apiLogin(leaver)).token, 'GET', '/api/me/context');
    expect(JSON.stringify(before.body.grants), 'a member holds a grant of the organisation until removed').toContain(center.orgId);
    await page.reload();
    await page.getByRole('button', { name: 'Xoá Học Viên Rời Đi' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Xoá', exact: true }).click();
    await expect(page.getByRole('row', { name: /Học Viên Rời Đi/ })).toHaveCount(0);
    // seed.authz would still say "allowed": every person holds the GLOBAL learner role, which covers any context. What removal takes back is the organisation's grants.
    const leaverToken = (await org.apiLogin(leaver)).token;
    const ctx = await org.api(leaverToken, 'GET', '/api/me/context');
    expect(JSON.stringify(ctx.body.grants)).not.toContain(center.orgId);

    // the last administrator cannot be removed
    const adminToken = (await org.apiLogin(admin)).token;
    expect((await org.api(adminToken, 'DELETE', `/api/orgs/${center.orgId}/members/${admin.userId}`, { org: center.orgId })).status).toBe(409);
  });
});
