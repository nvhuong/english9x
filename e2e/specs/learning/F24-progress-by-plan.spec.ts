// F24 — Tiến trình theo gói (spec §16.2): "2 gói (cá nhân + suất tổ chức) hiển thị đúng; chuỗi ngày đúng; gói hết hạn → chỉ đọc; mua lại → nối tiếp".
//
// Through the real stack: two published courses, a learner who holds course A on a personal plan (order-sourced) and course B on a seat of an organisation's licence.
// The /progress page shows one card per plan, the right courses in each, the % done and the lesson to continue with. The organisation's ORG_ADMIN takes the seat back:
// that plan moves to "expired", read only, and what was learnt is still shown; the course bought again as a personal plan continues from the same place.
// The organisation's admin reads the learner's progress of the seat only, and a stranger reads nothing of it.
import AxeBuilder from '@axe-core/playwright';
import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { staffed } from '../../fixtures/content';
import { publishedCourse, publishedLesson, publishedToeicTestVersion } from '../../fixtures/course';
import { api } from '../../fixtures/org';

async function axeClean(page: Page) {
  const r = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  expect(r.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical').map((v) => `${v.id}: ${v.help}`)).toEqual([]);
}

test.describe('F24 progress by plan', () => {
  test('F24 a personal plan and an organisation seat are two cards with their own courses; an ended plan is read only and a course bought again continues', async ({ page, request, auth, org, seed, uniqueEmail }) => {
    test.setTimeout(300_000);
    const suffix = Date.now().toString(36);
    const s = await staffed(['TOEIC'], 'f24', { request, uniqueEmail, apiLogin: org.apiLogin });
    const mark = s.author.userId.slice(0, 6);
    const course = async (name: string) =>
      publishedCourse(s, 'FREE', {
        l1: await publishedLesson(s, 'TOEIC', `${name} one ${mark}`),
        test: await publishedToeicTestVersion(s, `${name} test ${mark}`),
        l2: await publishedLesson(s, 'TOEIC', `${name} two ${mark}`),
      }, `${name} ${mark}`);
    const a = await course('Personal course');
    const b = await course('Seat course');
    const lessonsOf = (c: any) => c.chapters.flatMap((ch: any) => ch.lessons.map((l: any) => l.id as string)) as string[]; // l1, test, l2

    const admin = await seed.sysAdmin({ email: uniqueEmail('f24-admin') });
    const boss = await seed.verifiedLearner({ email: uniqueEmail('f24-boss'), displayName: 'Quản Trị F24' });
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f24-learner'), displayName: 'Học Viên F24' });
    const stranger = await seed.verifiedLearner({ email: uniqueEmail('f24-stranger') });
    const orgName = `Doanh nghiệp F24 ${suffix}`;
    const company = await seed.organization({ name: orgName, type: 'ENTERPRISE' });
    await seed.member({ orgId: company.orgId, userId: boss.userId, roles: [{ role: 'ORG_ADMIN' }] });
    await seed.member({ orgId: company.orgId, userId: learner.userId, roles: [{ role: 'LEARNER' }] });
    const adminToken = (await org.apiLogin(admin)).token;
    const bossToken = (await org.apiLogin(boss)).token;
    const token = (await org.apiLogin(learner)).token;
    const strangerToken = (await org.apiLogin(stranger)).token;
    const created = await org.api(adminToken, 'POST', '/api/admin/org-licenses', {
      body: { orgId: company.orgId, seats: 1, months: 3, courseIds: [b.courseId], priceVnd: 3000000, activation: 'CONTRACT', contractNo: `HD-${suffix}` },
    });
    expect(created.status, created.text).toBe(201);
    const seats = `/api/orgs/${company.orgId}/licenses/${created.body.license.id}/seats`;
    const assigned = await org.api(bossToken, 'POST', seats, { org: company.orgId, body: { userId: learner.userId, courseIds: [b.courseId] } });
    expect(assigned.status, assigned.text).toBe(201);
    await seed.billing.entitlement({ userId: learner.userId, courseId: a.courseId, endsInDays: 30 });

    // one lesson of each course done
    const [a1, aTest] = lessonsOf(a);
    const [b1, bTest] = lessonsOf(b);
    expect((await api(token, 'POST', `/api/learning/lessons/${a1}/complete`)).status).toBe(200);
    expect((await api(token, 'POST', `/api/learning/lessons/${b1}/complete`)).status).toBe(200);
    expect((await api(token, 'POST', '/api/learning/heartbeat', { body: { courseId: a.courseId, lessonId: a1, timeZone: 'Asia/Ho_Chi_Minh' } })).status).toBe(200);

    // the page: two cards, each with its own course
    await auth.loginAs(page, learner);
    await page.goto('/progress');
    await expect(page.getByRole('heading', { level: 1, name: 'Tiến trình học' })).toBeVisible();
    const cards = page.getByTestId('plan-card');
    await expect(cards).toHaveCount(2);
    const personal = page.locator('[data-testid="plan-card"][data-source="ORDER"]').first();
    const seat = page.locator('[data-testid="plan-card"][data-source="SEAT"]');
    await expect(seat).toHaveCount(1);
    await expect(seat.getByText(orgName)).toBeVisible();
    await expect(seat.getByTestId('course-progress')).toHaveCount(1);
    await expect(seat.getByTestId('course-progress')).toContainText(`Seat course ${mark}`);
    await expect(personal.getByTestId('course-progress')).toContainText(`Personal course ${mark}`);
    await expect(personal.getByTestId('course-percent')).toHaveText('33%');
    await expect(seat.getByTestId('course-percent')).toHaveText('33%');
    await expect(personal.getByRole('link', { name: 'Học tiếp' })).toHaveAttribute('href', `/learn/lessons/${aTest}`);
    await expect(page.getByTestId('streak')).toContainText('Chuỗi ngày học: 1 ngày');
    await expect(page.getByTestId('weekly-minutes')).toBeVisible();
    await axeClean(page);

    // the seat is taken back: the plan is ended and read only, what was learnt is still there, nothing new opens
    expect((await org.api(bossToken, 'DELETE', `${seats}/${learner.userId}`, { org: company.orgId })).status).toBe(204);
    await page.reload();
    await expect(cards).toHaveCount(2);
    const ended = page.locator('[data-testid="plan-card"][data-expired="true"]');
    await expect(ended).toHaveCount(1);
    await expect(ended.getByText('Chỉ đọc')).toBeVisible();
    await expect(ended.getByTestId('course-percent')).toHaveText('33%');
    await expect(ended.getByRole('link', { name: 'Học tiếp' })).toHaveCount(0);
    await axeClean(page);
    expect((await api(token, 'GET', `/api/learning/lessons/${bTest}`)).status, 'the ended plan opens nothing new').toBe(403);

    // bought again as a personal plan: the same figures, and the next lesson to continue with
    await seed.billing.entitlement({ userId: learner.userId, courseId: b.courseId, endsInDays: 30 });
    await page.reload();
    const bought = page.locator('[data-testid="plan-card"][data-expired="false"]', { hasText: `Seat course ${mark}` });
    await expect(bought).toHaveCount(1);
    await expect(bought.getByTestId('course-percent')).toHaveText('33%');
    await expect(bought.getByRole('link', { name: 'Học tiếp' })).toHaveAttribute('href', `/learn/lessons/${bTest}`);

    // the API, and who may read it
    const mine = await api(token, 'GET', '/api/me/progress');
    expect(mine.status, mine.text).toBe(200);
    expect(mine.body.plans).toHaveLength(2);
    expect(mine.body.expired).toHaveLength(1);
    const staff = await org.api(bossToken, 'GET', `/api/orgs/${company.orgId}/learners/${learner.userId}/progress`, { org: company.orgId });
    expect(staff.status, staff.text).toBe(200);
    expect(staff.body.plans, 'only the seats this organisation issued').toHaveLength(0);
    expect(staff.body.expired).toHaveLength(1);
    expect(staff.text).not.toContain(`Personal course ${mark}`);
    expect((await org.api(strangerToken, 'GET', `/api/orgs/${company.orgId}/learners/${learner.userId}/progress`, { org: company.orgId })).status, 'not a member of the organisation').toBeGreaterThanOrEqual(403);
    expect((await org.api(strangerToken, 'GET', `/api/orgs/${company.orgId}/learners/${learner.userId}/progress`)).status, 'no organisation context').toBe(403);
  });
});
