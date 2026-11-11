import type { Browser } from '@playwright/test';
import type { seed as Seed } from '../../fixtures/seed';
import { decideOnScreen, submitQuestion } from '../../fixtures/review';

/**
 * A fresh world per test: a centre of its own (parallel tests never notify each other's people) with
 *  - `reviewer`: REVIEWER and AUTHOR of IELTS, who receives "waiting for review" and, for what they wrote, "published";
 *  - `author`: AUTHOR of IELTS, who receives "changes requested" and "published";
 *  - `second`: another REVIEWER of IELTS, who decides on the real review screen (nobody decides what they wrote or submitted).
 * Every event is raised the way production raises it: a real question submitted over the CMS API, decided by a reviewer on the real review screen.
 * (Since P2.9 nothing here is the seed API's review-event stand-in.)
 */
export async function reviewWorld(seed: typeof Seed, uniqueEmail: (label?: string) => string, label: string, browser: Browser) {
  const suffix = Date.now().toString(36);
  const org = await seed.organization({ name: `Trung tâm ${label} ${suffix}`, type: 'CENTER' });
  const reviewer = await seed.verifiedLearner({ email: uniqueEmail(`${label}-reviewer`), displayName: `Người duyệt ${label}` });
  const author = await seed.verifiedLearner({ email: uniqueEmail(`${label}-author`), displayName: `Biên soạn ${label}` });
  const second = await seed.verifiedLearner({ email: uniqueEmail(`${label}-second`), displayName: `Người duyệt phụ ${label}` });
  await seed.member({ orgId: org.orgId, userId: reviewer.userId, roles: [{ role: 'REVIEWER', examCode: 'IELTS' }, { role: 'AUTHOR', examCode: 'IELTS' }] });
  await seed.member({ orgId: org.orgId, userId: author.userId, roles: [{ role: 'AUTHOR', examCode: 'IELTS' }] });
  await seed.member({ orgId: org.orgId, userId: second.userId, roles: [{ role: 'REVIEWER', examCode: 'IELTS' }] });
  const decide = async (writer: typeof author, outcome: 'approved' | 'changes') => {
    const sent = await submitQuestion(writer, org.orgId, `Câu hỏi ${label} ${Date.now().toString(36)}?`);
    await decideOnScreen(browser, second, { reviewId: sent.reviewId, orgName: org.name, outcome, comment: 'Cần sửa lại đáp án.' });
    return sent;
  };
  return {
    org,
    reviewer,
    author,
    second,
    /** The author submits a question: the REVIEWERs of IELTS in the centre (not the author) get "waiting for review". */
    requested: () => submitQuestion(author, org.orgId, `Câu hỏi ${label} ${Date.now().toString(36)}?`),
    /** The author submits and the second reviewer sends it back on the screen: the author gets "changes requested". */
    changes: () => decide(author, 'changes'),
    /** The author submits and the second reviewer approves on the screen: the author gets "published". */
    approved: () => decide(author, 'approved'),
    /** Same, for the first person (who is also an author): the reviewer-person gets "published". */
    reviewerPublished: () => decide(reviewer, 'approved'),
  };
}
