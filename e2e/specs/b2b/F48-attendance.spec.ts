// F48 — Điểm danh (spec §16.2): điểm danh 4 trạng thái → tỉ lệ chuyên cần trong báo cáo.
// The teacher takes attendance on the real class page (four states and a note), a correction is just a new mark; the rate table of the class is worked out
// per learner ((present + late) / (present + late + absent), excused left out). A learner only sees their own mark, and writes nothing.
import { test, expect } from '../../fixtures';

/** Today at hh:mm local, so the session shows on the schedule page (which starts at the beginning of today) whatever the time of day the suite runs. */
function todayAt(hour: number, minute: number): Date {
  const d = new Date();
  d.setHours(hour, minute, 0, 0);
  return d;
}

test.describe('F48 attendance', () => {
  test('F48 four states over two sessions: the rate of each learner in the class report', async ({ page, browser, seed, auth, org, uniqueEmail }) => {
    const suffix = Date.now().toString(36);
    const centerName = `Trung tâm F48 ${suffix}`;
    const center = await seed.organization({ name: centerName, type: 'CENTER' });
    const boss = await seed.verifiedLearner({ email: uniqueEmail('f48-boss'), displayName: 'Quản Trị F48' });
    const teacher = await seed.verifiedLearner({ email: uniqueEmail('f48-teacher'), displayName: 'Thầy Giáo F48' });
    const ann = await seed.verifiedLearner({ email: uniqueEmail('f48-ann'), displayName: 'An Chăm Chỉ' });
    const bao = await seed.verifiedLearner({ email: uniqueEmail('f48-bao'), displayName: 'Bảo Hay Vắng' });
    const chi = await seed.verifiedLearner({ email: uniqueEmail('f48-chi'), displayName: 'Chi Có Phép' });
    await seed.member({ orgId: center.orgId, userId: boss.userId, roles: [{ role: 'ORG_ADMIN' }] });
    await seed.member({ orgId: center.orgId, userId: teacher.userId, roles: [{ role: 'TEACHER', examCode: 'IELTS' }] });
    for (const u of [ann, bao, chi]) await seed.member({ orgId: center.orgId, userId: u.userId, roles: [{ role: 'LEARNER' }] });

    const bossT = await org.apiLogin(boss);
    const teacherT = await org.apiLogin(teacher);
    const annT = await org.apiLogin(ann);
    const made = await org.api(bossT.token, 'POST', `/api/orgs/${center.orgId}/classes`, { org: center.orgId, body: { name: `Lớp F48 ${suffix}`, teacherId: teacher.userId } });
    expect(made.status, made.text).toBe(201);
    const classId = made.body.id as string;
    expect((await org.api(teacherT.token, 'POST', `/api/orgs/${center.orgId}/classes/${classId}/members`, { org: center.orgId, body: { userIds: [ann.userId, bao.userId, chi.userId] } })).status).toBe(200);
    // two sessions earlier today (a session is marked once it has taken place); the second is the later one
    for (const [h, m] of [[0, 5], [0, 20]] as const) {
      const s = await org.api(teacherT.token, 'POST', `/api/orgs/${center.orgId}/classes/${classId}/sessions`, {
        org: center.orgId, body: { mode: 'OFFLINE', startsAt: todayAt(h, m).toISOString(), endsAt: todayAt(h, m + 10).toISOString(), room: `Phòng ${h}${m}` },
      });
      expect(s.status, s.text).toBe(201);
    }

    await auth.loginAs(page, teacher);
    await org.switchContext(page, centerName);
    await page.goto(`/org/classes/${classId}`);
    const rows = page.getByTestId('session-row');
    await expect(rows).toHaveCount(2);

    // 1) the first session: An present, Bảo absent, Chi excused (with a note); nothing is sent without a mark
    const first = rows.nth(0);
    await first.getByRole('button', { name: 'Điểm danh' }).click();
    const sheet1 = first.getByRole('form', { name: 'Điểm danh buổi học' });
    await sheet1.getByRole('button', { name: 'Lưu điểm danh' }).click();
    await expect(sheet1.getByText('Chọn trạng thái cho ít nhất một học viên.')).toBeVisible();
    const line = (sheet: typeof sheet1, name: string) => sheet.getByTestId('attendance-row').filter({ hasText: name });
    await line(sheet1, 'An Chăm Chỉ').getByLabel('Có mặt', { exact: true }).check();
    await line(sheet1, 'Bảo Hay Vắng').getByLabel('Vắng', { exact: true }).check();
    await line(sheet1, 'Chi Có Phép').getByLabel('Có phép', { exact: true }).check();
    await line(sheet1, 'Chi Có Phép').getByRole('textbox').fill('Bị ốm');
    await sheet1.getByRole('button', { name: 'Lưu điểm danh' }).click();
    await expect(sheet1.getByText('Đã lưu điểm danh.')).toBeVisible();

    // 2) the second: An late, Bảo present (Chi not marked)
    const second = rows.nth(1);
    await second.getByRole('button', { name: 'Điểm danh' }).click();
    const sheet2 = second.getByRole('form', { name: 'Điểm danh buổi học' });
    await line(sheet2, 'An Chăm Chỉ').getByLabel('Đi trễ', { exact: true }).check();
    await line(sheet2, 'Bảo Hay Vắng').getByLabel('Có mặt', { exact: true }).check();
    await sheet2.getByRole('button', { name: 'Lưu điểm danh' }).click();
    await expect(sheet2.getByText('Đã lưu điểm danh.')).toBeVisible();

    // 3) the rates: An 2 of 2 (late counts as attended), Bảo 1 of 2, Chi only an excused session: no rate
    const rate = (name: string) => page.getByTestId('rate-row').filter({ hasText: name });
    await expect(rate('An Chăm Chỉ').getByTestId('rate-value')).toHaveText('100%');
    await expect(rate('Bảo Hay Vắng').getByTestId('rate-value')).toHaveText('50%');
    await expect(rate('Chi Có Phép').getByTestId('rate-value')).toHaveText('–');

    // 4) a correction is a new mark: Bảo was there after all, in the first session
    await line(sheet1, 'Bảo Hay Vắng').getByLabel('Đi trễ', { exact: true }).check();
    await sheet1.getByRole('button', { name: 'Lưu điểm danh' }).click();
    await expect(sheet1.getByText('Đã lưu điểm danh.')).toBeVisible();
    await expect(rate('Bảo Hay Vắng').getByTestId('rate-value')).toHaveText('100%');
    // the sheet comes back with what was saved
    await page.reload();
    await page.getByTestId('session-row').nth(0).getByRole('button', { name: 'Điểm danh' }).click();
    const again = page.getByTestId('session-row').nth(0).getByRole('form', { name: 'Điểm danh buổi học' });
    await expect(line(again, 'Bảo Hay Vắng').getByLabel('Đi trễ', { exact: true })).toBeChecked();
    await expect(line(again, 'Chi Có Phép').getByRole('textbox')).toHaveValue('Bị ốm');

    // 5) the learner sees only their own mark on the schedule, and may not write or read the class's
    const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    const tab = await ctx.newPage();
    await auth.loginAs(tab, ann);
    await org.switchContext(tab, centerName);
    await tab.goto('/schedule');
    await expect(tab.getByTestId('schedule-item')).toHaveCount(2);
    await expect(tab.getByTestId('schedule-item').filter({ hasText: 'Điểm danh: Có mặt' })).toHaveCount(1);
    await expect(tab.getByTestId('schedule-item').filter({ hasText: 'Điểm danh: Đi trễ' })).toHaveCount(1);
    const sessions = (await org.api(annT.token, 'GET', `/api/orgs/${center.orgId}/classes/${classId}/sessions`, { org: center.orgId })).body as Array<{ id: string }>;
    expect(sessions).toHaveLength(2);
    const write = await org.api(annT.token, 'PUT', `/api/orgs/${center.orgId}/classes/${classId}/sessions/${sessions[0]!.id}/attendance`, {
      org: center.orgId, body: [{ userId: ann.userId, status: 'PRESENT' }],
    });
    expect(write.status).toBe(403);
    expect((await org.api(annT.token, 'GET', `/api/orgs/${center.orgId}/classes/${classId}/attendance`, { org: center.orgId })).status).toBe(403);
    await ctx.close();
  });
});
