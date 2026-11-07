import type { TFunction } from 'i18next';
import { ApiProblemError } from '../../api/problem';

/** A human message for a failed organisation call, from the problem `type` (never from server text). */
export function orgProblemMessage(err: unknown, t: TFunction): string {
  if (err instanceof ApiProblemError) {
    switch (err.type) {
      case 'urn:e9x:problem:role-not-allowed':
        return t('problem.roleNotAllowed');
      case 'urn:e9x:problem:too-many-invitations':
        return t('problem.tooManyInvitations');
      case 'urn:e9x:problem:last-org-admin':
        return t('problem.lastOrgAdmin');
      default:
        return t('problem.generic');
    }
  }
  return t('problem.network');
}
