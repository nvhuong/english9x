// F18 — Phiên bản (spec §16.2): "Sửa câu đã xuất bản → học viên đang thi vẫn thấy bản cũ → bản mới chỉ hiện sau khi duyệt".
//
// What the question versions of P2.3 do, through the real stack: editing a published question makes a NEW draft version and never touches the
// published one; the new version is published only by a review; one open version per question; a colleague cannot take a draft over. The last
// last test sits a learner IN AN EXAM through the real room: a new version of a question of the test is published by a real review in the middle of
// the attempt, and the learner still reads, answers and is scored on the version the attempt began on.
import { test, expect } from '../../fixtures';
import { exampleOf, publishedQuestion, staffed } from '../../fixtures/content';
import { poll } from '../../helpers/env';
import { enterExam, finishSection } from '../exam/_exam';

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
  test('F18 a learner in an exam keeps the question version the attempt began on when a new version is published in the middle, and is scored by it', async ({ page, seed, auth, org }) => {
    test.setTimeout(540_000);
    const exam = await seed.publishedShortToeicTest({ listening: 900, reading: 900 });
    await seed.grading.scoreTable({ testVersionId: exam.testVersionId, skill: 'LISTENING', scaled: [0, 5] });
    await seed.grading.scoreTable({ testVersionId: exam.testVersionId, skill: 'READING', scaled: [0, 5, 10] });
    const staff = exam.staff;
    const learner = await seed.learnerWithPlan({ courses: 1, examCode: 'TOEIC' });
    const [listening, reading] = exam.parts;
    const target = reading!.questions[0]!; // the first question of P5, which has a stem to tell the versions apart
    const learnerToken = (await org.apiLogin({ email: learner.email, password: learner.password })).token;
    type RoomQuestion = { questionId: string; versionId: string; content: { stem: string } };
    const roomQuestion = async (token: string, attemptId: string): Promise<RoomQuestion> => {
      const view = await org.api(token, 'GET', `/api/exam/attempts/${attemptId}`);
      expect(view.status, view.text).toBe(200);
      return (view.body.currentSection.groups as Array<{ questions: RoomQuestion[] }>).flatMap((g) => g.questions).find((q) => q.questionId === target.questionId)!;
    };

    // the learner sits P2, then P5 opens
    await auth.loginAs(page, learner);
    const attemptId = await enterExam(page, exam.testVersionId);
    await page.keyboard.press(String(listening!.questions[0]!.answer));
    await finishSection(page);
    await expect(page.getByText('Phần 2/2')).toBeVisible();
    const before = await roomQuestion(learnerToken, attemptId);
    expect(before.content.stem).toMatch(/^Question \d+$/);
    await expect(page.getByRole('region', { name: 'Câu hỏi' })).toContainText(before.content.stem);

    // in the middle of the attempt the author edits the question (a new stem and ANOTHER right answer) and a reviewer publishes version 2
    const author = (await org.apiLogin(staff.authorLogin)).token;
    const current = await org.api(author, 'GET', `/api/cms/questions/${target.questionId}`, { org: staff.orgId });
    expect(current.status, current.text).toBe(200);
    const oldKey = String(target.answer);
    const newKey = ['A', 'B', 'C', 'D'].find((k) => k !== oldKey)!;
    const newStem = `NEW VERSION ${staff.author.userId.slice(0, 8)}`;
    const edited = await org.api(author, 'PUT', `/api/cms/questions/${target.questionId}`, {
      org: staff.orgId,
      body: { content: { ...current.body.content, stem: newStem }, answerKey: { ...current.body.answerKey, slots: [[newKey]] } },
    });
    expect(edited.status, edited.text).toBe(201);
    expect(edited.body.versionNo).toBe(2);
    await seed.reviewVersion({ type: 'question', versionId: edited.body.versionId, authorId: staff.author.userId, reviewerId: staff.reviewer.userId, orgId: staff.orgId, outcome: 'approved' });
    const versions = (await org.api(staff.reviewer.token, 'GET', `/api/cms/questions/${target.questionId}/versions`, { org: staff.orgId })).body as Array<{ versionNo: number; status: string; versionId: string }>;
    expect(versions.map((v) => [v.versionNo, v.status]), 'the new version is published').toEqual([[1, 'PUBLISHED'], [2, 'PUBLISHED']]);
    expect(versions[0]!.versionId).toBe(before.versionId);

    // the room the learner is in (reloaded, the way a person who comes back to the tab sees it) still has version 1: stem, version id, and the same on the server
    await page.reload();
    await expect(page.getByText('Phần 2/2')).toBeVisible();
    await expect(page.getByRole('region', { name: 'Câu hỏi' })).toContainText(before.content.stem);
    await expect(page.getByText(newStem)).toHaveCount(0);
    const after = await roomQuestion(learnerToken, attemptId);
    expect(after.versionId, 'the attempt still points at the version it began on').toBe(before.versionId);
    expect(after.versionId).not.toBe(edited.body.versionId);
    expect(after.content.stem).toBe(before.content.stem);

    // the learner answers with the key of version 1 and submits: the result is scored by version 1 (version 2's key is another letter)
    await page.keyboard.press(oldKey);
    await finishSection(page);
    await expect(page.getByTestId('exam-result')).toHaveAttribute('data-status', 'SUBMITTED');
    const scores = await poll('the scores', async () => {
      const r = await seed.grading.scores(attemptId);
      return r.graded ? r : null;
    }, { timeoutMs: 360_000, intervalMs: 3_000 });
    expect(scores.scores.find((x) => x.skill === 'READING'), 'the answer was right by the key of the version the attempt began on').toMatchObject({ raw: 1, rawMax: 2, scaled: 5 });
  });
});
