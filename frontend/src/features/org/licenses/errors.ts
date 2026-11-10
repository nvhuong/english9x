import { ApiProblemError } from '../../../api/problem';

/** The key (under `licenses.errors`) of a refused licence or seat action: the problem type's last part, or `failed`. */
export function licenseErrorKey(err: unknown): string {
  if (err instanceof ApiProblemError && err.type) {
    const slug = err.type.replace('urn:e9x:problem:', '');
    if (['no-seat-available', 'seat-already-assigned', 'license-not-active', 'course-not-in-license', 'not-an-org-member'].includes(slug)) return slug;
  }
  return 'failed';
}
