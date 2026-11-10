// F13 — Dựng đề (spec §16.2): "Dựng đề TOEIC 200 câu và IELTS 4 kỹ năng từ câu đã xuất bản; đặt N lần quay lại; gắn bảng quy đổi ghi đè".
//
// What the test builder of P2.6 does, through the real stack: an AUTHOR builds a TOEIC test on the screen part by part from PUBLISHED question groups,
// the screen shows what the exam expects of each part (a count that differs warns; a missing skill blocks the review), the number of resumes is set per
// test, the test is published only by a review, and editing a published test makes version 2 and never touches the published one. The part of the
// scenario that needs other tasks is listed in e2e/partial-features.json: the CMS screen where an administrator attaches a per-test conversion table
// (the database, the lookup and the API listing of overrides are done and tested in IT and db-tests), and the full 200-question / four-skill IELTS
// tests (a hundred published questions are made by the Excel import of P2.7, which this scenario will use).
import { test, expect } from '../../fixtures';
import { publishedQuestion, staffed } from '../../fixtures/content';

type Version = {
  versionNo: number;
  status: string;
  versionId: string;
  maxResumes: number | null;
  parts: Array<{ sectionCode: string; skill: string; durationSec: number; questions: number; groups: Array<{ groupVersionId: string }> }>;
  sections: Array<{ sectionCode: string; expected: number; actual: number; state: string }>;
  blockers: Array<{ skill: string }>;
};

