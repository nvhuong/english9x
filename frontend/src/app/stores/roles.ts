import { create } from 'zustand';

/** Roles from spec §4. */
export const ROLES = ['LEARNER', 'AUTHOR', 'REVIEWER', 'TEACHER', 'ORG_ADMIN', 'SYS_ADMIN'] as const;
export type Role = (typeof ROLES)[number];

/** `role_grants(user, org, exam, role)`: org null = English9x-wide; exam null = every exam (ORG_ADMIN / SYS_ADMIN only). */
export interface RoleGrant {
  role: Role;
  org: string | null;
  exam: string | null;
}

/**
 * Grants of the signed-in user. P1.4 / P1.5 fill this after sign-in. `null` = not loaded / signed out.
 * NOT a security boundary: the server enforces every permission; this only decides what UI to show.
 */
interface RoleState {
  grants: RoleGrant[] | null;
  setGrants: (grants: RoleGrant[] | null) => void;
}

export const useRoleStore = create<RoleState>((set) => ({
  grants: null,
  setGrants: (grants) => set({ grants }),
}));

const isRole = (r: unknown): r is Role => (ROLES as readonly unknown[]).includes(r);
const ALL_EXAMS_ROLES: readonly Role[] = ['ORG_ADMIN', 'SYS_ADMIN'];

/**
 * Does any grant satisfy one of `want` in the current context?
 * - A grant applies when it is English9x-wide (org null) or belongs to the current org.
 * - With `exam`, the grant must be for that exam, or exam null for ORG_ADMIN / SYS_ADMIN.
 * Unknown/missing grants or unknown role strings never match (denied by default).
 */
export function hasRole(grants: RoleGrant[] | null, ctxOrgId: string | null, want: Role | readonly Role[], exam?: string): boolean {
  if (!grants) return false;
  const wanted = (Array.isArray(want) ? want : [want]) as readonly Role[];
  return grants.some((g) => {
    if (!isRole(g.role) || !wanted.includes(g.role)) return false;
    if (g.org !== null && g.org !== ctxOrgId) return false;
    if (exam === undefined) return true;
    return g.exam === exam || (g.exam === null && ALL_EXAMS_ROLES.includes(g.role));
  });
}
