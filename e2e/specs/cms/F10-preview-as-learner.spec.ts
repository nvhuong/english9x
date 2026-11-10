// F10 — Xem trước như học viên (spec §16.2): "Xem trước hiển thị giống phòng thi, không lộ đáp án".
// Also the screens of F06 and F09 that the work queue and the preview bring: "Hàng việc của tôi" is scoped to the exams the person is assigned, an AUTHOR of
// IELTS does not see or open a TOEIC question, and a person who wrote a version is not offered it to review.
//
// The REAL stack. Until the exam room of P3.4 exists the preview is the CMS's minimal renderer; the part "looks like the exam room" is pending, see
// e2e/partial-features.json (F10, owner P3.4). What is asserted here is the part that must never change: the preview shows the learner's side and
// not one word of the solution side (answer key, explanation, transcript, model answer).
import { test, expect } from '../../fixtures';
import { createQuestion, exampleOf, exampleTypeCodes, examOfType, staffed, withMedia } from '../../fixtures/content';

/** Every string in a JSON value. */
function strings(node: unknown): string[] {
  if (typeof node === 'string') return [node];
  if (Array.isArray(node)) return node.flatMap(strings);
  if (node && typeof node === 'object') return Object.values(node).flatMap(strings);
  return [];
}

test.describe('F10 preview as the learner', () => {
  test('F10 the author opens the preview from the editor and sees the question the way a learner does, with no answer, explanation or transcript', async ({ page, request, auth, org, uniqueEmail }) => {
    const s = await staffed(['TOEIC'], 'f10-one', { request, uniqueEmail, apiLogin: org.apiLogin });
    const example = withMedia(exampleOf('TOEIC_P3_CONVERSATION'), s.mediaByExam.TOEIC!);
    const explanation = `EXPLANATION-${s.author.userId.slice(0, 8)}`;
    const made = await org.api(s.author.token, 'POST', '/api/cms/questions', {
      org: s.orgId,
      body: { typeCode: 'TOEIC_P3_CONVERSATION', content: example.content, answerKey: example.answerKey, explanation, points: 1 },
    });
    expect(made.status, made.text).toBe(201);
    const id = (made.body as { questionId: string }).questionId;

    await auth.loginAs(page, s.authorLogin);
    await page.goto(`/cms/questions/${id}`);
    // the solution side is on the editor ...
    await expect(page.getByLabel(/Lời thoại/)).toHaveValue(/Script of the recording/);
    await page.getByRole('link', { name: 'Xem trước như học viên' }).click();
    await expect(page).toHaveURL(new RegExp(`/cms/questions/${id}/preview$`));
    await expect(page.getByRole('heading', { level: 1, name: 'Xem trước như học viên' })).toBeVisible();

    // ... and the learner's side is on the preview
    const view = page.getByRole('region', { name: 'Câu hỏi như học viên thấy' });
    await expect(view.getByText((example.content as { stem: string }).stem)).toBeVisible();
    await expect(view.getByRole('radio')).toHaveCount(4);
    await expect(view.locator('audio')).toBeVisible();
    await view.getByRole('radio').first().check(); // answering is possible; nothing is saved or marked

    const text = await page.locator('main').innerText();
    for (const secret of [...strings((example.answerKey as { transcript?: string }).transcript), explanation, 'Script of the recording']) expect(text, secret).not.toContain(secret);
    await expect(page.getByLabel(/Lời thoại|Giải thích/)).toHaveCount(0);

    // back to the editor
    await page.getByRole('link', { name: 'Về câu hỏi' }).click();
    await expect(page).toHaveURL(new RegExp(`/cms/questions/${id}$`));
  });

  test('F10 the preview of every question type of the catalog shows something and never a word of its solution side', async ({ page, request, auth, org, uniqueEmail }) => {
    test.setTimeout(300_000);
    const s = await staffed(['IELTS', 'TOEIC'], 'f10-all', { request, uniqueEmail, apiLogin: org.apiLogin });
    await auth.loginAs(page, s.authorLogin);
    for (const code of exampleTypeCodes()) {
      const example = withMedia(exampleOf(code), s.mediaByExam[examOfType(code)]!);
      const made = await org.api(s.author.token, 'POST', '/api/cms/questions', {
        org: s.orgId,
        body: { typeCode: code, content: example.content, answerKey: example.answerKey, explanation: `EXPLANATION ${code}`, points: 1 },
      });
      expect(made.status, `${code}: ${made.text}`).toBe(201);
      await page.goto(`/cms/questions/${(made.body as { questionId: string }).questionId}/preview`);
      const view = page.getByRole('region', { name: 'Câu hỏi như học viên thấy' });
      await expect(view, code).toBeVisible();
      const shown = await view.innerText();
      expect(shown.trim().length, `${code} shows something`).toBeGreaterThan(5);
      const page_ = await page.locator('main').innerText();
      // what is only on the solution side: the strings of the answer key that the content does not also have, and the explanation
      const contentWords = new Set(strings(example.content));
      const LEARNER_WORDS = new Set(['TRUE', 'FALSE', 'YES', 'NO', 'NOT GIVEN']); // the fixed choices of the learner's side
      const secrets = strings(example.answerKey).filter((x) => x.length >= 8 && !contentWords.has(x) && !LEARNER_WORDS.has(x));
      for (const secret of [...secrets, `EXPLANATION ${code}`]) expect(page_, `${code}: ${secret}`).not.toContain(secret);
    }
  });

  test('F06 + F09: the work queue lists my drafts, what waits for my review, and only in the exams I am assigned; an author of another exam does not see the question', async ({ page, browser, request, auth, org, seed, uniqueEmail }) => {
    test.setTimeout(180_000);
    // every person signs in in a browser context of their own (the session lives in the page's storage)
    const as = async (person: { email: string; password: string }) => {
      const p = await (await browser.newContext()).newPage();
      await auth.loginAs(p, person);
      return p;
    };
    const s = await staffed(['TOEIC'], 'f06-queue', { request, uniqueEmail, apiLogin: org.apiLogin });
    const titleOf = async (token: string, id: string, exam = 'TOEIC') => {
      const list = await org.api(token, 'GET', `/api/cms/questions?exam=${exam}&size=100`, { org: s.orgId });
      return (list.body.items as Array<{ questionId: string; title: string }>).find((x) => x.questionId === id)!.title;
    };
    const mk = async (code: string, n: number) => {
      const e = withMedia(exampleOf(code), s.mediaByExam.TOEIC!);
      const content = { ...e.content, stem: `F06 queue ${n} ${s.author.userId.slice(0, 8)}` };
      const r = await org.api(s.author.token, 'POST', '/api/cms/questions', { org: s.orgId, body: { typeCode: code, content, answerKey: e.answerKey, explanation: 'x', points: 1 } });
      expect(r.status, r.text).toBe(201);
      return (r.body as { questionId: string }).questionId;
    };
    const draftId = await mk('TOEIC_P5_INCOMPLETE_SENTENCE', 1);
    const sentId = await mk('TOEIC_P5_INCOMPLETE_SENTENCE', 2);
    const submitted = await org.api(s.author.token, 'POST', `/api/cms/questions/${sentId}/submit`, { org: s.orgId });
    expect(submitted.status, submitted.text).toBe(202);
    const draftTitle = await titleOf(s.author.token, draftId);
    const sentTitle = await titleOf(s.author.token, sentId);

    // the author: the draft is theirs to finish; the submitted one is locked and is nobody's to review but a reviewer's
    await auth.loginAs(page, s.authorLogin);
    await page.goto('/cms/work-queue');
    await expect(page.getByRole('heading', { level: 1, name: 'Hàng việc của tôi' })).toBeVisible();
    await expect(page.getByRole('region', { name: /Bản nháp của tôi/ }).getByRole('link', { name: draftTitle })).toBeVisible();
    await expect(page.getByRole('region', { name: /Chờ tôi duyệt/ }).getByRole('link')).toHaveCount(0);
    await expect(page.getByRole('link', { name: sentTitle })).toHaveCount(0);
    await page.getByRole('region', { name: /Bản nháp của tôi/ }).getByRole('link', { name: draftTitle }).click();
    await expect(page).toHaveURL(new RegExp(`/cms/questions/${draftId}$`));

    // a reviewer of TOEIC: the submitted question waits for them, the draft does not
    const reviewer = await seed.verifiedLearner({ email: uniqueEmail('f06-reviewer') });
    await seed.staffGrant({ userId: reviewer.userId, role: 'REVIEWER', examCode: 'TOEIC' });
    const reviewerPage = await as(reviewer);
    await reviewerPage.goto('/cms/work-queue');
    await expect(reviewerPage.getByRole('region', { name: /Chờ tôi duyệt/ }).getByRole('link', { name: sentTitle })).toBeVisible();
    await expect(reviewerPage.getByRole('link', { name: draftTitle })).toHaveCount(0);

    // a person who is both author and reviewer is not offered their own version to review
    const both = await seed.verifiedLearner({ email: uniqueEmail('f06-both') });
    await seed.staffGrant({ userId: both.userId, role: 'AUTHOR', examCode: 'TOEIC' });
    await seed.staffGrant({ userId: both.userId, role: 'REVIEWER', examCode: 'TOEIC' });
    const bothToken = (await org.apiLogin(both)).token;
    const e = withMedia(exampleOf('TOEIC_P5_INCOMPLETE_SENTENCE'), s.mediaByExam.TOEIC!);
    const own = await org.api(bothToken, 'POST', '/api/cms/questions', { org: s.orgId, body: { typeCode: 'TOEIC_P5_INCOMPLETE_SENTENCE', content: { ...e.content, stem: `F06 own ${both.userId.slice(0, 8)}` }, answerKey: e.answerKey, points: 1 } });
    expect(own.status, own.text).toBe(201);
    expect((await org.api(bothToken, 'POST', `/api/cms/questions/${own.body.questionId}/submit`, { org: s.orgId })).status).toBe(202);
    const ownTitle = await titleOf(bothToken, own.body.questionId);
    const bothPage = await as(both);
    await bothPage.goto('/cms/work-queue');
    await expect(bothPage.getByRole('region', { name: /Chờ tôi duyệt/ }).getByRole('link', { name: sentTitle })).toBeVisible(); // somebody else's: yes
    await expect(bothPage.getByRole('link', { name: ownTitle })).toHaveCount(0); // their own: no

    // an author of IELTS only: nothing of TOEIC in the queue, and the question cannot be opened or previewed
    const ielts = await seed.verifiedLearner({ email: uniqueEmail('f06-ielts') });
    await seed.staffGrant({ userId: ielts.userId, role: 'AUTHOR', examCode: 'IELTS' });
    const ieltsPage = await as(ielts);
    await ieltsPage.goto('/cms/work-queue');
    await expect(ieltsPage.getByRole('heading', { level: 1, name: 'Hàng việc của tôi' })).toBeVisible();
    await expect(ieltsPage.getByRole('link', { name: draftTitle })).toHaveCount(0);
    await expect(ieltsPage.getByRole('link', { name: sentTitle })).toHaveCount(0);
    await ieltsPage.goto(`/cms/questions/${draftId}`);
    await expect(ieltsPage.getByText('Không tìm thấy câu hỏi')).toBeVisible();
    await ieltsPage.goto(`/cms/questions/${draftId}/preview`);
    await expect(ieltsPage.getByText('Không tìm thấy câu hỏi')).toBeVisible();
  });
});
