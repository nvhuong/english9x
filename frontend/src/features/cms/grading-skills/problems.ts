import type { TFunction } from 'i18next';
import { ApiProblemError } from '../../../api/problem';

export interface FieldIssue {
  field: string;
  message: string;
}

/** The refusals the author can act on, as one line each. */
export function skillProblemText(t: TFunction<'skills'>, err: unknown): string {
  if (err instanceof ApiProblemError) {
    switch (err.type) {
      case 'urn:e9x:problem:grading-skill-invalid':
        return t('errors.invalid');
      case 'urn:e9x:problem:grading-skill-exists':
        return t('errors.exists');
      case 'urn:e9x:problem:dry-run-unavailable':
        return t('dryRun.unavailable');
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

/** The reasons for one row (`criteria[2]` or `samples[0]`), as one line. */
export function rowIssue(issues: FieldIssue[], prefix: string, index: number): string | undefined {
  const start = `${prefix}[${index}]`;
  const hits = issues.filter((i) => i.field === start || i.field.startsWith(`${start}.`));
  return hits.length ? hits.map((i) => `${i.field.slice(start.length + 1) || '·'}: ${i.message}`).join(' ') : undefined;
}

export const fieldIssue = (issues: FieldIssue[], name: string): string | undefined => {
  const hits = issues.filter((i) => i.field === name || i.field.startsWith(`${name}.`));
  return hits.length ? hits.map((i) => i.message).join(' ') : undefined;
};
