import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { ForbiddenPage } from '../pages/ForbiddenPage';
import { useAuthStore } from '../stores/auth';
import { useContextStore } from '../stores/context';
import { hasRole, useRoleStore, type Role } from '../stores/roles';

export interface RequireRoleProps {
  /** One role, or a list where any one suffices. */
  role: Role | readonly Role[];
  /** Restrict to one exam (for exam-scoped roles such as AUTHOR/REVIEWER). */
  exam?: string;
  /** Shown instead of the content when denied. Default: the access-denied page. */
  fallback?: ReactNode;
  children: ReactNode;
}

/**
 * Renders `children` only if the signed-in user holds the role in the current context.
 *
 * UI CONVENIENCE, NOT SECURITY: the browser is untrusted. This only avoids showing screens the user cannot use;
 * every API call is authorised again on the server (spec §4). Unknown/missing roles are denied, never allowed.
 */
export function RequireRole({ role, exam, fallback, children }: RequireRoleProps) {
  const grants = useRoleStore((s) => s.grants);
  const orgId = useContextStore((s) => s.orgId);
  const status = useAuthStore((s) => s.status);
  const { t } = useTranslation();
  if (!hasRole(grants, orgId, role, exam)) {
    // The roles of a signed-in person arrive a moment after the page: say "loading", not "no access", until they have.
    const pending = status === 'loading' || (status === 'authenticated' && grants === null);
    if (pending) return <p role="status" className="p-4 text-muted">{t('app.loading')}</p>;
    return <>{fallback ?? <ForbiddenPage />}</>;
  }
  return <>{children}</>;
}
