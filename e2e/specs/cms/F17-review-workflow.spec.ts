// F17 — Duyệt nội dung (spec §16.2): "Tác giả gửi duyệt → người duyệt thấy trong hàng chờ → duyệt/yêu cầu sửa; người duyệt không duyệt được bản mình soạn;
// đề chưa đủ câu xuất bản thì không xuất bản được".
//
// Everything here is done on the REAL review screens (/cms/reviews, /cms/reviews/:id): the author's work goes through the real CMS endpoints, the reviewer
// reads the queue, the version against the one before it (field by field) and decides. The two-exam bundle self-review rule is measured at the service
// (ReviewServiceIT.aTwoExamBundleWithTheReviewersOwnMemberIsRefusedAsAWhole): a bundle needs an import, which is F11's.
import { test, expect } from '../../fixtures';
import { exampleOf, publishedQuestion, staffed } from '../../fixtures/content';
import { openReview, submitQuestion } from '../../fixtures/review';

const stemOf = (s: string) => `${s} ${Date.now().toString(36)}?`;

test.describe('F17 review workflow', () => {
  test('F17 the reviewer finds the submission in the queue, reads it with the author\'s name, and approves it on the screen', async ({ page, request, auth, org, uniqueEmail }) => {
    test.setTimeout(120_000);
    const s = await staffed(['IELTS'], 'f17-approve', { request, uniqueEmail, apiLogin: org.apiLogin }, { inOrganisation: true });
    const sent = await submitQuestion({ ...s.authorLogin, userId: s.author.userId }, s.orgId, stemOf('Approve me'));

    await auth.loginAs(page, s.reviewerLogin);
    await org.switchContext(page, s.orgName);
    await page.goto('/cms/reviews');
    await expect(page.getByRole('heading', { level: 1, name: 'Hàng chờ duyệt' })).toBeVisible();
    const line = page.getByTestId('review-line');
    await expect(line).toHaveCount(1);
    // the reviewer cannot read a colleague's users row: the name comes through the narrow definer
    await expect(line).toContainText('Người soạn:');
    await line.getByRole('link', { name: 'Câu hỏi' }).click();
    await expect(page.getByRole('heading', { level: 2, name: 'Nội dung và khác biệt' })).toBeVisible();
    await expect(page.getByText('Chưa có bản trước: mọi trường đều là mới.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Duyệt', exact: true })).toBeEnabled();
    await page.getByRole('button', { name: 'Duyệt', exact: true }).click();
    await expect(page.getByText('Đã duyệt và xuất bản.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Duyệt', exact: true })).toBeDisabled();

    const v = (await org.api(s.reviewer.token, 'GET', `/api/cms/questions/${sent.questionId}`, { org: s.orgId })).body as { status: string };
    expect(v.status).toBe('PUBLISHED');
    // decided once
    expect((await org.api(s.reviewer.token, 'POST', `/api/cms/reviews/${sent.reviewId}/approve`, { org: s.orgId })).status).toBe(409);
    // the queue is empty again
    await page.getByRole('link', { name: '← Hàng chờ duyệt' }).click();
    await expect(page.getByText('Không có gì chờ duyệt')).toBeVisible();
  });

  test('F17 requesting changes needs a comment, returns the version to its author, who edits it in place and submits again', async ({ page, request, auth, org, uniqueEmail }) => {
    test.setTimeout(120_000);
    const s = await staffed(['IELTS'], 'f17-changes', { request, uniqueEmail, apiLogin: org.apiLogin }, { inOrganisation: true });
    const sent = await submitQuestion({ ...s.authorLogin, userId: s.author.userId }, s.orgId, stemOf('Send me back'));
    await auth.loginAs(page, s.reviewerLogin);
    await org.switchContext(page, s.orgName);
    await openReview(page, sent.reviewId);
    await page.getByRole('button', { name: 'Yêu cầu sửa', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Gửi yêu cầu sửa' })).toBeDisabled();
    await page.getByLabel('Nhận xét').fill('Đáp án thiếu một cách viết khác.');
    await page.getByRole('button', { name: 'Gửi yêu cầu sửa' }).click();
    await expect(page.getByText('Đã gửi yêu cầu sửa cho người soạn.')).toBeVisible();

    const mine = async () => (await org.api(s.author.token, 'GET', `/api/cms/questions/${sent.questionId}`, { org: s.orgId })).body as { status: string; versionNo: number };
    expect(await mine()).toMatchObject({ status: 'CHANGES_REQUESTED', versionNo: 1 });
    // the author reads the comment on the review and fixes the same version
    const seen = await org.api(s.author.token, 'GET', `/api/cms/reviews/${sent.reviewId}`, { org: s.orgId });
    expect(seen.body.review.comment).toBe('Đáp án thiếu một cách viết khác.');
    const ex = exampleOf('IELTS_R_SHORT_ANSWER');
    const fixed = await org.api(s.author.token, 'PUT', `/api/cms/questions/${sent.questionId}`, { org: s.orgId, body: { content: { ...ex.content, stem: stemOf('Fixed') }, answerKey: ex.answerKey } });
    expect(fixed.status, fixed.text).toBe(200);
    expect((await org.api(s.author.token, 'POST', `/api/cms/questions/${sent.questionId}/submit`, { org: s.orgId })).status).toBe(202);
    expect((await mine()).status).toBe('IN_REVIEW');
  });

  test('F17 a new version is read against the published one: only the changed field is highlighted; approving it makes version 2 the published one', async ({ page, request, auth, org, uniqueEmail }) => {
    test.setTimeout(120_000);
    const s = await staffed(['IELTS'], 'f17-diff', { request, uniqueEmail, apiLogin: org.apiLogin }, { inOrganisation: true });
    const { questionId } = await publishedQuestion(s, 'IELTS_R_SHORT_ANSWER');
    const base = exampleOf('IELTS_R_SHORT_ANSWER');
    const changed = stemOf('The changed stem');
    const put = await org.api(s.author.token, 'PUT', `/api/cms/questions/${questionId}`, { org: s.orgId, body: { content: { ...base.content, stem: changed }, answerKey: base.answerKey, explanation: 'because' } });
    expect(put.status, put.text).toBe(201);
    const sub = await org.api(s.author.token, 'POST', `/api/cms/questions/${questionId}/submit`, { org: s.orgId });
    expect(sub.status).toBe(202);

    await auth.loginAs(page, s.reviewerLogin);
    await org.switchContext(page, s.orgName);
    await openReview(page, sub.body.reviewId);
    await expect(page.getByText('Phiên bản 2', { exact: true })).toBeVisible();
    await expect(page.getByText('So với phiên bản 1')).toBeVisible();
    await expect(page.getByText('1 trường thay đổi')).toBeVisible();
    const changedRows = page.locator('[data-testid="diff-row"][data-changed="true"]');
    await expect(changedRows).toHaveCount(1);
    await expect(changedRows).toContainText('content.stem');
    await expect(changedRows).toContainText(changed);
    await expect(changedRows).toContainText(String((base.content as { stem: string }).stem));
    await expect(page.locator('[data-testid="diff-row"][data-changed="false"]').first()).toBeVisible();
    await page.getByRole('checkbox', { name: 'Chỉ hiện trường thay đổi' }).check();
    await expect(page.getByTestId('diff-row')).toHaveCount(1);

    await page.getByRole('button', { name: 'Duyệt', exact: true }).click();
    await expect(page.getByText('Đã duyệt và xuất bản.')).toBeVisible();
    const all = (await org.api(s.reviewer.token, 'GET', `/api/cms/questions/${questionId}/versions`, { org: s.orgId })).body as Array<{ versionNo: number; status: string }>;
    expect(all.map((v) => [v.versionNo, v.status])).toEqual([[1, 'PUBLISHED'], [2, 'PUBLISHED']]);
  });

  test('F17 a reviewer cannot approve or send back the version they wrote: the screen does not offer it and the server refuses it', async ({ page, request, auth, org, uniqueEmail, seed }) => {
    test.setTimeout(120_000);
    const s = await staffed(['IELTS'], 'f17-own', { request, uniqueEmail, apiLogin: org.apiLogin }, { inOrganisation: true });
    const both = await seed.verifiedLearner({ email: uniqueEmail('f17-both') });
    await seed.member({ orgId: s.orgId!, userId: both.userId, roles: [{ role: 'AUTHOR', examCode: 'IELTS' }, { role: 'REVIEWER', examCode: 'IELTS' }] });
    const sent = await submitQuestion(both, s.orgId, stemOf('My own'));
    const token = (await org.apiLogin(both)).token;

    await auth.loginAs(page, both);
    await org.switchContext(page, s.orgName);
    await openReview(page, sent.reviewId);
    await expect(page.getByText(/Bạn đã soạn hoặc gửi bản này/)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Duyệt', exact: true })).toBeDisabled();
    await expect(page.getByRole('button', { name: 'Yêu cầu sửa', exact: true })).toBeDisabled();
    // the same refusal from the server, whatever the screen offers
    expect((await org.api(token, 'POST', `/api/cms/reviews/${sent.reviewId}/approve`, { org: s.orgId })).status).toBe(403);
    expect((await org.api(token, 'POST', `/api/cms/reviews/${sent.reviewId}/request-changes`, { org: s.orgId, body: { comment: 'x' } })).status).toBe(403);
    expect(((await org.api(token, 'GET', `/api/cms/questions/${sent.questionId}`, { org: s.orgId })).body as { status: string }).status).toBe('IN_REVIEW');
    // a colleague who is only a reviewer decides it
    expect((await org.api(s.reviewer.token, 'POST', `/api/cms/reviews/${sent.reviewId}/approve`, { org: s.orgId })).status).toBe(204);
  });

  test('F17 a test that is not complete is not published: a missing skill blocks the review, and a group still in draft makes the approval fail on the screen until it is published', async ({ page, request, auth, org, uniqueEmail }) => {
    test.setTimeout(180_000);
    const s = await staffed(['TOEIC'], 'f17-test', { request, uniqueEmail, apiLogin: org.apiLogin }, { inOrganisation: true });
    const mkGroup = async (typeCode: string, title: string, publish: boolean) => {
      const q = await publishedQuestion(s, typeCode);
      const made = await org.api(s.author.token, 'POST', '/api/cms/question-groups', { org: s.orgId, body: { examCode: 'TOEIC', content: { title }, questionVersionIds: [q.versionId] } });
      expect(made.status, made.text).toBe(201);
      if (publish) {
        const sub = await org.api(s.author.token, 'POST', `/api/cms/question-groups/${made.body.groupId}/submit`, { org: s.orgId });
        expect(sub.status, sub.text).toBe(202);
        expect((await org.api(s.reviewer.token, 'POST', `/api/cms/reviews/${sub.body.reviewId}/approve`, { org: s.orgId })).status).toBe(204);
      }
      return { versionId: made.body.versionId as string, groupId: made.body.groupId as string };
    };
    const listening = await mkGroup('TOEIC_P1_PHOTO', 'listening group', true);
    const reading = await mkGroup('TOEIC_P5_INCOMPLETE_SENTENCE', 'reading group (draft)', false);
    const made = await org.api(s.author.token, 'POST', '/api/cms/tests', { org: s.orgId, body: { examCode: 'TOEIC', title: 'Not complete' } });
    expect(made.status, made.text).toBe(201);
    const testId = made.body.testId as string;
    // too few: only the Listening skill has questions, so the test cannot even be sent for review
    const half = await org.api(s.author.token, 'PUT', `/api/cms/tests/${testId}/structure`, { org: s.orgId, body: { parts: [{ sectionCode: 'P1', durationSec: 600, groups: [listening.versionId] }] } });
    expect(half.status, half.text).toBe(200);
    const refused = await org.api(s.author.token, 'POST', `/api/cms/tests/${testId}/submit`, { org: s.orgId });
    expect(refused.status).toBeGreaterThanOrEqual(400);
    expect(refused.status).toBeLessThan(500);
    expect(((await org.api(s.author.token, 'GET', `/api/cms/tests/${testId}`, { org: s.orgId })).body as { status: string }).status).toBe('DRAFT');

    // every skill has a group, but the Reading group is still a draft: the test is sent, the approval is refused on the screen
    const full = await org.api(s.author.token, 'PUT', `/api/cms/tests/${testId}/structure`, { org: s.orgId, body: { parts: [
      { sectionCode: 'P1', durationSec: 600, groups: [listening.versionId] }, { sectionCode: 'P5', durationSec: 600, groups: [reading.versionId] }] } });
    expect(full.status, full.text).toBe(200);
    const sub = await org.api(s.author.token, 'POST', `/api/cms/tests/${testId}/submit`, { org: s.orgId });
    expect(sub.status, sub.text).toBe(202);

    await auth.loginAs(page, s.reviewerLogin);
    await org.switchContext(page, s.orgName);
    await openReview(page, sub.body.reviewId);
    await page.getByRole('button', { name: 'Duyệt', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('chưa được xuất bản');
    expect(((await org.api(s.author.token, 'GET', `/api/cms/tests/${testId}`, { org: s.orgId })).body as { status: string }).status).toBe('IN_REVIEW');

    // the group goes through its own review, then the same test review is approved
    const groupSub = await org.api(s.author.token, 'POST', `/api/cms/question-groups/${reading.groupId}/submit`, { org: s.orgId });
    expect(groupSub.status, groupSub.text).toBe(202);
    expect((await org.api(s.reviewer.token, 'POST', `/api/cms/reviews/${groupSub.body.reviewId}/approve`, { org: s.orgId })).status).toBe(204);
    await page.reload();
    await page.getByRole('button', { name: 'Duyệt', exact: true }).click();
    await expect(page.getByText('Đã duyệt và xuất bản.')).toBeVisible();
    expect(((await org.api(s.author.token, 'GET', `/api/cms/tests/${testId}`, { org: s.orgId })).body as { status: string }).status).toBe('PUBLISHED');
  });
});