test.describe('F13 test builder', () => {
  test('F13 the author builds a TOEIC test from published groups on the screen; a missing skill blocks the review; a review publishes it; an edit makes version 2', async ({ page, request, auth, seed, org, uniqueEmail }) => {
    test.setTimeout(240_000);
    const s = await staffed(['TOEIC'], 'f13-ui', { request, uniqueEmail, apiLogin: org.apiLogin });
    const mark = s.author.userId.slice(0, 8);
    const listeningTitle = `Photos ${mark}`;
    const readingTitle = `Sentences ${mark}`;
    const group = async (typeCode: string, title: string, withAudio: boolean) => {
      const q = await publishedQuestion(s, typeCode);
      const content: Record<string, string> = { title };
      if (withAudio) content.audioMediaId = s.audioId;
      const made = await org.api(s.author.token, 'POST', '/api/cms/question-groups', { org: s.orgId, body: { examCode: 'TOEIC', content, questionVersionIds: [q.versionId] } });
      expect(made.status, made.text).toBe(201);
      await seed.reviewVersion({ type: 'question_group', versionId: made.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
      return made.body.versionId as string;
    };
    const listening = await group('TOEIC_P1_PHOTO', listeningTitle, true);
    const reading = await group('TOEIC_P5_INCOMPLETE_SENTENCE', readingTitle, false);
    const one = async (id: string) => (await org.api(s.author.token, 'GET', `/api/cms/tests/${id}`, { org: s.orgId })).body as Version;
    const versions = async (id: string) => (await org.api(s.reviewer.token, 'GET', `/api/cms/tests/${id}/versions`, { org: s.orgId })).body as Version[];

    await auth.loginAs(page, s.authorLogin);
    await page.goto('/cms/tests/new');
    await expect(page.getByRole('heading', { level: 1, name: 'Đề mới' })).toBeVisible();
    await page.getByRole('combobox', { name: 'Kỳ thi' }).click();
    await page.getByRole('option', { name: 'TOEIC', exact: true }).click();
    // TOEIC has no variants: the screen does not ask for one
    await expect(page.getByRole('combobox', { name: 'Dạng đề' })).toHaveCount(0);
    await page.getByRole('button', { name: 'Tạo đề' }).click();
    await expect(page.getByText('is required')).toBeVisible();
    await page.getByLabel('Tên đề').fill(`Mock test ${mark}`);
    await page.getByRole('button', { name: 'Tạo đề' }).click();
    await expect(page).toHaveURL(/\/cms\/tests\/[0-9a-f-]{36}$/);
    const testId = page.url().split('/').pop()!;
    await expect(page.getByRole('heading', { level: 1, name: `Mock test ${mark}` })).toBeVisible();
    // nothing in it yet: both skills are missing, and the check says every part is
    expect(((await one(testId)).blockers).map((b) => b.skill).sort()).toEqual(['LISTENING', 'READING']);

    // part 1 (Listening): the photo group
    await page.getByRole('button', { name: 'Thêm phần' }).click();
    const part1 = page.getByRole('group', { name: 'Phần 1' });
    await part1.getByLabel('Thời gian (phút)').fill('10');
    await part1.getByRole('combobox', { name: 'Thêm nhóm câu' }).click();
    await page.getByRole('option', { name: new RegExp(listeningTitle) }).click();
    await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
    await expect(page.getByText('Đã lưu bản nháp.')).toBeVisible();
    let saved = await one(testId);
    expect(saved.parts).toHaveLength(1);
    expect(saved.parts[0]).toMatchObject({ sectionCode: 'P1', skill: 'LISTENING', durationSec: 600, questions: 1 });
    expect(saved.parts[0]!.groups.map((g) => g.groupVersionId)).toEqual([listening]);
    // one of six: a warning, not an error; the Reading skill is missing: that blocks the review
    expect(saved.sections.find((x) => x.sectionCode === 'P1')).toMatchObject({ expected: 6, actual: 1, state: 'SHORT' });
    expect(saved.blockers.map((b) => b.skill)).toEqual(['READING']);
    await expect(page.getByText(/Chưa thể gửi duyệt, thiếu kỹ năng:/)).toContainText('READING');
    await page.getByRole('button', { name: 'Gửi duyệt' }).click();
    await expect(page.getByRole('alert').filter({ hasText: 'đề cần có câu hỏi ở mọi kỹ năng' })).toBeVisible();
    expect((await one(testId)).status).toBe('DRAFT');

    // part 2 (Reading, Part 5): the sentence group, and the number of resumes
    await page.getByRole('button', { name: 'Thêm phần' }).click();
    const part2 = page.getByRole('group', { name: 'Phần 2' });
    await part2.getByRole('combobox', { name: 'Phần của kỳ thi' }).click();
    await page.getByRole('option', { name: 'P5', exact: true }).click();
    await part2.getByLabel('Thời gian (phút)').fill('20');
    await part2.getByRole('combobox', { name: 'Thêm nhóm câu' }).click();
    await page.getByRole('option', { name: new RegExp(readingTitle) }).click();
    await page.getByLabel('Số lần quay lại').fill('3');
    await page.getByRole('button', { name: 'Lưu bản nháp' }).click();
    await expect(page.getByText('Đã lưu bản nháp.')).toBeVisible();
    saved = await one(testId);
    expect(saved.parts.map((p) => [p.sectionCode, p.durationSec, p.questions])).toEqual([['P1', 600, 1], ['P5', 1200, 1]]);
    expect(saved.maxResumes).toBe(3);
    expect(saved.blockers).toEqual([]);

    // submit: the version is locked while the reviewer decides
    await page.getByRole('button', { name: 'Gửi duyệt' }).click();
    await expect(page.getByText(/Đã gửi duyệt/)).toBeVisible();
    const locked = await org.api(s.author.token, 'PUT', `/api/cms/tests/${testId}/structure`, { org: s.orgId, body: { parts: [{ sectionCode: 'P1', durationSec: 60, groups: [listening] }] } });
    expect(locked.status).toBe(409);
    expect(locked.body.type).toBe('urn:e9x:problem:version-under-review');
    expect((await org.api(s.reviewer.token, 'PUT', `/api/cms/tests/${testId}/structure`, { org: s.orgId, body: { parts: [] } })).status, 'a reviewer does not write').toBe(403);

    // the real review publishes it (the review screens are P2.9's)
    await seed.reviewVersion({ type: 'test', versionId: saved.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
    await page.reload();
    await expect(page.getByText('Đã xuất bản', { exact: true })).toBeVisible();
    await expect(page.getByText(/Bản này đã xuất bản và không sửa được/)).toBeVisible();

    // editing it makes version 2; the published version is untouched
    await page.getByRole('group', { name: 'Phần 1' }).getByLabel('Thời gian (phút)').fill('15');
    await page.getByRole('button', { name: 'Lưu thành phiên bản mới' }).click();
    await expect(page.getByText(/Đã tạo bản nháp mới, phiên bản 2/)).toBeVisible();
    const all = await versions(testId);
    expect(all.map((v) => [v.versionNo, v.status])).toEqual([[1, 'PUBLISHED'], [2, 'DRAFT']]);
    expect(all[0]!.parts[0]!.durationSec).toBe(600);
    expect(all[1]!.parts[0]!.durationSec).toBe(900);
    expect(all[1]!.maxResumes).toBe(3);

    // the list shows the latest version of the test
    await page.goto('/cms/tests');
    await expect(page.getByRole('link', { name: `Mock test ${mark}` })).toBeVisible();
  });

  test('F13 a test waits for its groups: the review cannot publish it while a group is still a draft', async ({ request, seed, org, uniqueEmail }) => {
    test.setTimeout(120_000);
    const s = await staffed(['TOEIC'], 'f13-wait', { request, uniqueEmail, apiLogin: org.apiLogin });
    const mk = async (typeCode: string, title: string) => {
      const q = await publishedQuestion(s, typeCode);
      const made = await org.api(s.author.token, 'POST', '/api/cms/question-groups', { org: s.orgId, body: { examCode: 'TOEIC', content: { title }, questionVersionIds: [q.versionId] } });
      expect(made.status, made.text).toBe(201);
      return made.body.versionId as string;
    };
    const published = await mk('TOEIC_P1_PHOTO', 'published group');
    await seed.reviewVersion({ type: 'question_group', versionId: published, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
    const draft = await mk('TOEIC_P5_INCOMPLETE_SENTENCE', 'draft group');
    const made = await org.api(s.author.token, 'POST', '/api/cms/tests', { org: s.orgId, body: { examCode: 'TOEIC', title: 'Waits for a group' } });
    expect(made.status, made.text).toBe(201);
    const testId = made.body.testId as string;
    const saved = await org.api(s.author.token, 'PUT', `/api/cms/tests/${testId}/structure`, { org: s.orgId, body: { parts: [
      { sectionCode: 'P1', durationSec: 600, groups: [published] }, { sectionCode: 'P5', durationSec: 600, groups: [draft] }] } });
    expect(saved.status, saved.text).toBe(200);
    expect((await org.api(s.author.token, 'POST', `/api/cms/tests/${testId}/submit`, { org: s.orgId })).status).toBe(202);
    // the reviewer's approval is refused: the group is not PUBLISHED
    await expect(seed.reviewVersion({ type: 'test', versionId: saved.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' })).rejects.toThrow();
    expect(((await org.api(s.author.token, 'GET', `/api/cms/tests/${testId}`, { org: s.orgId })).body as Version).status).toBe('IN_REVIEW');
    // publish the group, then the same review goes through
    await seed.reviewVersion({ type: 'question_group', versionId: draft, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
    await seed.reviewVersion({ type: 'test', versionId: saved.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
    expect(((await org.api(s.author.token, 'GET', `/api/cms/tests/${testId}`, { org: s.orgId })).body as Version).status).toBe('PUBLISHED');
  });
});
