import type { Role, RoleGrant } from '../../../app/stores/roles';
import type { ContentStatus, QuestionSummary } from '../questions/types';

export type Section = 'drafts' | 'changes' | 'review';
export interface Request {
  section: Section;
  exam: string;
  status: ContentStatus;
}
export interface Queue {
  drafts: QuestionSummary[];
  changes: QuestionSummary[];
  review: QuestionSummary[];
}

const STATUS_OF: Record<Section, ContentStatus> = { drafts: 'DRAFT', changes: 'CHANGES_REQUESTED', review: 'IN_REVIEW' };

/** The exams a role is granted for in this context (a grant of English9x as a whole, or of this organisation), in the order of the grants, without repeats. */
export function examsOfRole(grants: RoleGrant[] | null, ctxOrgId: string | null, role: Role): string[] {
  const out: string[] = [];
  for (const g of grants ?? []) {
    if (g.role !== role || (g.org !== null && g.org !== ctxOrgId) || g.exam === null) continue;
    if (!out.includes(g.exam)) out.push(g.exam);
  }
  return out;
}

/** What to ask the server for: my drafts and changes-requested items in the exams I write, what is in review in the exams I review. */
export function plan(grants: RoleGrant[] | null, ctxOrgId: string | null): Request[] {
  const out: Request[] = [];
  for (const exam of examsOfRole(grants, ctxOrgId, 'AUTHOR')) {
    out.push({ section: 'drafts', exam, status: STATUS_OF.drafts }, { section: 'changes', exam, status: STATUS_OF.changes });
  }
  for (const exam of examsOfRole(grants, ctxOrgId, 'REVIEWER')) out.push({ section: 'review', exam, status: STATUS_OF.review });
  return out;
}

/**
 * Sorts what the server listed into the three queues of the person. The server already limits the list to what they may read; this keeps what is theirs to
 * act on: the drafts and the changes asked of them are the ones they wrote, and a version waits for their review only when somebody else wrote it
 * (the server refuses the author's own version again).
 */
export function buildQueue(items: QuestionSummary[], userId: string | undefined, grants: RoleGrant[] | null, ctxOrgId: string | null): Queue {
  const authored = new Set(examsOfRole(grants, ctxOrgId, 'AUTHOR'));
  const reviewed = new Set(examsOfRole(grants, ctxOrgId, 'REVIEWER'));
  const seen = new Set<string>();
  const out: Queue = { drafts: [], changes: [], review: [] };
  for (const q of items) {
    if (seen.has(q.versionId)) continue;
    seen.add(q.versionId);
    const mine = userId !== undefined && q.createdBy === userId;
    if (q.status === 'DRAFT' && mine && authored.has(q.examCode)) out.drafts.push(q);
    else if (q.status === 'CHANGES_REQUESTED' && mine && authored.has(q.examCode)) out.changes.push(q);
    else if (q.status === 'IN_REVIEW' && !mine && reviewed.has(q.examCode)) out.review.push(q);
  }
  return out;
}
