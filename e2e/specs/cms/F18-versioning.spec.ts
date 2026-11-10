// F18 — Phiên bản (spec §16.2): "Sửa câu đã xuất bản → học viên đang thi vẫn thấy bản cũ → bản mới chỉ hiện sau khi duyệt".
//
// What the question versions of P2.3 do, through the real stack: editing a published question makes a NEW draft version and never touches the
// published one; the new version is published only by a review; one open version per question; a colleague cannot take a draft over. The two
// parts that need other tasks are listed in e2e/partial-features.json: a learner IN AN EXAM keeping the old version (the exam room, P3.3/P3.4)
// and the side-by-side comparison screen (P2.9).
import { test, expect } from '../../fixtures';
import { exampleOf, publishedQuestion, staffed } from '../../fixtures/content';

type Version = { versionNo: number; status: string; versionId: string; content: { stem: string } };

test.describe('F18 versioning', () => {
  test('F18 editing a published question makes a new draft version; the published one is untouched while the new one waits for review', async ({ page, request, auth, org, uniqueEmail }) => {
    const s = await staffed(['IELTS'], 'f18', { request, uniqueEmail, apiLogin: org.apiLogin });
    const { questionId, versionId: v1 } = await publishedQuestion(s, 'IELTS_R_SHORT_ANSWER');
    const original = exampleOf('IELTS_R_SHORT_ANSWER').content as { stem: string };
    const versions = async () => (await org.api(s.reviewer.token, 'GET', `/api/cms/questions/${questionId}/versions`, { org: s.orgId })).body as Version[];

    await auth.loginAs(page, s.authorLogin);
    await page.goto(`/cms/questions/${questionId}`);
    await expect(page.getByText('Đã xuất bản', { exact: true })).toBeVisible();
    await expect(page.getByText(/Bản này đã xuất bản và không sửa được/)).toBeVisible();
    const mine = `What is the main effect of the policy? (${s.author.userId.slice(0, 8)})`;
    await page.locator('#root_content_stem').fill(mine);
    await page.getByRole('button', { name: 'Lưu thành bản nháp mới' }).click();
    await expect(page.getByText(/Đã tạo bản nháp mới, phiên bản 2/)).toBeVisible();
    await expect(page.getByText('Phiên bản 2', { exact: true })).toBeVisible();
    await expect(page.getByText('Bản nháp', { exact: true })).toBeVisible();

    // the published version has not changed by a character; the draft holds the edit
    let all = await versions();
    expect(all.map((v) => [v.versionNo, v.status])).toEqual([[1, 'PUBLISHED'], [2, 'DRAFT']]);
    expect(all[0]!.versionId).toBe(v1);
    expect(all[0]!.content.stem).toBe(original.stem);
    expect(all[1]!.content.stem).toBe(mine);

    // saving again changes the open draft in place; it does not pile up versions
    const newer = `${mine} again`;
    await page.locator('#root_content_stem').fill(newer);
    await page.getByRole('button', { name: 'Lưu thay đổi' }).click();
    await expect(page.getByText('Đã lưu bản nháp.')).toBeVisible();
    all = await versions();
    expect(all).toHaveLength(2);
    expect(all[1]!.content.stem).toBe(newer);

    // the list shows the latest version of the question
    await page.goto('/cms/questions');
    await expect(page.getByRole('link', { name: newer })).toBeVisible();

    // submit it: the version is locked while the reviewer decides (the approval itself is the next test: the review screens are P2.9's)
    await page.goto(`/cms/questions/${questionId}`);
    await page.getByRole('button', { name: 'Gửi duyệt' }).click();
    await expect(page.getByText(/Đã gửi duyệt/)).toBeVisible();
    const locked = await org.api(s.author.token, 'PUT', `/api/cms/questions/${questionId}`, { org: s.orgId, body: { content: all[1]!.content, answerKey: exampleOf('IELTS_R_SHORT_ANSWER').answerKey } });
    expect(locked.status).toBe(409);
    expect(locked.body.type).toBe('urn:e9x:problem:version-under-review');
  });

  test('F18 the new version becomes the published one only after a review, and a third edit starts version 3', async ({ request, seed, org, uniqueEmail }) => {
    const s = await staffed(['IELTS'], 'f18-review', { request, uniqueEmail, apiLogin: org.apiLogin });
    const { questionId, versionId: v1 } = await publishedQuestion(s, 'IELTS_R_SHORT_ANSWER');
    const base = exampleOf('IELTS_R_SHORT_ANSWER');
    const put = (stem: string) => org.api(s.author.token, 'PUT', `/api/cms/questions/${questionId}`, { org: s.orgId, body: { content: { ...base.content, stem }, answerKey: base.answerKey } });
    const versions = async () => (await org.api(s.reviewer.token, 'GET', `/api/cms/questions/${questionId}/versions`, { org: s.orgId })).body as Version[];

    const second = await put('Second?');
    expect(second.status, second.text).toBe(201);
    expect(second.body.versionNo).toBe(2);
    await seed.reviewVersion({ type: 'question', versionId: second.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'approved' });
    let all = await versions();
    expect(all.map((v) => [v.versionNo, v.status])).toEqual([[1, 'PUBLISHED'], [2, 'PUBLISHED']]);
    expect(all[0]!.versionId, 'version 1 keeps its id').toBe(v1);
    expect((all[0]!.content as { stem: string }).stem).toBe((base.content as { stem: string }).stem);

    const third = await put('Third?');
    expect(third.status, third.text).toBe(201);
    expect(third.body.versionNo).toBe(3);
    all = await versions();
    expect(all.map((v) => v.status)).toEqual(['PUBLISHED', 'PUBLISHED', 'DRAFT']);
    // a reviewer who asks for changes hands the same version back to its author (the version number does not move)
    await seed.reviewVersion({ type: 'question', versionId: third.body.versionId, authorId: s.author.userId, reviewerId: s.reviewer.userId, orgId: s.orgId, outcome: 'changes' });
    expect((await versions()).map((v) => v.status)).toEqual(['PUBLISHED', 'PUBLISHED', 'CHANGES_REQUESTED']);
    const fixed = await put('Third, fixed?');
    expect(fixed.status).toBe(200);
    expect(fixed.body.versionNo).toBe(3);
  });

  test('F18 a colleague cannot take over an open draft, and nobody but the reviewer of the exam approves it', async ({ request, seed, org, uniqueEmail }) => {
    const s = await staffed(['IELTS'], 'f18-colleague', { request, uniqueEmail, apiLogin: org.apiLogin }, { inOrganisation: true });
    const colleague = await seed.verifiedLearner({ email: uniqueEmail('f18-colleague2') });
    await seed.member({ orgId: s.orgId!, userId: colleague.userId, roles: [{ role: 'AUTHOR', examCode: 'IELTS' }] });
    const c = (await org.apiLogin(colleague)).token;
    const { questionId } = await publishedQuestion(s, 'IELTS_R_SHORT_ANSWER');
    const base = exampleOf('IELTS_R_SHORT_ANSWER');
    const body = { content: { ...base.content, stem: 'Mine?' }, answerKey: base.answerKey };
    const mine = await org.api(s.author.token, 'PUT', `/api/cms/questions/${questionId}`, { org: s.orgId, body });
    expect(mine.status).toBe(201);
    // the colleague may read it, not change it or send it for review
    expect((await org.api(c, 'GET', `/api/cms/questions/${questionId}`, { org: s.orgId })).status).toBe(200);
    const edit = await org.api(c, 'PUT', `/api/cms/questions/${questionId}`, { org: s.orgId, body: { ...body, content: { ...base.content, stem: 'Hijacked?' } } });
    expect(edit.status).toBe(409);
    expect(edit.body.type).toBe('urn:e9x:problem:draft-of-another-author');
    expect((await org.api(c, 'POST', `/api/cms/questions/${questionId}/submit`, { org: s.orgId })).status).toBe(409);
    // the reviewer reads but does not write
    expect((await org.api(s.reviewer.token, 'PUT', `/api/cms/questions/${questionId}`, { org: s.orgId, body })).status).toBe(403);
    const after = await org.api(s.author.token, 'GET', `/api/cms/questions/${questionId}`, { org: s.orgId });
    expect(after.body.content.stem).toBe('Mine?');
  });
});
