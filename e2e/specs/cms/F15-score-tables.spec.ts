// F15 — Bảng quy đổi (spec §16.2): "Tạo bảng mặc định + ghi đè → duyệt → kết quả thi dùng đúng bảng; sửa bảng không đổi kết quả cũ".
//
// What the score tables of P2.5 do, through the real stack: the author makes the DEFAULT table of an exam skill on the screen, the server refuses a table
// the domain's TableConverter would refuse (a gap in the raw values), the table is published only by a review, and editing a published table makes a new
// draft version and never touches the published one; the new version replaces it when published. Two parts of the scenario need other tasks and are listed
// in e2e/partial-features.json: the per-TEST override (tests exist from P2.6; until then the server answers 409 for scope TEST and the database refuses
// the row) and "kết quả thi dùng đúng bảng / sửa bảng không đổi kết quả cũ" (results exist from P3.4 / P4.1).
import { test, expect } from '../../fixtures';
import { staffed } from '../../fixtures/content';

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

    // the list shows the latest version of each table
    await page.goto('/cms/score-tables');
    await expect(page.getByRole('link', { name: 'LISTENING' })).toBeVisible();
  });

  test('F15 the scope TEST is not available yet; the other refusals are by path; a score a function computes has no table', async ({ request, org, uniqueEmail }) => {
    const s = await staffed(['IELTS', 'TOEIC'], 'f15-api', { request, uniqueEmail, apiLogin: org.apiLogin });
    const post = (token: string, body: unknown) => org.api(token, 'POST', '/api/cms/score-tables', { org: s.orgId, body });
    const rows = [{ raw: 0, scaled: 0 }, { raw: 1, scaled: 100 }];

    const test_ = await post(s.author.token, { examCode: 'TOEIC', skill: 'READING', scope: 'TEST', testId: '00000000-0000-0000-0000-000000000001', rows });
    expect(test_.status, test_.text).toBe(409);
    expect(test_.body.type).toBe('urn:e9x:problem:test-scope-unavailable');

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
});
