import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Select } from '../design';
import { useOrgStore, writeStoredContext } from '../features/org/orgContext';
import { useAuthStore } from './stores/auth';
import { switchContext, useContextStore } from './stores/context';

const PERSONAL = 'personal';

/**
 * The header's context switcher: "Cá nhân" and every organisation the person belongs to (GET /api/me/context).
 * Changing it goes through {@link switchContext}, which cancels and drops everything cached for the old context before any
 * request of the new one is made. Absent for visitors and for people with no organisation: the personal context is implied.
 * The server decides what each context may do; this only chooses where to ask.
 */
export function ContextSwitcher() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const userId = useAuthStore((s) => (s.status === 'authenticated' ? (s.user?.id ?? null) : null));
  const orgs = useOrgStore((s) => s.orgs);
  const current = useContextStore((s) => s.orgId);
  if (userId === null || orgs.length === 0) return null;
  const value = current !== null && orgs.some((o) => o.id === current) ? current : PERSONAL;
  return (
    <Select
      label={t('context.label')}
      value={value}
      options={[{ value: PERSONAL, label: t('context.personal') }, ...orgs.map((o) => ({ value: o.id, label: o.name }))]}
      onValueChange={(v) => {
        const next = v === PERSONAL ? null : v;
        writeStoredContext(userId, next);
        void switchContext(queryClient, next);
      }}
    />
  );
}
