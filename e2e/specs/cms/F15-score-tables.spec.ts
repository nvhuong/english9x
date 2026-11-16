// F15 — Bảng quy đổi (spec §16.2): "Tạo bảng mặc định + ghi đè → duyệt → kết quả thi dùng đúng bảng; sửa bảng không đổi kết quả cũ".
//
// What the score tables of P2.5 do, through the real stack: the author makes the DEFAULT table of an exam skill on the screen, the server refuses a table
// the domain's TableConverter would refuse (a gap in the raw values), the table is published only by a review, and editing a published table makes a new
// draft version and never touches the published one; the new version replaces it when published.
// The override of one test (W10 T6): the SYS_ADMIN attaches the override of Listening to a published test on the administrator's screen (an author cannot,
// a second attach is refused), an author finds it on the test builder screen and writes its rows on the score-table editor, a review publishes it; a learner
// who sits the test is scored with the override and the result keeps its version; editing the override (version 2, published) does not change that result,
// and the next learner is scored with version 2.
import { test, expect } from '../../fixtures';
import { staffed } from '../../fixtures/content';
import { api } from '../../fixtures/org';
import { publishedIeltsTestWithId } from '../../fixtures/ai-grading';
import { poll } from '../../helpers/env';

/** Sits the test as a learner with a plan: Listening answered right, Reading answered right ("not given"), Writing and Speaking left. Returns the attempt. */
async function sit(token: string, testVersionId: string): Promise<string> {
  const started = await api(token, 'POST', '/api/exam/attempts', { body: { testVersionId } });
  expect(started.status, started.text).toBe(201);
  const attemptId = started.body.attemptId as string;
  const questionOf = (view: any) => view.currentSection.groups[0].questions[0].questionId as string;
  const answer = async (view: any, value: unknown) => {
    await new Promise((r) => setTimeout(r, 1500));
    const answeredAt = new Date(Date.parse(view.serverNow) + 1000).toISOString();
    const put = await api(token, 'PUT', `/api/exam/attempts/${attemptId}/responses/${questionOf(view)}`, { body: { answer: value, answeredAt } });
    expect(put.status, put.text).toBe(204);
  };
  await answer(started.body, ['SMITH', '0123 456']);
  const reading = await api(token, 'POST', `/api/exam/attempts/${attemptId}/sections/LISTENING/finish`);
  expect(reading.status, reading.text).toBe(200);
  await answer(reading.body, 'not given');
  const submitted = await api(token, 'POST', `/api/exam/attempts/${attemptId}/submit`);
  expect(submitted.status, submitted.text).toBe(200);
  return attemptId;
}

type Version = { versionNo: number; status: string; versionId: string; rows: Array<{ raw: number; scaled: number }> };

