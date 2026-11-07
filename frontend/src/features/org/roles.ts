import type { TFunction } from 'i18next';
import type { Role } from '../../app/stores/roles';

/**
 * The exams a role can be given for in v1. Assumption: P2.1 (catalogue) replaces this with the exams table; the server
 * knows the same list (RoleRules.KNOWN_EXAMS) and refuses any other code.
 */
export const EXAMS = ['IELTS', 'TOEIC'] as const;

/** Who is being given roles: English9x staff, or the members of a center / an enterprise. */
export type Scope = 'staff' | 'CENTER' | 'ENTERPRISE';

export interface RoleChoice {
  /** `ROLE` or `ROLE:EXAM`, stable and unique. */
  key: string;
  role: Role;
  examCode: string | null;
}

export interface RoleSpec {
  role: Role;
  examCode?: string | null;
}

const perExam = (role: Role): RoleChoice[] => EXAMS.map((exam) => ({ key: `${role}:${exam}`, role, examCode: exam }));
const plain = (role: Role): RoleChoice => ({ key: role, role, examCode: null });

/**
 * The roles that make sense in a scope (spec §4): an ENTERPRISE has no AUTHOR / REVIEWER / TEACHER; English9x staff have no
 * organisation roles. UI convenience: the server refuses the same combinations.
 */
export function roleChoices(scope: Scope): RoleChoice[] {
  switch (scope) {
    case 'ENTERPRISE':
      return [plain('LEARNER'), plain('ORG_ADMIN')];
    case 'CENTER':
      return [plain('LEARNER'), plain('ORG_ADMIN'), ...perExam('AUTHOR'), ...perExam('REVIEWER'), ...perExam('TEACHER')];
    case 'staff':
      return [...perExam('AUTHOR'), ...perExam('REVIEWER'), ...perExam('TEACHER'), plain('SYS_ADMIN')];
  }
}

export function choicesToRoles(keys: readonly string[]): Array<{ role: Role; examCode: string | null }> {
  return keys.map((k) => {
    const [role, exam] = k.split(':');
    return { role: role as Role, examCode: exam ?? null };
  });
}

export function rolesToKeys(roles: readonly RoleSpec[]): string[] {
  return roles.map((r) => (r.examCode ? `${r.role}:${r.examCode}` : r.role));
}

/** "Giáo viên · IELTS" / "Quản trị tổ chức". */
export function roleLabel(t: TFunction, role: Role, examCode?: string | null): string {
  const name = t(`roles.${role}`, { ns: 'org' });
  return examCode ? t('roleWithExam', { ns: 'org', role: name, exam: examCode }) : name;
}
