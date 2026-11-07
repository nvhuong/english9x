// F07 — Chuyển ngữ cảnh (spec §16.2): "Người thuộc 2 tổ chức chuyển ngữ cảnh → danh sách khoá/lớp đổi theo".
//
// !! PARTIAL. There are no courses or classes yet (P3.1 courses, P6.1 classes), so the lists that change with the context are
// !! the ones that exist: the members and roles of the organisation, the areas and menus the roles open, and the switcher
// !! itself. The scenario is real: one person in two organisations, the data in the UI changes with the context, no data of the
// !! organisation left behind is shown while the new one loads, the choice survives a reload, and a context the person no
// !! longer belongs to is dropped without an error loop. e2e/partial-features.json lists the course/class part and its owner.
import { test, expect } from '../../fixtures';

test.describe('F07 context switch', () => {
  test('F07 switching changes the data in the UI, and nothing of the old organisation is shown while the new one loads', async ({ page, seed, auth, org, uniqueEmail }) => {
    const suffix = Date.now().toString(36);
    const nameA = `Trung tâm Alpha ${suffix}`;
    const nameB = `Trung tâm Beta ${suffix}`;
    const person = await seed.verifiedLearner({ email: uniqueEmail('f07-person'), displayName: 'Phạm Hai Tổ Chức' });
    const a = await seed.organization({ name: nameA, type: 'CENTER' });
    const b = await seed.organization({ name: nameB, type: 'CENTER' });
    const adminRoles = [{ role: 'ORG_ADMIN' }];
    await seed.member({ orgId: a.orgId, userId: person.userId, roles: adminRoles });
    await seed.member({ orgId: b.orgId, userId: person.userId, roles: [{ role: 'ORG_ADMIN' }, { role: 'TEACHER', examCode: 'TOEIC' }] });
    const inA = await seed.verifiedLearner({ email: uniqueEmail('f07-in-a'), displayName: `Học viên Alpha ${suffix}` });
    const inB = await seed.verifiedLearner({ email: uniqueEmail('f07-in-b'), displayName: `Học viên Beta ${suffix}` });
    await seed.member({ orgId: a.orgId, userId: inA.userId, roles: [{ role: 'LEARNER' }] });
    await seed.member({ orgId: b.orgId, userId: inB.userId, roles: [{ role: 'LEARNER' }] });

    // organisation B's member list is slow to arrive: the window in which stale data would show
    const requestedWith: string[] = [];
    await page.route('**/api/orgs/*/role-grants', async (route) => {
      const ctx = route.request().headers()['x-org-context'] ?? '';
      requestedWith.push(ctx);
      if (ctx === b.orgId) await new Promise((r) => setTimeout(r, 2500));
      await route.continue();
    });

    await auth.loginAs(page, person);
    const nav = page.getByRole('navigation', { name: 'Điều hướng chính' });
    const switcher = page.getByRole('combobox', { name: 'Ngữ cảnh' });
    await expect(switcher).toContainText('Cá nhân');
    // personal context: the organisation area does not exist for this person
    await expect(nav.getByRole('link', { name: 'Tổ chức' })).toHaveCount(0);
    await page.goto('/org/members');
    await expect(page.getByRole('heading', { level: 1, name: 'Bạn chưa có quyền xem trang này' })).toBeVisible();

    // organisation A: the members of A
    await org.switchContext(page, nameA);
    await expect(page.getByRole('heading', { level: 1, name: `Thành viên của ${nameA}` })).toBeVisible();
    await expect(page.getByRole('row', { name: new RegExp(`Học viên Alpha ${suffix}`) })).toBeVisible();
    await expect(page.getByText(`Học viên Beta ${suffix}`)).toHaveCount(0);
    // ...and nothing of A's roles for B: A has no teacher role to edit-in
    await expect(page.getByRole('row', { name: /Phạm Hai Tổ Chức/ })).not.toContainText('Giáo viên');

    // switch to B while A's list is on screen: A's data is gone at once, B's arrives later
    await org.switchContext(page, nameB);
    await expect(page.getByText(`Học viên Alpha ${suffix}`)).toHaveCount(0);
    await expect(page.getByRole('status').filter({ hasText: 'Đang tải thành viên…' })).toBeVisible();
    await expect(page.getByText(`Học viên Alpha ${suffix}`)).toHaveCount(0);
    await expect(page.getByText(`Học viên Beta ${suffix}`)).toHaveCount(0);
    // B's data when it arrives: B's members and B's roles
    await expect(page.getByRole('row', { name: new RegExp(`Học viên Beta ${suffix}`) })).toBeVisible();
    await expect(page.getByText(`Học viên Alpha ${suffix}`)).toHaveCount(0);
    await expect(page.getByRole('row', { name: /Phạm Hai Tổ Chức/ })).toContainText('Giáo viên · TOEIC');
    expect(requestedWith.filter((c) => c === a.orgId).length, 'A was asked for in context A').toBeGreaterThan(0);
    expect(requestedWith.filter((c) => c === b.orgId).length, 'B was asked for in context B').toBeGreaterThan(0);

    // the choice survives a reload
    await page.reload();
    await expect(page.getByRole('combobox', { name: 'Ngữ cảnh' })).toContainText(nameB);
    await expect(page.getByRole('row', { name: new RegExp(`Học viên Beta ${suffix}`) })).toBeVisible();

    // back to "Cá nhân": the organisation page is closed, the organisation area disappears
    await org.switchContext(page, null);
    await expect(page.getByRole('heading', { level: 1, name: 'Bạn chưa có quyền xem trang này' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Tổ chức' })).toHaveCount(0);
    await expect(page.getByText(`Học viên Beta ${suffix}`)).toHaveCount(0);
  });

  test('F07 a stored context the person no longer belongs to falls back to "Cá nhân" without an error loop', async ({ page, seed, auth, uniqueEmail }) => {
    const person = await seed.verifiedLearner({ email: uniqueEmail('f07-stale') });
    const real = await seed.organization({ name: `Trung tâm Gamma ${Date.now().toString(36)}`, type: 'CENTER' });
    await seed.member({ orgId: real.orgId, userId: person.userId, roles: [{ role: 'LEARNER' }] });
    const gone = '99999999-9999-4999-8999-999999999999';
    const refused: string[] = [];
    page.on('response', (r) => {
      if (r.url().includes('/api/') && r.status() === 403) refused.push(`${r.request().method()} ${new URL(r.url()).pathname}`);
    });
    // before the app starts: this browser remembers an organisation of this person that they have since left
    await page.addInitScript(([userId, orgId]) => {
      localStorage.setItem('e9x.context', JSON.stringify({ userId, orgId }));
    }, [person.userId, gone]);
    await auth.loginAs(page, person);
    await expect(page.getByRole('combobox', { name: 'Ngữ cảnh' })).toContainText('Cá nhân');
    await page.reload();
    await expect(page.getByRole('combobox', { name: 'Ngữ cảnh' })).toContainText('Cá nhân');
    await expect(page.getByRole('heading', { level: 1, name: 'English9x' })).toBeVisible();
    expect(refused, 'no request was refused because of the stale context').toEqual([]);
    expect(await page.evaluate(() => localStorage.getItem('e9x.context'))).toBeNull();
  });

  test('F07 the context header is checked by the server on every request', async ({ seed, org, uniqueEmail }) => {
    const person = await seed.verifiedLearner({ email: uniqueEmail('f07-header') });
    const mine = await seed.organization({ type: 'CENTER' });
    const foreign = await seed.organization({ type: 'CENTER' });
    await seed.member({ orgId: mine.orgId, userId: person.userId, roles: [{ role: 'LEARNER' }] });
    const { token } = await org.apiLogin(person);
    expect((await org.api(token, 'GET', '/api/me/context', { org: mine.orgId })).body.contextOrgId).toBe(mine.orgId);
    expect((await org.api(token, 'GET', '/api/me/context')).body.contextOrgId).toBeNull();
    const notMember = await org.api(token, 'GET', '/api/me/context', { org: foreign.orgId });
    const nowhere = await org.api(token, 'GET', '/api/me/context', { org: '99999999-9999-4999-8999-999999999999' });
    const malformed = await org.api(token, 'GET', '/api/me/context', { org: 'not-a-uuid' });
    for (const r of [notMember, nowhere, malformed]) {
      expect(r.status).toBe(403);
      expect(r.body.type).toBe('urn:e9x:problem:forbidden');
    }
    expect(notMember.body).toEqual({ ...nowhere.body, instance: notMember.body.instance });
  });
});