test.describe('F15 score tables', () => {
  test('F15 the author makes the default table on the screen; a gap in the raw values is refused with the line; a review publishes it; an edit makes version 2', async ({ page, request, auth, seed, org, uniqueEmail }) => {
    test.setTimeout(180_000);
    const s = await staffed(['IELTS'], 'f15-ui', { request, uniqueEmail, apiLogin: org.apiLogin });
    const versions = async (id: string) => (await org.api(s.reviewer.token, 'GET', `/api/cms/score-tables/${id}/versions`, { org: s.orgId })).body as Version[];
    await auth.loginAs(page, s.authorLogin);

    await page.goto('/cms/score-tables/new');
    await expect(page.getByRole('heading', { level: 1, name: 'Bảng quy đổi mới' })).toBeVisible();
    await page.getByRole('combobox', { name: 'Kỳ thi' }).click();
    await page.getByRole('option', { name: 'IELTS', exact: true }).click();
    await page.getByRole('combobox', { name: 'Điểm cần quy đổi' }).click();
    await page.getByRole('option', { name: 'LISTENING', exact: true }).click();

    // raw 0, 1, 3: a learner with 2 correct answers would have no score. The server says so; nothing is created
    await page.getByLabel(/^Các dòng của bảng/).fill('0 0\n1 4.5\n3 5.5');
    await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
    const alert = page.getByRole('alert').filter({ hasText: 'Bảng chưa đúng' });
    await expect(alert.getByText(/contiguous from 0, missing 2/)).toBeVisible();
    await expect(page).toHaveURL(/\/cms\/score-tables\/new$/);

    // a score that falls as the raw score rises is refused too
    await page.getByLabel(/^Các dòng của bảng/).fill('0 5\n1 4');
    await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
    await expect(alert.getByText(/non-decreasing/)).toBeVisible();

    await page.getByLabel(/^Các dòng của bảng/).fill('0 0\n1 4.5\n2 5\n3 5.5');
    await expect(page.getByRole('table', { name: 'Bảng quy đổi sẽ lưu' }).getByRole('row')).toHaveCount(5);
    await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
    await expect(page).toHaveURL(/\/cms\/score-tables\/[0-9a-f-]{36}$/);
    await expect(page.getByText('Bản nháp', { exact: true })).toBeVisible();
    const tableId = page.url().split('/').pop()!;

    // what the screen saved is what the server holds: the EXAM scope, the rows in order
    const saved = await org.api(s.author.token, 'GET', `/api/cms/score-tables/${tableId}`, { org: s.orgId });
    expect(saved.status, saved.text).toBe(200);
    expect(saved.body).toMatchObject({ examCode: 'IELTS', skill: 'LISTENING', scope: 'EXAM', function: 'TABLE', status: 'DRAFT', versionNo: 1 });
    expect(saved.body.rows).toEqual([{ raw: 0, scaled: 0 }, { raw: 1, scaled: 4.5 }, { raw: 2, scaled: 5 }, { raw: 3, scaled: 5.5 }]);
    const v1 = saved.body.versionId as string;

    // submit it: the version is locked while the reviewer decides
    await page.getByRole('button', { name: 'Gửi duyệt' }).click();
    await expect(page.getByText(/Đã gửi duyệt/)).toBeVisible();
    const locked = await org.api(s.author.token, 'PUT', `/api/cms/score-tables/${tableId}`, { org: s.orgId, body: { examCode: 'IELTS', skill: 'LISTENING', scope: 'EXAM', rows: [{ raw: 0, scaled: 1 }] } });
    expect(locked.status).toBe(409);
    expect(locked.body.type).toBe('urn:e9x:problem:version-under-review');
    expect((await org.api(s.reviewer.token, 'PUT', `/api/cms/score-tables/${tableId}`, { org: s.orgId, body: { rows: [{ raw: 0, scaled: 1 }], scope: 'EXAM' } })).status, 'a reviewer does not write').toBe(403);

    // the real review publishes it (the review screens are P2.9's)
    await seed.reviewVersion({ type: 'score_table', versionId: v1, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
    await page.reload();
    await expect(page.getByText('Đã xuất bản', { exact: true })).toBeVisible();
    await expect(page.getByText(/Bản này đã xuất bản và không sửa được/)).toBeVisible();

    // editing it makes a new draft version; the published one is untouched
    await page.getByLabel(/^Các dòng của bảng/).fill('0 0\n1 5\n2 5.5\n3 6');
    await page.getByRole('button', { name: 'Lưu thành phiên bản mới' }).click();
    await expect(page.getByText(/Đã tạo bản nháp mới, phiên bản 2/)).toBeVisible();
    let all = await versions(tableId);
    expect(all.map((v) => [v.versionNo, v.status])).toEqual([[1, 'PUBLISHED'], [2, 'DRAFT']]);
    expect(all[0]!.versionId).toBe(v1);
    expect(all[0]!.rows).toEqual(saved.body.rows);
    expect(all[1]!.rows).toEqual([{ raw: 0, scaled: 0 }, { raw: 1, scaled: 5 }, { raw: 2, scaled: 5.5 }, { raw: 3, scaled: 6 }]);

    // version 2 replaces version 1 when published: version 1 is archived, its rows still readable
    await seed.reviewVersion({ type: 'score_table', versionId: all[1]!.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
    all = await versions(tableId);
    expect(all.map((v) => [v.versionNo, v.status])).toEqual([[1, 'ARCHIVED'], [2, 'PUBLISHED']]);
    expect(all[0]!.rows).toEqual(saved.body.rows);

    // the list shows the latest version of each table — assert THIS table's own row link
    // (the list also shows any English9x default table of the same skill, so match by href, not skill name alone)
    await page.goto('/cms/score-tables');
    const ownLink = page.locator(`a[href="/cms/score-tables/${tableId}"]`);
    await expect(ownLink).toBeVisible();
    await expect(ownLink).toHaveText('LISTENING');
  });

  test('F15 the scope TEST is not made with POST (an override is attached first); the other refusals are by path; a score a function computes has no table', async ({ request, org, uniqueEmail }) => {
    const s = await staffed(['IELTS', 'TOEIC'], 'f15-api', { request, uniqueEmail, apiLogin: org.apiLogin });
    const post = (token: string, body: unknown) => org.api(token, 'POST', '/api/cms/score-tables', { org: s.orgId, body });
    const rows = [{ raw: 0, scaled: 0 }, { raw: 1, scaled: 100 }];

    const test_ = await post(s.author.token, { examCode: 'TOEIC', skill: 'READING', scope: 'TEST', testId: '00000000-0000-0000-0000-000000000001', rows });
    expect(test_.status, test_.text).toBe(422);
    expect(test_.body.errors.map((e: { field: string }) => e.field)).toEqual(['scope']);

    const wrongSkill = await post(s.author.token, { examCode: 'TOEIC', skill: 'READING_AC', scope: 'EXAM', rows });
    expect(wrongSkill.status).toBe(422);
    expect(wrongSkill.body.errors.map((e: { field: string }) => e.field)).toEqual(['skill']);
    const gap = await post(s.author.token, { examCode: 'TOEIC', skill: 'READING', scope: 'EXAM', rows: [{ raw: 0, scaled: 0 }, { raw: 2, scaled: 100 }] });
    expect(gap.status).toBe(422);
    expect(gap.body.errors[0]).toMatchObject({ field: 'rows' });
    const text = await post(s.author.token, { examCode: 'TOEIC', skill: 'READING', scope: 'EXAM', rows: [{ raw: 0, scaled: '0' }] });
    expect(text.status).toBe(422);
    expect(text.body.errors[0]).toMatchObject({ field: 'rows[0].scaled' });

    // none of the refusals wrote a table
    const list = await org.api(s.author.token, 'GET', '/api/cms/score-tables?exam=TOEIC', { org: s.orgId });
    expect(list.body.items.filter((i: { function: string }) => i.function === 'TABLE')).toEqual([]);

    // an author of another exam, and a reviewer, cannot write; the aggregate scores are functions of the domain
    const ielts = await staffed(['IELTS'], 'f15-other', { request, uniqueEmail, apiLogin: org.apiLogin });
    expect((await post(ielts.author.token, { examCode: 'TOEIC', skill: 'READING', scope: 'EXAM', rows })).status).toBe(403);
    expect((await post(s.reviewer.token, { examCode: 'TOEIC', skill: 'READING', scope: 'EXAM', rows })).status).toBe(403);
    const overall = (await org.api(s.author.token, 'GET', '/api/cms/score-tables?exam=TOEIC', { org: s.orgId })).body.items.find((i: { skill: string }) => i.skill === 'TOTAL');
    expect(overall).toMatchObject({ function: 'SUM', status: 'PUBLISHED' });
    const put = await org.api(s.author.token, 'PUT', `/api/cms/score-tables/${overall.tableId}`, { org: s.orgId, body: { scope: 'EXAM', rows } });
    expect(put.status).toBe(422);
  });

  test('F15 the administrator attaches an override to a published test, an author writes it, the learner result uses it and keeps its version, and editing it changes no old result', async ({ page, browser, request, auth, seed, org, uniqueEmail, gemini }) => {
    // two attempts, each waits out the two minutes of late-sync grace (e9x.exam.late-sync-grace) for real; the fake Gemini is held because Writing and Speaking are graded by the AI too
    test.setTimeout(720_000);
    await gemini.reset();
    const s = await staffed(['IELTS'], 'f15-ov', { request, uniqueEmail, apiLogin: org.apiLogin });
    const { testId, versionId: testVersionId } = await publishedIeltsTestWithId(s, `F15 ${Math.random().toString(36).slice(2, 8)}`);
    const admin = await seed.sysAdmin();
    const adminToken = (await org.apiLogin({ email: admin.email, password: admin.password })).token;
    const latest = async (tableId: string) => (await org.api(s.author.token, 'GET', `/api/cms/score-tables/${tableId}`, { org: s.orgId })).body as Version;
    const versions = async (tableId: string) => (await org.api(s.reviewer.token, 'GET', `/api/cms/score-tables/${tableId}/versions`, { org: s.orgId })).body as Version[];

    // an author cannot attach an override: it is the administrator's
    const refused = await org.api(s.author.token, 'POST', '/api/cms/score-tables/overrides', { org: s.orgId, body: { testId, skill: 'LISTENING' } });
    expect(refused.status, refused.text).toBe(403);

    // the administrator attaches the override of Listening on the screen
    const adminPage = await (await browser.newContext({ ignoreHTTPSErrors: true })).newPage();
    await auth.loginAs(adminPage, admin);
    await adminPage.goto('/admin/score-overrides');
    await expect(adminPage.getByRole('heading', { level: 1, name: 'Gắn bảng quy đổi ghi đè cho đề' })).toBeVisible();
    await adminPage.getByLabel(/^Mã đề/).fill(testId);
    await adminPage.getByRole('combobox', { name: 'Kỳ thi' }).click();
    await adminPage.getByRole('option', { name: 'IELTS', exact: true }).click();
    await adminPage.getByRole('combobox', { name: 'Điểm cần ghi đè' }).click();
    await adminPage.getByRole('option', { name: 'LISTENING', exact: true }).click();
    const attaching = adminPage.waitForResponse((r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/cms/score-tables/overrides');
    await adminPage.getByRole('button', { name: 'Gắn bảng ghi đè' }).click();
    const attached = await attaching;
    expect(attached.status(), await attached.text()).toBe(201);
    const tableId = ((await attached.json()) as { tableId: string }).tableId;
    await expect(adminPage.getByText(/Đã gắn bảng ghi đè LISTENING/)).toBeVisible();
    await expect(adminPage.getByRole('list', { name: 'Các bảng ghi đè của đề' }).getByText('Chưa có dòng nào')).toBeVisible();
    // one override per test and skill
    const twice = await org.api(adminToken, 'POST', '/api/cms/score-tables/overrides', { body: { testId, skill: 'LISTENING' } });
    expect(twice.status, twice.text).toBe(409);
    expect(twice.body.type).toBe('urn:e9x:problem:score-table-exists');
    // Reading of the Academic variant too, written entirely over the API (the screen path is the same)
    const readingAttached = await org.api(adminToken, 'POST', '/api/cms/score-tables/overrides', { body: { testId, skill: 'READING_AC' } });
    expect(readingAttached.status, readingAttached.text).toBe(201);
    const readingTableId = readingAttached.body.tableId as string;
    const readingWritten = await org.api(s.author.token, 'PUT', `/api/cms/score-tables/${readingTableId}`, {
      org: s.orgId,
      body: { examCode: 'IELTS', skill: 'READING_AC', scope: 'TEST', testId, rows: [{ raw: 0, scaled: 0 }, { raw: 1, scaled: 6 }] },
    });
    expect(readingWritten.status, readingWritten.text).toBe(201);
    await seed.reviewVersion({ type: 'score_table', versionId: readingWritten.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });

    // the author finds the override on the test builder screen and writes its rows on the editor
    await auth.loginAs(page, s.authorLogin);
    await page.goto(`/cms/tests/${testId}`);
    await expect(page.getByRole('heading', { level: 2, name: 'Bảng quy đổi ghi đè' })).toBeVisible();
    await expect(page.getByText('Chưa có dòng nào')).toBeVisible();
    await page.getByRole('link', { name: 'Mở bảng ghi đè LISTENING' }).click();
    await expect(page).toHaveURL(new RegExp(`/cms/score-tables/${tableId}\\?test=${testId}$`));
    await expect(page.getByText('Bảng ghi đè của một đề')).toBeVisible();
    // rows the table cannot take are refused with the reason, nothing is created
    await page.getByLabel(/^Các dòng của bảng/).fill('0 0\n2 6.5');
    await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
    await expect(page.getByRole('alert').filter({ hasText: 'Bảng chưa đúng' }).getByText(/contiguous from 0, missing 1/)).toBeVisible();
    await page.getByLabel(/^Các dòng của bảng/).fill('0 0\n1 6.5');
    const writing = page.waitForResponse((r) => r.request().method() === 'PUT' && new URL(r.url()).pathname === `/api/cms/score-tables/${tableId}` && r.status() === 201);
    await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
    await writing;
    await expect(page).toHaveURL(new RegExp(`/cms/score-tables/${tableId}$`));
    await expect(page.getByText('Bản nháp', { exact: true })).toBeVisible();
    const v1 = await latest(tableId);
    expect(v1).toMatchObject({ scope: 'TEST', testId, skill: 'LISTENING', versionNo: 1, status: 'DRAFT', rows: [{ raw: 0, scaled: 0 }, { raw: 1, scaled: 6.5 }] });
    await page.getByRole('button', { name: 'Gửi duyệt' }).click();
    await expect(page.getByText(/Đã gửi duyệt/)).toBeVisible();
    await seed.reviewVersion({ type: 'score_table', versionId: v1.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
    expect((await latest(tableId)).status).toBe('PUBLISHED');

    // a learner sits the test: the result uses the override and keeps its version
    const a = await seed.learnerWithPlan({ courses: 1 });
    const tokenA = (await org.apiLogin({ email: a.email, password: a.password })).token;
    const attemptA = await sit(tokenA, testVersionId);
    const scoresA = await poll('the scores of learner A', async () => {
      const r = await seed.grading.scores(attemptA);
      return r.graded ? r : null;
    }, { timeoutMs: 300_000, intervalMs: 2_000 });
    const listeningA = scoresA.scores.find((x) => x.skill === 'LISTENING')!;
    expect(listeningA, 'the override, not the default table of the exam').toMatchObject({ raw: 1, rawMax: 1, scaled: 6.5, scoreTableVersionId: v1.versionId });

    // the author edits the published override: version 2 is a draft until the review publishes it; version 1 is untouched
    await page.goto(`/cms/score-tables/${tableId}`);
    await expect(page.getByText(/Bản này đã xuất bản và không sửa được/)).toBeVisible();
    await page.getByLabel(/^Các dòng của bảng/).fill('0 0\n1 8');
    await page.getByRole('button', { name: 'Lưu thành phiên bản mới' }).click();
    await expect(page.getByText(/Đã tạo bản nháp mới, phiên bản 2/)).toBeVisible();
    const draft = (await versions(tableId))[1]!;
    await seed.reviewVersion({ type: 'score_table', versionId: draft.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
    const all = await versions(tableId);
    expect(all.map((v) => [v.versionNo, v.status])).toEqual([[1, 'ARCHIVED'], [2, 'PUBLISHED']]);
    expect(all[0]!.rows).toEqual(v1.rows);

    // the result already made is not changed; the next learner is scored with version 2
    const again = await seed.grading.scores(attemptA);
    expect(again.scores.find((x) => x.skill === 'LISTENING'), 'editing the table does not change an old result').toMatchObject({ scaled: 6.5, scoreTableVersionId: v1.versionId });
    const b = await seed.learnerWithPlan({ courses: 1 });
    const tokenB = (await org.apiLogin({ email: b.email, password: b.password })).token;
    const attemptB = await sit(tokenB, testVersionId);
    const scoresB = await poll('the scores of learner B', async () => {
      const r = await seed.grading.scores(attemptB);
      return r.graded ? r : null;
    }, { timeoutMs: 300_000, intervalMs: 2_000 });
    expect(scoresB.scores.find((x) => x.skill === 'LISTENING')).toMatchObject({ raw: 1, rawMax: 1, scaled: 8, scoreTableVersionId: all[1]!.versionId });
    expect((await seed.grading.scores(attemptA)).scores.find((x) => x.skill === 'LISTENING')).toMatchObject({ scaled: 6.5, scoreTableVersionId: v1.versionId });
  });
});
