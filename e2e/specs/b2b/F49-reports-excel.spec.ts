// F49 — Báo cáo lớp + Excel (spec §16.2): báo cáo theo học viên → tải .xlsx → số liệu khớp.
// The teacher opens the real report page of a class, downloads the real .xlsx and the file is read in the test: its cells are the figures of the page and of the
// JSON. The report holds the organisation's own data only: another centre's admin finds the class does not exist, a teacher who is not the class's teacher is refused.
import { readFileSync } from 'node:fs';
import { test, expect } from '../../fixtures';
import { readXlsx } from '../../helpers/xlsx';

/** Today at hh:mm local, so a session has already taken place whatever the time of day the suite runs. */
function todayAt(hour: number, minute: number): Date {
  const d = new Date();
  d.setHours(hour, minute, 0, 0);
  return d;
}

test.describe('F49 class report and Excel', () => {
  test('F49 the report page, the downloaded .xlsx and the JSON carry the same figures, and nothing of another organisation', async ({ page, seed, auth, org, uniqueEmail }) => {
    const suffix = Date.now().toString(36);
    const centerName = `Trung tâm F49 ${suffix}`;
    const center = await seed.organization({ name: centerName, type: 'CENTER' });
    const other = await seed.organization({ name: `Trung tâm khác F49 ${suffix}`, type: 'CENTER' });
    const boss = await seed.verifiedLearner({ email: uniqueEmail('f49-boss'), displayName: 'Quản Trị F49' });
    const teacher = await seed.verifiedLearner({ email: uniqueEmail('f49-teacher'), displayName: 'Thầy Giáo F49' });
    const stranger = await seed.verifiedLearner({ email: uniqueEmail('f49-teacher2'), displayName: 'Thầy Khác F49' });
    const ann = await seed.verifiedLearner({ email: uniqueEmail('f49-ann'), displayName: 'An Chăm Chỉ' });
    const bao = await seed.verifiedLearner({ email: uniqueEmail('f49-bao'), displayName: 'Bảo Hay Vắng' });
    const outsider = await seed.verifiedLearner({ email: uniqueEmail('f49-out'), displayName: 'Người Ngoài Rò Rỉ' });
    const otherBoss = await seed.verifiedLearner({ email: uniqueEmail('f49-oboss'), displayName: 'Quản Trị Khác F49' });
    await seed.member({ orgId: center.orgId, userId: boss.userId, roles: [{ role: 'ORG_ADMIN' }] });
    await seed.member({ orgId: center.orgId, userId: teacher.userId, roles: [{ role: 'TEACHER', examCode: 'IELTS' }] });
    await seed.member({ orgId: center.orgId, userId: stranger.userId, roles: [{ role: 'TEACHER', examCode: 'IELTS' }] });
    for (const u of [ann, bao]) await seed.member({ orgId: center.orgId, userId: u.userId, roles: [{ role: 'LEARNER' }] });
    await seed.member({ orgId: other.orgId, userId: otherBoss.userId, roles: [{ role: 'ORG_ADMIN' }, { role: 'TEACHER', examCode: 'IELTS' }] });
    await seed.member({ orgId: other.orgId, userId: outsider.userId, roles: [{ role: 'LEARNER' }] });

    const bossT = await org.apiLogin(boss);
    const teacherT = await org.apiLogin(teacher);
    const strangerT = await org.apiLogin(stranger);
    const otherT = await org.apiLogin(otherBoss);
    const made = await org.api(bossT.token, 'POST', `/api/orgs/${center.orgId}/classes`, { org: center.orgId, body: { name: `Lớp F49 ${suffix}`, teacherId: teacher.userId } });
    expect(made.status, made.text).toBe(201);
    const classId = made.body.id as string;
    expect((await org.api(teacherT.token, 'POST', `/api/orgs/${center.orgId}/classes/${classId}/members`, { org: center.orgId, body: { userIds: [ann.userId, bao.userId] } })).status).toBe(200);
    const madeOther = await org.api(otherT.token, 'POST', `/api/orgs/${other.orgId}/classes`, { org: other.orgId, body: { name: `Lớp khác F49 ${suffix}`, teacherId: otherBoss.userId } });
    expect(madeOther.status, madeOther.text).toBe(201);
    const otherClass = madeOther.body.id as string;

    // two sessions already held: An present + late (100%), Bảo present + absent (50%)
    const sessions: string[] = [];
    for (const [h, m] of [[0, 5], [0, 20]] as const) {
      const s = await org.api(teacherT.token, 'POST', `/api/orgs/${center.orgId}/classes/${classId}/sessions`, {
        org: center.orgId, body: { mode: 'OFFLINE', startsAt: todayAt(h, m).toISOString(), endsAt: todayAt(h, m + 10).toISOString(), room: `Phòng ${h}${m}` },
      });
      expect(s.status, s.text).toBe(201);
      sessions.push(s.body.id as string);
    }
    const mark = async (session: string, marks: Array<{ userId: string; status: string }>) =>
      expect((await org.api(teacherT.token, 'PUT', `/api/orgs/${center.orgId}/classes/${classId}/sessions/${session}/attendance`, { org: center.orgId, body: marks })).status).toBe(200);
    await mark(sessions[0]!, [{ userId: ann.userId, status: 'PRESENT' }, { userId: bao.userId, status: 'PRESENT' }]);
    await mark(sessions[1]!, [{ userId: ann.userId, status: 'LATE' }, { userId: bao.userId, status: 'ABSENT' }]);

    // 1) the page, reached from the class page
    await auth.loginAs(page, teacher);
    await org.switchContext(page, centerName);
    await page.goto(`/org/classes/${classId}`);
    await page.getByRole('link', { name: 'Báo cáo lớp' }).click();
    await expect(page.getByRole('heading', { level: 1, name: new RegExp(`Báo cáo lớp: Lớp F49 ${suffix}`) })).toBeVisible();
    const rows = page.getByTestId('report-row');
    await expect(rows).toHaveCount(2);
    const rowOf = (name: string) => rows.filter({ hasText: name });
    await expect(rowOf('An Chăm Chỉ').getByTestId('report-attendance')).toHaveText('100%');
    await expect(rowOf('Bảo Hay Vắng').getByTestId('report-attendance')).toHaveText('50%');
    await expect(rowOf('An Chăm Chỉ').getByTestId('report-progress')).toHaveText('Chưa có khoá');
    await expect(page.getByText('Người Ngoài Rò Rỉ')).toHaveCount(0);

    // 2) the .xlsx the button downloads: one sheet named after the class, the same figures
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: 'Tải Excel' }).click();
    const file = await download;
    expect(file.suggestedFilename()).toBe(`class-report-${classId}.xlsx`);
    const bytes = readFileSync((await file.path())!);
    expect(bytes.subarray(0, 2).toString(), 'an .xlsx is a zip').toBe('PK');
    const sheets = readXlsx(bytes);
    expect(sheets).toHaveLength(1);
    expect(sheets[0]!.name).toBe(`Lớp F49 ${suffix}`);
    const [head, ...body] = sheets[0]!.rows;
    expect(head![0]).toBe('Học viên');
    expect(body.map((r) => r[0]).sort()).toEqual(['An Chăm Chỉ', 'Bảo Hay Vắng']);
    const col = (label: string) => head!.indexOf(label);
    const line = (name: string) => body.find((r) => r[0] === name)!;
    expect(line('An Chăm Chỉ')[col('Chuyên cần')]).toBe(1);
    expect(line('Bảo Hay Vắng')[col('Chuyên cần')]).toBe(0.5);
    expect(line('An Chăm Chỉ')[col('Đi trễ')]).toBe(1);
    expect(line('Bảo Hay Vắng')[col('Vắng')]).toBe(1);
    expect(line('An Chăm Chỉ')[col('Phút học')]).toBe(0);
    expect(JSON.stringify(sheets)).not.toContain('Người Ngoài');

    // 3) the JSON says the same, number for number
    const json = await org.api(teacherT.token, 'GET', `/api/orgs/${center.orgId}/classes/${classId}/report`, { org: center.orgId });
    expect(json.status, json.text).toBe(200);
    for (const l of json.body.learners as Array<{ displayName: string; attendance: { rate: number; present: number; absent: number } }>) {
      expect(line(l.displayName)[col('Chuyên cần')]).toBe(l.attendance.rate);
      expect(line(l.displayName)[col('Có mặt')]).toBe(l.attendance.present);
      expect(line(l.displayName)[col('Vắng')]).toBe(l.attendance.absent);
    }
    expect(json.text).not.toContain(outsider.userId);

    // 4) who may ask: a teacher who is not this class's teacher and the other centre's admin are refused; a class of another organisation does not exist
    for (const suffixPath of ['/report', '/report.xlsx']) {
      expect((await org.api(strangerT.token, 'GET', `/api/orgs/${center.orgId}/classes/${classId}${suffixPath}`, { org: center.orgId })).status).toBe(403);
      expect((await org.api(otherT.token, 'GET', `/api/orgs/${center.orgId}/classes/${classId}${suffixPath}`, { org: other.orgId })).status).toBe(404);
      expect((await org.api(otherT.token, 'GET', `/api/orgs/${other.orgId}/classes/${classId}${suffixPath}`, { org: other.orgId })).status).toBe(404);
      expect((await org.api(teacherT.token, 'GET', `/api/orgs/${center.orgId}/classes/${classId}${suffixPath}`, { org: null })).status).toBe(403);
    }
    expect((await org.api(teacherT.token, 'GET', `/api/orgs/${center.orgId}/classes/${otherClass}/report`, { org: center.orgId })).status).toBe(404);
    expect((await org.api(otherT.token, 'GET', `/api/orgs/${other.orgId}/classes/${otherClass}/report.xlsx`, { org: other.orgId })).status).toBe(200);
  });
});
