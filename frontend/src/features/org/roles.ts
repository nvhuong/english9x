import type { TFunction } from 'i18next';
import type { Role } from '../../app/stores/roles';

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

const perExam = (role: Role, exams: readonly string[]): RoleChoice[] => exams.map((exam) => ({ key: `${role}:${exam}`, role, examCode: exam }));
const plain = (role: Role): RoleChoice => ({ key: role, role, examCode: null });

/**
 * The roles that make sense in a scope (spec §4): an ENTERPRISE has no AUTHOR / REVIEWER / TEACHER; English9x staff have no
 * organisation roles. UI convenience: the server refuses the same combinations. `exams` are the codes of the catalogue
 * (GET /api/catalog/exams, see useExamCodes), never a list of the screen's own.
 */
export function roleChoices(scope: Scope, exams: readonly string[]): RoleChoice[] {
  switch (scope) {
    case 'ENTERPRISE':
      return [plain('LEARNER'), plain('ORG_ADMIN')];
    case 'CENTER':
      return [plain('LEARNER'), plain('ORG_ADMIN'), ...perExam('AUTHOR', exams), ...perExam('REVIEWER', exams), ...perExam('TEACHER', exams)];
    case 'staff':
      return [...perExam('AUTHOR', exams), ...perExam('REVIEWER', exams), ...perExam('TEACHER', exams), plain('SYS_ADMIN')];
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
