import { expect, type Browser, type Page } from '@playwright/test';
import { exampleOf } from './content';
import { api, apiLogin, switchContext } from './org';
import { loginAs } from './auth';

/**
 * The REAL review screens (P2.9) as steps of a spec. These replace the seed stand-ins (seed.reviewFlow, seed.reviewEvent): the author's work goes through the
 * real CMS endpoints (create, submit), the decision through the screen a reviewer uses. Nothing is faked: workflow, Authz, RLS, events, audit, notifications
 * all run for real.
 */

export interface Person { email: string; password: string; userId: string }

/** The author makes a question (an IELTS short answer, no files needed) and submits it for review over the real API. Returns the ids. */
export async function submitQuestion(author: Person, orgId: string | null, stem?: string): Promise<{ questionId: string; versionId: string; reviewId: string }> {
  const token = (await apiLogin(author)).token;
  const ex = exampleOf('IELTS_R_SHORT_ANSWER');
  const content = stem ? { ...ex.content, stem } : ex.content;
  const made = await api(token, 'POST', '/api/cms/questions', { org: orgId, body: { typeCode: 'IELTS_R_SHORT_ANSWER', content, answerKey: ex.answerKey, explanation: 'because', points: 1 } });
  if (made.status !== 201) throw new Error(`creating a question answered ${made.status} ${made.text}`);
  const { questionId, versionId } = made.body as { questionId: string; versionId: string };
  const sub = await api(token, 'POST', `/api/cms/questions/${questionId}/submit`, { org: orgId });
  if (sub.status !== 202) throw new Error(`submitting answered ${sub.status} ${sub.text}`);
  return { questionId, versionId, reviewId: (sub.body as { reviewId: string }).reviewId };
}

/** Opens a review on the screen (from the queue when it is listed there) as the signed-in reviewer. */
export async function openReview(page: Page, reviewId: string): Promise<void> {
  await page.goto(`/cms/reviews/${reviewId}`);
  await expect(page.getByRole('heading', { level: 2, name: 'Nội dung và khác biệt' })).toBeVisible();
}

/**
 * A new browser context in which `reviewer` signs in (in `orgName`'s context when given), opens the review and approves it or sends it back with a comment,
 * all on the real screen. Closes the context. Waits for the screen's confirmation.
 */
export async function decideOnScreen(browser: Browser, reviewer: Person, opts: { reviewId: string; orgName?: string | null; outcome: 'approved' | 'changes'; comment?: string }): Promise<void> {
  const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
  try {
    const page = await ctx.newPage();
    await loginAs(page, reviewer);
    if (opts.orgName) await switchContext(page, opts.orgName);
    await openReview(page, opts.reviewId);
    if (opts.outcome === 'approved') {
      await page.getByRole('button', { name: 'Duyệt', exact: true }).click();
      await expect(page.getByText('Đã duyệt và xuất bản.')).toBeVisible();
    } else {
      await page.getByRole('button', { name: 'Yêu cầu sửa', exact: true }).click();
      await page.getByLabel('Nhận xét').fill(opts.comment ?? 'Cần sửa lại đáp án.');
      await page.getByRole('button', { name: 'Gửi yêu cầu sửa' }).click();
      await expect(page.getByText('Đã gửi yêu cầu sửa cho người soạn.')).toBeVisible();
    }
  } finally {
    await ctx.close();
  }
}
