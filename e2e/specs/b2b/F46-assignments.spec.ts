// F46 — Giao bài (spec §16.2): giao đề thi thử có hạn → học viên thấy → nộp trễ được đánh dấu trễ.
// The teacher gives the assignment on the real class page, the learner sees it on the real assignments page and sits the real exam (through the API: the
// attempt is what matters here, F25/F26 drive the exam room itself); the status shown on both screens comes from AttemptSubmitted and the due date.
import { test, expect } from '../../fixtures';

function localInput(d: Date): string {
  const x = new Date(d.getTime() - d.getTimezoneOffset() * 60_000);
  return x.toISOString().slice(0, 16);
}

test.describe('F46 assignments', () => {
  test('F46 an exam given with a due date: the learner sees it, hands in on time or late, and the teacher sees who did not', async ({ page, browser, seed, auth, org, uniqueEmail }) => {
    const suffix = Date.now().toString(36);
    const centerName = `Trung tâm F46 ${suffix}`;
    const center = await seed.organization({ name: centerName, type: 'CENTER' });
    const boss = await seed.verifiedLearner({ email: uniqueEmail('f46-boss'), displayName: 'Quản Trị F46' });
    const teacher = await seed.verifiedLearner({ email: uniqueEmail('f46-teacher'), displayName: 'Thầy Giáo F46' });
    // the learner holds a plan (the exam room needs an entitlement) and is a LEARNER of the centre
    const ann = await seed.learnerWithPlan({ courses: 1, examCode: 'IELTS' });
    const bao = await seed.verifiedLearner({ email: uniqueEmail('f46-bao'), displayName: 'Bảo Chưa Nộp' });
    await seed.member({ orgId: center.orgId, userId: boss.userId, roles: [{ role: 'ORG_ADMIN' }] });
    await seed.member({ orgId: center.orgId, userId: teacher.userId, roles: [{ role: 'TEACHER', examCode: 'IELTS' }] });
    for (const u of [ann, bao]) await seed.member({ orgId: center.orgId, userId: u.userId, roles: [{ role: 'LEARNER' }] });
    const test1 = await seed.publishedIeltsTest({ variant: 'AC' });

    const bossT = await org.apiLogin(boss);
    const teacherT = await org.apiLogin(teacher);
    const annT = await org.apiLogin(ann);
    const baoT = await org.apiLogin(bao);
    const made = await org.api(bossT.token, 'POST', `/api/orgs/${center.orgId}/classes`, { org: center.orgId, body: { name: `Lớp F46 ${suffix}`, teacherId: teacher.userId } });
    expect(made.status, made.text).toBe(201);
    const classId = made.body.id as string;
    expect((await org.api(teacherT.token, 'POST', `/api/orgs/${center.orgId}/classes/${classId}/members`, { org: center.orgId, body: { userIds: [ann.userId, bao.userId] } })).status).toBe(200);

    // 1) the teacher gives the exam on the class page, due in two days
    await auth.loginAs(page, teacher);
    await org.switchContext(page, centerName);
    await page.goto(`/org/classes/${classId}`);
    const give = page.getByRole('form', { name: 'Giao bài' });
    await give.getByRole('button', { name: 'Giao bài' }).click();
    await expect(give.getByText('Nhập mã bài và hạn nộp.')).toBeVisible();
    await give.getByLabel('Mã phiên bản đề thi').fill(test1.testVersionId);
    await give.getByLabel('Hạn nộp').fill(localInput(new Date(Date.now() + 2 * 86_400_000)));
    await give.getByRole('button', { name: 'Giao bài' }).click();
    await expect(page.getByText('Đã giao bài.')).toBeVisible();
    const row = page.getByTestId('assignment-row').filter({ hasText: test1.title });
    await expect(row.getByTestId('assignment-counts')).toContainText('2 học viên: đã nộp 0, trễ 0, chưa nộp 0');

    // a second one, due in a few seconds, made through the API (the date picker has a one-minute grain): this one will be handed in late
    const soon = await org.api(teacherT.token, 'POST', `/api/orgs/${center.orgId}/classes/${classId}/assignments`, {
      org: center.orgId, body: { testVersionId: test1.testVersionId, dueAt: new Date(Date.now() + 6_000).toISOString() },
    });
    expect(soon.status, soon.text).toBe(201);

    // 2) the learner sees both on the assignments page, still pending; the classmate is told too
    const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    const tab = await ctx.newPage();
    await auth.loginAs(tab, ann);
    await org.switchContext(tab, centerName);
    await tab.goto('/assignments');
    await expect(tab.getByTestId('my-assignment')).toHaveCount(2);
    await expect(tab.getByTestId('my-assignment').first()).toContainText('Chưa đến hạn');
    await expect.poll(async () => (await org.api(annT.token, 'GET', '/api/me/notifications', { org: center.orgId })).text, { timeout: 30_000 }).toContain('ASSIGNMENT_NEW');
    expect((await org.api(baoT.token, 'GET', '/api/me/assignments', { org: center.orgId })).body).toHaveLength(2);

    // 3) the due date of the second one passes; then the learner sits the exam in the centre's context and submits
    await new Promise((r) => setTimeout(r, 8_000));
    const started = await org.api(annT.token, 'POST', '/api/exam/attempts', { org: center.orgId, body: { testVersionId: test1.testVersionId } });
    expect(started.status, started.text).toBe(201);
    const submitted = await org.api(annT.token, 'POST', `/api/exam/attempts/${started.body.attemptId}/submit`, { org: center.orgId });
    expect(submitted.status, submitted.text).toBe(200);

    // 4) the status is on time for the first, LATE for the second, on both screens
    await expect.poll(async () => {
      const mine = (await org.api(annT.token, 'GET', '/api/me/assignments', { org: center.orgId })).body as Array<{ status: string }>;
      return mine.map((a) => a.status).sort();
    }, { timeout: 30_000 }).toEqual(['LATE', 'SUBMITTED']);
    await tab.reload();
    await expect(tab.getByTestId('my-assignment').filter({ hasText: 'Nộp trễ' })).toHaveCount(1);
    await expect(tab.getByTestId('my-assignment').filter({ hasText: 'Đã nộp' })).toHaveCount(1);
    await ctx.close();

    // the teacher's page: counts and the table of who did what; the classmate has handed in nothing (MISSING for the one past due)
    await page.reload();
    await expect(page.getByTestId('assignment-counts').filter({ hasText: '2 học viên: đã nộp 1, trễ 0, chưa nộp 0' }), 'the first: Ann on time, Bao not yet due').toHaveCount(1);
    await expect(page.getByTestId('assignment-counts').filter({ hasText: '2 học viên: đã nộp 0, trễ 1, chưa nộp 1' })).toHaveCount(1);
    const lateRow = page.getByTestId('assignment-row').filter({ hasText: 'trễ 1, chưa nộp 1' });
    await lateRow.getByRole('button', { name: 'Xem chi tiết' }).click();
    await expect(lateRow.getByTestId('report-row').filter({ hasText: 'Bảo Chưa Nộp' })).toContainText('Chưa nộp');
    await expect(lateRow.getByTestId('report-row').filter({ hasText: 'Nộp trễ' })).toHaveCount(1);

    // 5) who may do what: a learner gives nothing and reads no report
    const asLearner = await org.api(annT.token, 'POST', `/api/orgs/${center.orgId}/classes/${classId}/assignments`, {
      org: center.orgId, body: { testVersionId: test1.testVersionId, dueAt: new Date(Date.now() + 86_400_000).toISOString() },
    });
    expect(asLearner.status).toBe(403);
    expect((await org.api(annT.token, 'GET', `/api/orgs/${center.orgId}/assignments/${soon.body.id}`, { org: center.orgId })).status).toBe(403);
    // outside the centre's context the learner has no assignments
    expect((await org.api(annT.token, 'GET', '/api/me/assignments')).body).toEqual([]);
  });
});
