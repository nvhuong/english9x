import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useApi } from '../../api/useApi';
import type { components } from '../../api/generated/client';
import { ctxKey, useContextStore } from '../../app/stores/context';
import { dayStartIso, nextDayStartIso } from './auditText';

export type AuditEntry = components['schemas']['AuditEntry'];
export type AuditPage = components['schemas']['AuditPage'];

/** What the person typed: dates are `yyyy-MM-dd` from the date inputs, the rest is free text; empty = no filter. */
export interface AuditFilters {
  action: string;
  targetType: string;
  targetId: string;
  actor: string;
  from: string;
  to: string;
}

export const NO_FILTERS: AuditFilters = { action: '', targetType: '', targetId: '', actor: '', from: '', to: '' };

/**
 * One page of the log, newest first. The answer depends on the context (only the English9x context may read it), so the key goes
 * through `ctxKey`: switching context drops it.
 */
export function useAuditLog(filters: AuditFilters, page: number) {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'audit', 'list', filters, page),
    retry: false,
    placeholderData: keepPreviousData,
    queryFn: async () =>
      (
        await api.GET('/api/admin/audit-log', {
          params: {
            query: {
              action: filters.action || undefined,
              targetType: filters.targetType.trim() || undefined,
              targetId: filters.targetId.trim() || undefined,
              actor: filters.actor.trim() || undefined,
              from: dayStartIso(filters.from),
              to: nextDayStartIso(filters.to),
              page,
            },
          },
        })
      ).data as AuditPage,
  });
}

/** The actions that occur in the log, for the filter's list. */
export function useAuditActions() {
  const api = useApi();
  const ctx = useContextStore((s) => s.orgId);
  return useQuery({
    queryKey: ctxKey(ctx, 'audit', 'actions'),
    retry: false,
    queryFn: async () => (await api.GET('/api/admin/audit-log/actions')).data as string[],
  });
}
