import type { ReactNode } from 'react';
import { ForbiddenPage } from '../pages/ForbiddenPage';
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
  if (!hasRole(grants, orgId, role, exam)) return <>{fallback ?? <ForbiddenPage />}</>;
  return <>{children}</>;
}
