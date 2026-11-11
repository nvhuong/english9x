import type { TFunction } from 'i18next';
import { ApiProblemError } from '../../../api/problem';

/** A message for a refused decision, chosen from the status and the problem type; never the server's own text. */
export function reviewProblemText(t: TFunction<'review'>, err: unknown, ownVersion: boolean): string {
  if (err instanceof ApiProblemError) {
    if (err.type === 'urn:e9x:problem:unpublished-dependencies') return t('errors.dependencies');
    if (err.type === 'urn:e9x:problem:review-conflict' || err.status === 409) return t('errors.conflict');
    if (err.status === 403) return ownVersion ? t('errors.own') : t('errors.forbidden');
    if (err.status === 404) return t('errors.notFound');
    if (err.status === 400) return t('errors.comment');
  }
  return t('errors.generic');
}
