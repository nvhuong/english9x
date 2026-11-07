import type { QueryClient } from '@tanstack/react-query';
import { create } from 'zustand';
import { switchContext, useContextStore } from '../../app/stores/context';
import { useRoleStore, type Role, type RoleGrant } from '../../app/stores/roles';

/** One organisation the signed-in person belongs to (GET /api/me/context). */
export interface OrgSummary {
  id: string;
  name: string;
  type: 'CENTER' | 'ENTERPRISE';
}

export interface MeContextBody {
  contextOrgId: string | null;
  organizations: OrgSummary[];
  grants: Array<{ role: Role; orgId: string | null; examCode: string | null }>;
}

interface OrgState {
  orgs: OrgSummary[];
  /** false until the first answer of /api/me/context for the current person. */
  loaded: boolean;
}

/** The organisations of the signed-in person. Not a security boundary: the server checks the header on every request. */
export const useOrgStore = create<OrgState>(() => ({ orgs: [], loaded: false }));

export function grantsFromApi(grants: MeContextBody['grants']): RoleGrant[] {
  return grants.map((g) => ({ role: g.role, org: g.orgId, exam: g.examCode }));
}

const STORAGE_KEY = 'e9x.context';

/** The context this person chose last time (per person: another account on the same browser inherits nothing). */
export function readStoredContext(userId: string): string | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const v = JSON.parse(raw) as { userId?: unknown; orgId?: unknown };
    return v.userId === userId && typeof v.orgId === 'string' ? v.orgId : null;
  } catch {
    return null;
  }
}

export function writeStoredContext(userId: string, orgId: string | null): void {
  try {
    if (orgId === null) localStorage.removeItem(STORAGE_KEY);
    else localStorage.setItem(STORAGE_KEY, JSON.stringify({ userId, orgId }));
  } catch {
    /* storage unavailable: the choice lasts until reload */
  }
}

/**
 * Which context to be in, once the person's organisations are known. A choice made in this session wins while it is
 * still valid; otherwise the stored one; anything the person no longer belongs to becomes the personal context.
 * (The server would answer 403 to such a header: this makes sure it is never sent, so there is nothing to retry.)
 */
export function resolveContext(stored: string | null, current: string | null, orgs: readonly OrgSummary[]): string | null {
  const valid = (id: string | null) => id !== null && orgs.some((o) => o.id === id);
  if (valid(current)) return current;
  if (valid(stored)) return stored;
  return null;
}

/** Applies an answer of GET /api/me/context: roles, organisations, and the context to be in. */
export async function applyMeContext(queryClient: QueryClient, userId: string, body: MeContextBody): Promise<void> {
  useOrgStore.setState({ orgs: body.organizations, loaded: true });
  const current = useContextStore.getState().orgId;
  const target = resolveContext(readStoredContext(userId), current, body.organizations);
  // the context first, the roles after: a page guarded by a role never sees "my roles, but the wrong context" for a moment
  if (target !== current) await switchContext(queryClient, target);
  if (target === null && current !== null) writeStoredContext(userId, null);
  useRoleStore.getState().setGrants(grantsFromApi(body.grants));
}

/** Forget everything about the previous person (sign-out, another account). */
export function resetOrgState(): void {
  useOrgStore.setState({ orgs: [], loaded: false });
}
