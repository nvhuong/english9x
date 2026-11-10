import type { TFunction } from 'i18next';
import { ApiProblemError } from '../../../api/problem';
import { UploadNetworkError } from './upload';

/** A message for a failed upload or link, chosen from the problem `type` and the status; never the server's own text. */
export function mediaProblemText(t: TFunction<'media'>, err: unknown): string {
  if (err instanceof UploadNetworkError) return t('errors.network');
  if (err instanceof ApiProblemError) {
    switch (err.type) {
      case 'urn:e9x:problem:media-type-not-allowed':
        return t('errors.type');
      case 'urn:e9x:problem:media-extension-mismatch':
        return t('errors.extension');
      case 'urn:e9x:problem:media-too-large':
      case 'urn:e9x:problem:payload-too-large':
        return t('errors.serverTooLarge');
      case 'urn:e9x:problem:media-unreadable':
        return t('errors.unreadable');
      case 'urn:e9x:problem:media-file-missing':
        return t('errors.empty');
      case 'urn:e9x:problem:media-storage-unavailable':
        return t('errors.unavailable');
      default:
        break;
    }
    if (err.status === 401 || err.status === 403) return t('errors.forbidden');
    if (err.status === 404) return t('errors.notFound');
    if (err.status === 413) return t('errors.serverTooLarge');
  }
  return t('errors.generic');
}
