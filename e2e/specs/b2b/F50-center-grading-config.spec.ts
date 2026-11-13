// F50 — Cấu hình chấm của trung tâm (spec §10.3, §4): the ORG_ADMIN of a CENTER chooses who grades its learners (its own teachers or English9x) and the
// Writing/Speaking mode. An ENTERPRISE's learners are always graded by English9x with AI: the SERVER refuses a centre grader or a teacher mode for it
// (400), whatever the screen shows. Nobody but the organisation's ORG_ADMIN reads or writes the setting.
import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';

const mainNav = (page: Page) => page.getByRole('navigation', { name: 'Điều hướng chính' });
const areaNav = (page: Page) => page.getByRole('navigation', { name: 'Điều hướng khu vực' });

test.describe('F50 centre grading configuration', () => {
  test('F50 a centre admin sets its grader and mode on the real page; an enterprise cannot choose a teacher mode (400) and its page is locked', async ({ page, browser, seed, auth, org, uniqueEmail }) => {
    const suffix = Date.now().toString(36);
    const centerName = `Trung tâm F50 ${suffix}`;
    const entName = `Doanh nghiệp F50 ${suffix}`;
    const center = await seed.organization({ name: centerName, type: 'CENTER' });
    const ent = await seed.organization({ name: entName, type: 'ENTERPRISE' });
    const centerBoss = await seed.verifiedLearner({ email: uniqueEmail('f50-cboss'), displayName: 'Quản Trị Trung Tâm' });
    const entBoss = await seed.verifiedLearner({ email: uniqueEmail('f50-eboss'), displayName: 'Quản Trị Doanh Nghiệp' });
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f50-learner'), displayName: 'Học Viên F50' });
    await seed.member({ orgId: center.orgId, userId: centerBoss.userId, roles: [{ role: 'ORG_ADMIN' }] });
    await seed.member({ orgId: center.orgId, userId: learner.userId, roles: [{ role: 'LEARNER' }] });
    await seed.member({ orgId: ent.orgId, userId: entBoss.userId, roles: [{ role: 'ORG_ADMIN' }] });
    const cb = await org.apiLogin(centerBoss);
    const eb = await org.apiLogin(entBoss);
    const lr = await org.apiLogin(learner);
    const path = (id: string) => `/api/orgs/${id}/grading-settings`;

    // 1) the centre admin sets its own teachers + TEACHER mode through the real page
    await auth.loginAs(page, centerBoss);
    await org.switchContext(page, centerName);
    await mainNav(page).getByRole('link', { name: 'Tổ chức' }).click();
    await areaNav(page).getByRole('link', { name: 'Cấu hình chấm bài' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Cấu hình chấm bài' })).toBeVisible();
    await page.getByRole('combobox', { name: 'Bên chấm' }).click();
    await page.getByRole('option', { name: 'Giáo viên của trung tâm' }).click();
    await page.getByRole('combobox', { name: 'Cách chấm Viết và Nói' }).click();
    await page.getByRole('option', { name: 'Chỉ giáo viên' }).click();
    await page.getByRole('button', { name: 'Lưu cấu hình' }).click();
    await expect(page.getByText('Đã lưu cấu hình chấm bài.')).toBeVisible();
    const saved = await org.api(cb.token, 'GET', path(center.orgId), { org: center.orgId });
    expect(saved.status, saved.text).toBe(200);
    expect(saved.body).toEqual({ grader: 'CENTER', mode: 'TEACHER' });
    await page.reload();
    await expect(page.getByRole('combobox', { name: 'Bên chấm' })).toContainText('Giáo viên của trung tâm');
    await expect(page.getByRole('combobox', { name: 'Cách chấm Viết và Nói' })).toContainText('Chỉ giáo viên');

    // 2) the SERVER, not the screen, refuses an enterprise: a centre grader and any teacher mode are a 400; English9x + AI is fine
    for (const body of [{ grader: 'CENTER' }, { grader: 'ENGLISH9X', mode: 'TEACHER' }, { grader: 'ENGLISH9X', mode: 'BOTH' }, { grader: 'CENTER', mode: 'AI' }]) {
      const r = await org.api(eb.token, 'PUT', path(ent.orgId), { org: ent.orgId, body });
      expect(r.status, `${JSON.stringify(body)} -> ${r.text}`).toBe(400);
      expect(r.body.type).toBe('urn:e9x:problem:grading-not-allowed');
    }
    const aiOnly = await org.api(eb.token, 'PUT', path(ent.orgId), { org: ent.orgId, body: { grader: 'ENGLISH9X', mode: 'AI' } });
    expect(aiOnly.status, aiOnly.text).toBe(200);
    expect(aiOnly.body).toEqual({ grader: 'ENGLISH9X', mode: 'AI' });
    // nothing from the refused calls was kept
    expect((await org.api(eb.token, 'GET', path(ent.orgId), { org: ent.orgId })).body).toEqual({ grader: 'ENGLISH9X', mode: 'AI' });

    // 3) the enterprise admin's page is locked to English9x + AI, with the reason
    const entCtx = await browser.newContext({ ignoreHTTPSErrors: true });
    const entPage = await entCtx.newPage();
    await auth.loginAs(entPage, entBoss);
    await org.switchContext(entPage, entName);
    await mainNav(entPage).getByRole('link', { name: 'Tổ chức' }).click();
    await areaNav(entPage).getByRole('link', { name: 'Cấu hình chấm bài' }).click();
    await expect(entPage.getByTestId('enterprise-lock')).toContainText('luôn do English9x chấm bằng AI');
    await expect(entPage.getByRole('combobox', { name: 'Bên chấm' })).toBeDisabled();
    await expect(entPage.getByRole('combobox', { name: 'Cách chấm Viết và Nói' })).toBeDisabled();
    await entCtx.close();

    // 4) nobody else reads or writes it: a plain member, the other organisation's admin, and the centre's setting from the enterprise's context
    const body = { grader: 'ENGLISH9X', mode: 'AI' };
    expect([403, 404]).toContain((await org.api(lr.token, 'PUT', path(center.orgId), { org: center.orgId, body })).status);
    expect([403, 404]).toContain((await org.api(lr.token, 'GET', path(center.orgId), { org: center.orgId })).status);
    expect([403, 404]).toContain((await org.api(eb.token, 'PUT', path(center.orgId), { org: ent.orgId, body })).status);
    expect([403, 404]).toContain((await org.api(eb.token, 'GET', path(center.orgId), { org: ent.orgId })).status);
    expect((await org.api(cb.token, 'GET', path(center.orgId), { org: center.orgId })).body, 'the refused writes changed nothing').toEqual({ grader: 'CENTER', mode: 'TEACHER' });
  });
});
