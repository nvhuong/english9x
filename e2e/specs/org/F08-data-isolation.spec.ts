// F08 — Cách ly dữ liệu (spec §16.2): "Người tổ chức A mở URL lớp/khoá riêng của tổ chức B → 404".
//
// !! PARTIAL. Classes (P6.1) and courses (P3.1) have no pages or endpoints yet, so there is no class/course URL to open. This
// !! spec runs the same scenario against the organisation-owned pages and endpoints that DO exist (an organisation's members,
// !! roles and invitations): a person of A opens B's URLs in the browser and over HTTP and gets 404/403 with none of B's data,
// !! while a SYS_ADMIN, who is allowed, sees that the data is really there. The backend leak sweep (RlsLeakSweepIT) also
// !! covers every GET with an id. e2e/partial-features.json lists the class/course part and its owner.
import { test, expect } from '../../fixtures';

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
    const denied = page.waitForResponse((r) => r.url().includes(`/api/orgs/${b.orgId}/role-grants`));
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
