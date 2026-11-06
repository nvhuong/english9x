import { describe, expect, it } from 'vitest';
import { hasRole, type RoleGrant } from './roles';

const g = (role: RoleGrant['role'], org: string | null = null, exam: string | null = null): RoleGrant => ({ role, org, exam });

describe('hasRole (UI convenience, not security)', () => {
  it('denies when grants are unknown or empty', () => {
    expect(hasRole(null, null, 'AUTHOR')).toBe(false);
    expect(hasRole([], null, 'AUTHOR')).toBe(false);
  });
  it('matches by role; accepts any of several roles', () => {
    expect(hasRole([g('AUTHOR')], null, 'AUTHOR')).toBe(true);
    expect(hasRole([g('AUTHOR')], null, ['REVIEWER', 'AUTHOR'])).toBe(true);
    expect(hasRole([g('LEARNER')], null, ['REVIEWER', 'AUTHOR'])).toBe(false);
  });
  it('treats an unknown role string in the grants as no grant', () => {
    expect(hasRole([{ role: 'ROOT' as never, org: null, exam: null }], null, 'SYS_ADMIN')).toBe(false);
  });
  it('org-scoped grants only apply in that organisation; English9x-wide grants apply everywhere', () => {
    expect(hasRole([g('ORG_ADMIN', 'o1')], 'o1', 'ORG_ADMIN')).toBe(true);
    expect(hasRole([g('ORG_ADMIN', 'o1')], 'o2', 'ORG_ADMIN')).toBe(false);
    expect(hasRole([g('ORG_ADMIN', 'o1')], null, 'ORG_ADMIN')).toBe(false);
    expect(hasRole([g('SYS_ADMIN')], 'o2', 'SYS_ADMIN')).toBe(true);
  });
  it('exam-scoped roles match only that exam; exam = null grants all exams only for ORG_ADMIN / SYS_ADMIN', () => {
    expect(hasRole([g('AUTHOR', null, 'IELTS_AC')], null, 'AUTHOR', 'IELTS_AC')).toBe(true);
    expect(hasRole([g('AUTHOR', null, 'IELTS_AC')], null, 'AUTHOR', 'TOEIC_LR')).toBe(false);
    expect(hasRole([g('AUTHOR')], null, 'AUTHOR', 'IELTS_AC')).toBe(false);
    expect(hasRole([g('SYS_ADMIN')], null, 'SYS_ADMIN', 'IELTS_AC')).toBe(true);
    expect(hasRole([g('AUTHOR', null, 'IELTS_AC')], null, 'AUTHOR')).toBe(true);
  });
});
