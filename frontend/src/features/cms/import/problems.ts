import type { TFunction } from 'i18next';
import { ApiProblemError } from '../../../api/problem';
import { ImportNetworkError } from './upload';

/** A message for a failed start or download, chosen from the problem `type` and the status; never the server's own text. */
export function importProblemText(t: TFunction<'import'>, err: unknown): string {
  if (err instanceof ImportNetworkError) return t('errors.network');
  if (err instanceof ApiProblemError) {
    switch (err.type) {
      case 'urn:e9x:problem:import-not-xlsx':
        return t('errors.notXlsx');
      case 'urn:e9x:problem:import-not-zip':
        return t('errors.notZip');
      case 'urn:e9x:problem:import-too-large':
      case 'urn:e9x:problem:payload-too-large':
        return t('errors.tooLarge');
      case 'urn:e9x:problem:import-type-unknown':
        return t('errors.type');
      case 'urn:e9x:problem:import-not-submittable':
        return t('errors.notSubmittable');
      case 'urn:e9x:problem:import-unavailable':
        return t('errors.unavailable');
      default:
        break;
    }
    if (err.status === 401 || err.status === 403) return t('errors.forbidden');
    if (err.status === 404) return t('errors.notFound');
    if (err.status === 413) return t('errors.tooLarge');
  }
  return t('errors.generic');
}
