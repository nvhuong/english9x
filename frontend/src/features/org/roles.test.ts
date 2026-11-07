import { describe, expect, it } from 'vitest';
import { choicesToRoles, roleChoices, rolesToKeys } from './roles';

// the exams come from the catalogue (GET /api/catalog/exams); the picker passes what it fetched
const EXAMS = ['IELTS', 'TOEIC'];
const keys = (scope: Parameters<typeof roleChoices>[0], exams: readonly string[] = EXAMS) => roleChoices(scope, exams).map((c) => c.key);

describe('role choices', () => {
  it('an enterprise can only have learners and admins (spec §4: no author, reviewer or teacher)', () => {
    expect(keys('ENTERPRISE')).toEqual(['LEARNER', 'ORG_ADMIN']);
  });
  it('a center also has authors, reviewers and teachers, per exam', () => {
    expect(keys('CENTER')).toEqual([
      'LEARNER', 'ORG_ADMIN', 'AUTHOR:IELTS', 'AUTHOR:TOEIC', 'REVIEWER:IELTS', 'REVIEWER:TOEIC', 'TEACHER:IELTS', 'TEACHER:TOEIC',
    ]);
  });
  it('English9x staff are authors, reviewers, teachers per exam, or system admins; never an org role', () => {
    expect(keys('staff')).toEqual(['AUTHOR:IELTS', 'AUTHOR:TOEIC', 'REVIEWER:IELTS', 'REVIEWER:TOEIC', 'TEACHER:IELTS', 'TEACHER:TOEIC', 'SYS_ADMIN']);
  });
  it('offers per-exam roles for exactly the exams it is given, and none while the catalogue has not answered', () => {
    expect(keys('CENTER', ['TOEIC'])).toEqual(['LEARNER', 'ORG_ADMIN', 'AUTHOR:TOEIC', 'REVIEWER:TOEIC', 'TEACHER:TOEIC']);
    expect(keys('staff', [])).toEqual(['SYS_ADMIN']);
  });
  it('converts between keys and API roles', () => {
    expect(choicesToRoles(['AUTHOR:IELTS', 'ORG_ADMIN'])).toEqual([{ role: 'AUTHOR', examCode: 'IELTS' }, { role: 'ORG_ADMIN', examCode: null }]);
    expect(rolesToKeys([{ role: 'TEACHER', examCode: 'TOEIC' }, { role: 'LEARNER', examCode: null }])).toEqual(['TEACHER:TOEIC', 'LEARNER']);
  });
});
