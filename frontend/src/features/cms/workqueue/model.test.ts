import { describe, expect, it } from 'vitest';
import { buildQueue, examsOfRole, plan } from './model';
import type { RoleGrant } from '../../../app/stores/roles';
import type { QuestionSummary } from '../questions/types';

const ME = 'me';
const q = (over: Partial<QuestionSummary>): QuestionSummary => ({
  questionId: 'x', versionId: `v-${over.questionId}`, versionNo: 1, status: 'DRAFT', examCode: 'TOEIC', typeCode: 'T', ...over,
});

describe('work queue model', () => {
  const grants: RoleGrant[] = [
    { role: 'AUTHOR', org: null, exam: 'TOEIC' },
    { role: 'REVIEWER', org: null, exam: 'IELTS' },
    { role: 'AUTHOR', org: 'other-org', exam: 'IELTS' },
  ];

  it('takes the exams of a role from the grants of this context only', () => {
    expect(examsOfRole(grants, null, 'AUTHOR')).toEqual(['TOEIC']);
    expect(examsOfRole(grants, 'other-org', 'AUTHOR')).toEqual(['TOEIC', 'IELTS']);
    expect(examsOfRole(grants, null, 'REVIEWER')).toEqual(['IELTS']);
  });

  it('asks for drafts and changes-requested of the author exams, and in-review of the reviewer exams', () => {
    expect(plan(grants, null)).toEqual([
      { section: 'drafts', exam: 'TOEIC', status: 'DRAFT' },
      { section: 'changes', exam: 'TOEIC', status: 'CHANGES_REQUESTED' },
      { section: 'review', exam: 'IELTS', status: 'IN_REVIEW' },
    ]);
  });

  it('keeps my own drafts and changes, and for review only what others wrote (a reviewer never reviews their own version)', () => {
    const items = [
      q({ questionId: 'a', status: 'DRAFT', createdBy: ME }),
      q({ questionId: 'b', status: 'DRAFT', createdBy: 'someone' }),
      q({ questionId: 'c', status: 'CHANGES_REQUESTED', createdBy: ME }),
      q({ questionId: 'd', status: 'IN_REVIEW', createdBy: 'someone', examCode: 'IELTS' }),
      q({ questionId: 'e', status: 'IN_REVIEW', createdBy: ME, examCode: 'IELTS' }),
      q({ questionId: 'f', status: 'IN_REVIEW', createdBy: 'someone', examCode: 'TOEIC' }), // not my exam to review
    ];
    const out = buildQueue(items, ME, grants, null);
    expect(out.drafts.map((x) => x.questionId)).toEqual(['a']);
    expect(out.changes.map((x) => x.questionId)).toEqual(['c']);
    expect(out.review.map((x) => x.questionId)).toEqual(['d']);
  });
});
