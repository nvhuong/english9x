import type { TFunction } from 'i18next';
import { ApiProblemError } from '../../api/problem';

/** A human message for a failed classroom call, from the problem `type` (never from server text). */
export function classroomProblemMessage(err: unknown, t: TFunction): string {
  if (err instanceof ApiProblemError) {
    switch (err.type) {
      case 'urn:e9x:problem:teacher-required':
        return t('problem.teacherRequired');
      case 'urn:e9x:problem:not-a-teacher':
        return t('problem.notATeacher');
      case 'urn:e9x:problem:group-has-no-teacher':
        return t('problem.groupNoTeacher');
      case 'urn:e9x:problem:not-an-org-member':
        return t('problem.notOrgMember');
      case 'urn:e9x:problem:no-seat-available':
        return t('problem.noSeat');
      case 'urn:e9x:problem:license-not-active':
        return t('problem.licenseNotActive');
      case 'urn:e9x:problem:course-not-in-license':
        return t('problem.courseNotInLicense');
      case 'urn:e9x:problem:due-in-past':
        return t('problem.dueInPast');
      case 'urn:e9x:problem:assignment-target-unavailable':
        return t('problem.targetUnavailable');
      case 'urn:e9x:problem:invalid-assignment':
        return t('problem.invalidAssignment');
      default:
        return err.status === 403 ? t('problem.forbidden') : t('problem.generic');
    }
  }
  return t('problem.network');
}
