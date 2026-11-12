// F45 — Lớp/nhóm (spec §16.2): tạo lớp, thêm học viên, gán khoá cho cả lớp.
// Driven through the real class screens (create, roster, courses for the whole class) with the HTTP API next to them for what the screens cannot show
// (entitlements of the learners, the seat count, the refusals, another organisation's 404).
import { test, expect } from '../../fixtures';

test.describe('F45 classes and groups', () => {
  test('F45 a centre creates a class with a teacher, adds learners, and gives the whole class a course through the licence seats', async ({ page, seed, auth, org, uniqueEmail }) => {
    const suffix = Date.now().toString(36);
    const centerName = `Trung tâm F45 ${suffix}`;
    const sys = await seed.sysAdmin({ email: uniqueEmail('f45-sys') });
    const center = await seed.organization({ name: centerName, type: 'CENTER' });
    const boss = await seed.verifiedLearner({ email: uniqueEmail('f45-boss'), displayName: 'Quản Trị F45' });
    const teacher = await seed.verifiedLearner({ email: uniqueEmail('f45-teacher'), displayName: 'Cô Giáo F45' });
    const ann = await seed.verifiedLearner({ email: uniqueEmail('f45-ann'), displayName: 'An Học Viên F45' });
    const bao = await seed.verifiedLearner({ email: uniqueEmail('f45-bao'), displayName: 'Bảo Học Viên F45' });
    await seed.member({ orgId: center.orgId, userId: boss.userId, roles: [{ role: 'ORG_ADMIN' }] });
    await seed.member({ orgId: center.orgId, userId: teacher.userId, roles: [{ role: 'TEACHER', examCode: 'IELTS' }] });
    for (const u of [ann, bao]) await seed.member({ orgId: center.orgId, userId: u.userId, roles: [{ role: 'LEARNER' }] });
    const course = await seed.billing.course();
    const s = await org.apiLogin(sys);
    const lic = await org.api(s.token, 'POST', '/api/admin/org-licenses', {
      body: { orgId: center.orgId, seats: 3, months: 3, courseIds: [course.courseId], priceVnd: 3000000, activation: 'CONTRACT', contractNo: `HD-${suffix}` },
    });
    expect(lic.status, lic.text).toBe(201);

    // 1) create the class on the real page (a centre's class needs a teacher)
    await auth.loginAs(page, boss);
    await org.switchContext(page, centerName);
    await page.goto('/org/classes');
    const create = page.getByRole('form', { name: 'Tạo lớp' });
    await create.getByRole('button', { name: 'Tạo lớp' }).click();
    await expect(create.getByText('Vui lòng nhập tên.')).toBeVisible();
    await expect(create.getByText('Chọn giáo viên phụ trách.')).toBeVisible();
    await create.getByLabel('Tên').fill('IELTS 6.5 sáng thứ ba');
    await create.getByRole('combobox', { name: 'Giáo viên phụ trách' }).click();
    await page.getByRole('option', { name: /Cô Giáo F45/ }).click();
    await create.getByRole('button', { name: 'Tạo lớp' }).click();
    await expect(page.getByText('Đã tạo IELTS 6.5 sáng thứ ba.')).toBeVisible();
    const row = page.getByTestId('class-row').filter({ hasText: 'IELTS 6.5 sáng thứ ba' });
    await expect(row).toContainText('Lớp');

    // 2) add the learners on the class page
    await row.getByRole('link', { name: 'IELTS 6.5 sáng thứ ba' }).click();
    for (const who of ['An Học Viên F45', 'Bảo Học Viên F45']) {
      const add = page.getByRole('form', { name: 'Thêm học viên' });
      await add.getByRole('combobox', { name: 'Thành viên của tổ chức' }).click();
      await page.getByRole('option', { name: new RegExp(who) }).click();
      await add.getByRole('button', { name: 'Thêm vào lớp' }).click();
      await expect(page.getByTestId('roster-row').filter({ hasText: who })).toBeVisible();
    }
    await expect(page.getByTestId('roster-row')).toHaveCount(2);

    // 3) give the whole class the course: one seat each, the P5.3 path
    const courses = page.getByRole('form', { name: 'Khoá học cho cả lớp' });
    await courses.getByRole('combobox', { name: 'Giấy phép' }).click();
    await page.getByRole('option', { name: /Giấy phép 3 suất/ }).click();
    await courses.getByRole('checkbox', { name: new RegExp(`^${course.code}`) }).check();
    await courses.getByRole('button', { name: 'Gán khoá cho cả lớp' }).click();
    await expect(page.getByText('Đã gán suất cho 2 học viên; 0 học viên đã có suất từ trước.')).toBeVisible();
    for (const u of [ann, bao]) {
      const t = await org.apiLogin(u);
      const ents = await org.api(t.token, 'GET', '/api/me/entitlements');
      expect(ents.body.filter((e: any) => e.active && e.courseId === course.courseId), `${u.email} holds the course`).toHaveLength(1);
      expect((await seed.billing.gate({ userId: u.userId, courseId: course.courseId })).activeFor).toBe(true);
    }
    const lics = await org.api((await org.apiLogin(boss)).token, 'GET', `/api/orgs/${center.orgId}/licenses`, { org: center.orgId });
    expect(lics.body[0].seatsUsed, 'the seats of the licence are used').toBe(2);

    // a second time changes nothing: they already hold their seats
    await courses.getByRole('button', { name: 'Gán khoá cho cả lớp' }).click();
    await expect(page.getByText('Đã gán suất cho 0 học viên; 2 học viên đã có suất từ trước.')).toBeVisible();

    // 4) who may do what, through the API: the teacher sees their class, a learner nothing of it, another organisation's admin gets 404
    const bossT = await org.apiLogin(boss);
    const teacherT = await org.apiLogin(teacher);
    const annT = await org.apiLogin(ann);
    const classes = await org.api(bossT.token, 'GET', `/api/orgs/${center.orgId}/classes`, { org: center.orgId });
    const classId = classes.body[0].id as string;
    const asTeacher = await org.api(teacherT.token, 'GET', `/api/orgs/${center.orgId}/classes/${classId}/members`, { org: center.orgId });
    expect(asTeacher.status, asTeacher.text).toBe(200);
    expect(asTeacher.text, 'a teacher reads names, not addresses').not.toContain('@example.test');
    expect((await org.api(annT.token, 'GET', `/api/orgs/${center.orgId}/classes/${classId}/members`, { org: center.orgId })).status).toBe(403);
    expect((await org.api(annT.token, 'POST', `/api/orgs/${center.orgId}/classes/${classId}/members`, { org: center.orgId, body: { userIds: [ann.userId] } })).status).toBe(403);
    expect((await org.api(annT.token, 'POST', `/api/orgs/${center.orgId}/classes`, { org: center.orgId, body: { name: 'Học viên tự tạo', teacherId: teacher.userId } })).status).toBe(403);
    expect((await org.api(teacherT.token, 'POST', `/api/orgs/${center.orgId}/classes/${classId}/courses`, { org: center.orgId, body: { courseIds: [course.courseId], licenseId: lics.body[0].id } })).status, 'a teacher does not spend seats').toBe(403);
    const other = await seed.organization({ name: `Trung tâm khác F45 ${suffix}`, type: 'CENTER' });
    const otherBoss = await seed.verifiedLearner({ email: uniqueEmail('f45-other') });
    await seed.member({ orgId: other.orgId, userId: otherBoss.userId, roles: [{ role: 'ORG_ADMIN' }] });
    const ot = await org.apiLogin(otherBoss);
    // Asserting the context of an organisation you hold no grant in is denied before any resource is looked at: 403, the convention F08 fixes (F08-data-isolation line 47:
    // `x-org-context` of another org → 403). It leaks nothing about the class (the request never reaches it); flipping this to 404 would contradict F08's passing assertion.
    expect((await org.api(ot.token, 'GET', `/api/orgs/${center.orgId}/classes/${classId}`, { org: center.orgId })).status, 'a context the person holds no grant in').toBe(403);
    // In their own context, a class that belongs to another organisation is simply not found — existence is not leaked (F44 convention).
    expect((await org.api(ot.token, 'GET', `/api/orgs/${other.orgId}/classes/${classId}`, { org: other.orgId })).status, 'a class that is not theirs').toBe(404);
  });

  test('F45 an enterprise has groups, and a group needs no teacher', async ({ page, seed, auth, org, uniqueEmail }) => {
    const suffix = Date.now().toString(36);
    const name = `Doanh nghiệp F45 ${suffix}`;
    const company = await seed.organization({ name, type: 'ENTERPRISE' });
    const boss = await seed.verifiedLearner({ email: uniqueEmail('f45e-boss'), displayName: 'Quản Trị Doanh Nghiệp' });
    await seed.member({ orgId: company.orgId, userId: boss.userId, roles: [{ role: 'ORG_ADMIN' }] });
    await auth.loginAs(page, boss);
    await org.switchContext(page, name);
    await page.goto('/org/classes');
    const form = page.getByRole('form', { name: 'Tạo nhóm' });
    await expect(form.getByRole('combobox', { name: 'Giáo viên phụ trách' }), 'no teacher box for a group').toHaveCount(0);
    await form.getByLabel('Tên').fill('Phòng Kinh doanh');
    await form.getByRole('button', { name: 'Tạo nhóm' }).click();
    await expect(page.getByText('Đã tạo Phòng Kinh doanh.')).toBeVisible();
    await expect(page.getByTestId('class-row').filter({ hasText: 'Phòng Kinh doanh' })).toContainText('Nhóm');
    // the server refuses a teacher for a group whatever the screen does
    const token = (await org.apiLogin(boss)).token;
    const refused = await org.api(token, 'POST', `/api/orgs/${company.orgId}/classes`, { org: company.orgId, body: { name: 'Có giáo viên', teacherId: boss.userId } });
    expect(refused.status).toBe(422);
    expect(refused.body.type).toBe('urn:e9x:problem:group-has-no-teacher');
  });
});
