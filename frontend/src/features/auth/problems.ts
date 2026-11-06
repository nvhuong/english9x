import type { TFunction } from 'i18next';
import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import { ApiProblemError } from '../../api/problem';

/** A human message for a failed call, from the problem `type` (never from server text). */
export function problemMessage(err: unknown, t: TFunction): string {
  if (err instanceof ApiProblemError) {
    switch (err.type) {
      case 'urn:e9x:problem:invalid-credentials':
        return t('problem.invalidCredentials');
      case 'urn:e9x:problem:too-many-attempts':
        return t('problem.tooManyAttempts');
      case 'urn:e9x:problem:invalid-or-expired-token':
      case 'urn:e9x:problem:invalid-login-code':
        return t('problem.invalidToken');
      default:
        return t('problem.generic');
    }
  }
  return t('problem.network');
}

/**
 * Shows the server's `errors[]` (problem+json) next to the fields they name. `code` (a Bean Validation constraint such
 * as `NotBlank`) is translated; unknown codes fall back to the server's message. Returns true when at least one field
 * error was placed, so the caller only shows a general message otherwise.
 */
export function applyFieldErrors<T extends FieldValues>(
  err: unknown,
  fields: readonly Path<T>[],
  setError: UseFormSetError<T>,
  t: TFunction,
): boolean {
  if (!(err instanceof ApiProblemError) || err.status !== 400) return false;
  let placed = false;
  for (const e of err.errors) {
    const field = fields.find((f) => f === e.field);
    if (!field) continue;
    const key = e.code ? `problem.${e.code}` : '';
    const message = key && t(key, { defaultValue: '' }) ? t(key) : (e.message ?? t('problem.generic'));
    setError(field, { type: 'server', message });
    placed = true;
  }
  return placed;
}

/** A post-login destination from `?next=`: same-site paths only (no scheme, no `//host`). */
export function safeNext(search: string): string {
  const next = new URLSearchParams(search).get('next');
  return next && /^\/(?![/\\])/.test(next) ? next : '/';
}
