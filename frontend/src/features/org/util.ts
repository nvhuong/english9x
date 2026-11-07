import type { TFunction } from 'i18next';
import { ApiProblemError } from '../../api/problem';
import { orgProblemMessage } from './problems';

/** The message for a failed load, never the server's text. */
export function problemFromResponseSafe(err: unknown, t: TFunction): string {
  return err instanceof ApiProblemError || err instanceof Error ? orgProblemMessage(err, t) : t('problem.generic');
}

/** 'forbidden' / 'not-found' decide which full-page state to show for an organisation page. */
export function pageState(err: unknown): 'not-found' | 'forbidden' | 'error' {
  if (err instanceof ApiProblemError) {
    if (err.status === 404) return 'not-found';
    if (err.status === 403) return 'forbidden';
  }
  return 'error';
}
