import type { TFunction } from 'i18next';
import { ApiProblemError } from '../../../api/problem';

export interface FieldIssue {
  field: string;
  message: string;
}

/** The refusals the author can act on, as one line each. */
export function lessonProblemText(t: TFunction<'lessons'>, err: unknown): string {
  if (err instanceof ApiProblemError) {
    switch (err.type) {
      case 'urn:e9x:problem:strategy-lesson-invalid':
        return t('errors.invalid');
      case 'urn:e9x:problem:version-under-review':
        return t('errors.underReview');
      case 'urn:e9x:problem:draft-of-another-author':
        return t('errors.anotherAuthor');
      case 'urn:e9x:problem:version-archived':
        return t('errors.archived');
      case 'urn:e9x:problem:version-not-editable':
        return t('errors.notEditable');
      case 'urn:e9x:problem:version-conflict':
        return t('errors.conflict');
      default:
        break;
    }
    if (err.status === 401 || err.status === 403) return t('errors.forbidden');
    if (err.status === 404) return t('errors.notFound');
  }
  return t('errors.generic');
}

export function issuesOf(err: unknown): FieldIssue[] {
  if (!(err instanceof ApiProblemError)) return [];
  return err.errors.map((e) => ({ field: e.field ?? '', message: e.message ?? '' }));
}
