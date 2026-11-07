import type { seed as Seed } from '../../fixtures/seed';

/**
 * A fresh world per test: a centre with one REVIEWER and one AUTHOR of IELTS (a centre of its own, so parallel tests never notify each
 * other's reviewers), and the helper that raises the review events the workflow raises. The REVIEWER receives `requested`; the AUTHOR
 * receives `changes` and `approved`.
 */
export async function reviewWorld(seed: typeof Seed, uniqueEmail: (label?: string) => string, label: string) {
  const suffix = Date.now().toString(36);
  const org = await seed.organization({ name: `Trung tâm ${label} ${suffix}`, type: 'CENTER' });
  const reviewer = await seed.verifiedLearner({ email: uniqueEmail(`${label}-reviewer`), displayName: `Người duyệt ${label}` });
  const author = await seed.verifiedLearner({ email: uniqueEmail(`${label}-author`), displayName: `Biên soạn ${label}` });
  await seed.member({ orgId: org.orgId, userId: reviewer.userId, roles: [{ role: 'REVIEWER', examCode: 'IELTS' }] });
  await seed.member({ orgId: org.orgId, userId: author.userId, roles: [{ role: 'AUTHOR', examCode: 'IELTS' }] });
  return {
    org,
    reviewer,
    author,
    requested: () => seed.reviewEvent({ kind: 'requested', authorId: author.userId, orgId: org.orgId }),
    changes: () => seed.reviewEvent({ kind: 'changes', authorId: author.userId, orgId: org.orgId }),
    approved: () => seed.reviewEvent({ kind: 'approved', authorId: author.userId, orgId: org.orgId }),
  };
}
