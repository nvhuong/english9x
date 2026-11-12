// F47 — Lịch học (spec §16.2): tạo buổi online có link và buổi offline → học viên thấy lịch → nhận nhắc.
// The teacher adds the sessions on the real class page (the online one needs an https link, said on the form and again by the server); the learner of the
// class sees both on the real schedule page, somebody outside the class sees none; a session starting within the hour is announced to the learner and the
// teacher by the recurring session_reminder job (E9X_CLASSROOM_SESSIONJOBINTERVAL is a few seconds in this stack).
import { test, expect } from '../../fixtures';

function localInput(d: Date): string {
  const x = new Date(d.getTime() - d.getTimezoneOffset() * 60_000);
  return x.toISOString().slice(0, 16);
}

function tomorrowAt(hour: number): Date {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  d.setHours(hour, 0, 0, 0);
  return d;
}

test.describe('F47 schedule', () => {
  test('F47 an offline and an online session: the learner sees the schedule, and is reminded an hour before', async ({ page, browser, seed, auth, org, uniqueEmail }) => {
    const suffix = Date.now().toString(36);
    const centerName = `Trung tâm F47 ${suffix}`;
    const center = await seed.organization({ name: centerName, type: 'CENTER' });
    const boss = await seed.verifiedLearner({ email: uniqueEmail('f47-boss'), displayName: 'Quản Trị F47' });
    const teacher = await seed.verifiedLearner({ email: uniqueEmail('f47-teacher'), displayName: 'Thầy Giáo F47' });
    const ann = await seed.verifiedLearner({ email: uniqueEmail('f47-ann'), displayName: 'An Học Viên' });
    const bao = await seed.verifiedLearner({ email: uniqueEmail('f47-bao'), displayName: 'Bảo Ngoài Lớp' });
    await seed.member({ orgId: center.orgId, userId: boss.userId, roles: [{ role: 'ORG_ADMIN' }] });
    await seed.member({ orgId: center.orgId, userId: teacher.userId, roles: [{ role: 'TEACHER', examCode: 'IELTS' }] });
    for (const u of [ann, bao]) await seed.member({ orgId: center.orgId, userId: u.userId, roles: [{ role: 'LEARNER' }] });

    const bossT = await org.apiLogin(boss);
    const teacherT = await org.apiLogin(teacher);
    const annT = await org.apiLogin(ann);
    const baoT = await org.apiLogin(bao);
    const made = await org.api(bossT.token, 'POST', `/api/orgs/${center.orgId}/classes`, { org: center.orgId, body: { name: `Lớp F47 ${suffix}`, teacherId: teacher.userId } });
    expect(made.status, made.text).toBe(201);
    const classId = made.body.id as string;
    expect((await org.api(teacherT.token, 'POST', `/api/orgs/${center.orgId}/classes/${classId}/members`, { org: center.orgId, body: { userIds: [ann.userId] } })).status).toBe(200);

    // 1) the teacher adds an offline session with a room, then an online one; the form refuses an online session without an https link
    await auth.loginAs(page, teacher);
    await org.switchContext(page, centerName);
    await page.goto(`/org/classes/${classId}`);
    const form = page.getByRole('form', { name: 'Thêm buổi học' });
    await form.getByLabel('Bắt đầu').fill(localInput(tomorrowAt(9)));
    await form.getByLabel('Kết thúc').fill(localInput(tomorrowAt(11)));
    await form.getByLabel('Phòng học').fill('Phòng 301');
    await form.getByRole('button', { name: 'Thêm buổi học' }).click();
    await expect(page.getByText('Đã thêm buổi học.')).toBeVisible();
    await expect(page.getByTestId('session-row')).toHaveCount(1);

    await form.getByRole('combobox', { name: 'Hình thức' }).click();
    await page.getByRole('option', { name: 'Trực tuyến' }).click();
    await form.getByLabel('Bắt đầu').fill(localInput(tomorrowAt(14)));
    await form.getByLabel('Kết thúc').fill(localInput(tomorrowAt(15)));
    await form.getByLabel('Liên kết buổi học (https)').fill('http://meet.example.org/lop-f47');
    await form.getByRole('button', { name: 'Thêm buổi học' }).click();
    await expect(form.getByText('Buổi trực tuyến cần liên kết bắt đầu bằng https://.')).toBeVisible();
    await expect(page.getByTestId('session-row')).toHaveCount(1);
    await form.getByLabel('Liên kết buổi học (https)').fill('https://meet.example.org/lop-f47');
    await form.getByRole('button', { name: 'Thêm buổi học' }).click();
    await expect(page.getByTestId('session-row')).toHaveCount(2);
    await expect(page.getByTestId('session-row').filter({ hasText: 'Trực tuyến' }).getByRole('link', { name: 'Vào buổi học' })).toHaveAttribute('href', 'https://meet.example.org/lop-f47');

    // the server says the same without the form
    const refused = await org.api(teacherT.token, 'POST', `/api/orgs/${center.orgId}/classes/${classId}/sessions`, {
      org: center.orgId, body: { mode: 'ONLINE', startsAt: tomorrowAt(16).toISOString(), endsAt: tomorrowAt(17).toISOString(), meetingUrl: 'http://meet.example.org/x' },
    });
    expect(refused.status, refused.text).toBe(422);

    // 2) the learner of the class sees both on the schedule page; somebody outside the class sees none
    const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    const tab = await ctx.newPage();
    await auth.loginAs(tab, ann);
    await org.switchContext(tab, centerName);
    await tab.goto('/schedule');
    const items = tab.getByTestId('schedule-item');
    await expect(items).toHaveCount(2);
    await expect(items.filter({ hasText: 'Phòng: Phòng 301' })).toHaveCount(1);
    await expect(items.filter({ hasText: 'Trực tuyến' }).getByRole('link', { name: 'Vào buổi học' })).toHaveAttribute('href', 'https://meet.example.org/lop-f47');
    await expect(items.first()).toContainText('Học viên');
    const outsiderCtx = await browser.newContext({ ignoreHTTPSErrors: true });
    const outsiderTab = await outsiderCtx.newPage();
    await auth.loginAs(outsiderTab, bao);
    await org.switchContext(outsiderTab, centerName);
    await outsiderTab.goto('/schedule');
    await expect(outsiderTab.getByText('Chưa có buổi học nào.')).toBeVisible();
    await expect(outsiderTab.getByTestId('schedule-item')).toHaveCount(0);
    await outsiderCtx.close();
    // the teacher sees theirs as a teacher
    await page.goto('/schedule');
    await expect(page.getByTestId('schedule-item')).toHaveCount(2);
    await expect(page.getByTestId('schedule-item').first()).toContainText('Giáo viên');

    // a learner creates nothing; the outsider reads nothing of the class
    const byLearner = await org.api(annT.token, 'POST', `/api/orgs/${center.orgId}/classes/${classId}/sessions`, {
      org: center.orgId, body: { mode: 'OFFLINE', startsAt: tomorrowAt(18).toISOString(), endsAt: tomorrowAt(19).toISOString(), room: 'X' },
    });
    expect(byLearner.status).toBe(403);
    expect((await org.api(baoT.token, 'GET', `/api/orgs/${center.orgId}/classes/${classId}/sessions`, { org: center.orgId })).body).toEqual([]);

    // 3) a session that starts in 40 minutes: the learner and the teacher are reminded (once)
    const soon = await org.api(teacherT.token, 'POST', `/api/orgs/${center.orgId}/classes/${classId}/sessions`, {
      org: center.orgId, body: { mode: 'OFFLINE', startsAt: new Date(Date.now() + 40 * 60_000).toISOString(), endsAt: new Date(Date.now() + 100 * 60_000).toISOString(), room: 'Phòng gấp' },
    });
    expect(soon.status, soon.text).toBe(201);
    for (const t of [annT, teacherT]) {
      await expect.poll(async () => {
        const list = (await org.api(t.token, 'GET', '/api/me/notifications', { org: center.orgId })).text;
        return list.split('SESSION_REMINDER').length - 1;
      }, { timeout: 60_000 }).toBe(1);
    }
    // the sessions starting tomorrow are not announced yet; the outsider is told nothing
    expect((await org.api(baoT.token, 'GET', '/api/me/notifications', { org: center.orgId })).text).not.toContain('SESSION_REMINDER');
    await ctx.close();
  });
});
