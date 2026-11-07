import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ForbiddenPage } from '../../app/pages/ForbiddenPage';
import { ApiProblemError } from '../../api/problem';
import { Button, EmptyState } from '../../design';
import { FormAlert } from '../auth/components';
import { AuditFilterForm } from './AuditFilterForm';
import { AuditLogView } from './AuditLogView';
import { NO_FILTERS, useAuditActions, useAuditLog, type AuditFilters } from './queries';

/** /admin/audit-log: the append-only log for the system administrator (F57). Read only. */
export default function AuditLogPage() {
  const { t } = useTranslation('audit');
  const [filters, setFilters] = useState<AuditFilters>(NO_FILTERS);
  const [page, setPage] = useState(0);
  const log = useAuditLog(filters, page);
  const actions = useAuditActions();

  if (log.isError && log.error instanceof ApiProblemError && log.error.status === 403) return <ForbiddenPage />;

  const apply = (next: AuditFilters) => {
    setFilters(next);
    setPage(0);
  };
  const filtered = JSON.stringify(filters) !== JSON.stringify(NO_FILTERS);

  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('title')}</h1>
        <p className="mt-2 max-w-prose text-muted">{t('description')}</p>
      </header>

      <section aria-labelledby="audit-filters" className="flex flex-col gap-3">
        <h2 id="audit-filters" className="font-serif text-xl font-semibold">
          {t('filters.title')}
        </h2>
        <AuditFilterForm value={filters} actions={actions.data ?? []} onApply={apply} />
      </section>

      <section aria-labelledby="audit-results" className="flex flex-col gap-3">
        <h2 id="audit-results" className="font-serif text-xl font-semibold">
          {t('results.title')}
        </h2>
        {log.isPending && (
          <p role="status" className="text-muted">
            {t('results.loading')}
          </p>
        )}
        {log.isError && (
          <div className="flex flex-col items-start gap-2">
            <FormAlert>{t('results.loadFailed')}</FormAlert>
            <Button variant="secondary" onClick={() => void log.refetch()}>
              {t('results.retry')}
            </Button>
          </div>
        )}
        {log.data && log.data.items.length === 0 && (
          <EmptyState
            icon="🗒️"
            title={filtered ? t('results.emptyFiltered') : t('results.empty')}
            description={filtered ? t('results.emptyFilteredHint') : t('results.emptyHint')}
            action={
              filtered ? (
                <Button variant="secondary" onClick={() => apply(NO_FILTERS)}>
                  {t('filters.reset')}
                </Button>
              ) : undefined
            }
          />
        )}
        {log.data && log.data.items.length > 0 && (
          <>
            <AuditLogView entries={log.data.items} />
            <nav aria-label={t('results.pagination')} className="flex flex-wrap items-center justify-between gap-3">
              <p role="status" className="text-muted">
                {t('results.pageOf', { page: log.data.page + 1 })}
              </p>
              <div className="flex gap-2">
                <Button variant="secondary" size="sm" disabled={page === 0 || log.isFetching} onClick={() => setPage((p) => Math.max(0, p - 1))}>
                  {t('results.previous')}
                </Button>
                <Button variant="secondary" size="sm" disabled={!log.data.hasMore || log.isFetching} onClick={() => setPage((p) => p + 1)}>
                  {t('results.next')}
                </Button>
              </div>
            </nav>
          </>
        )}
      </section>
    </div>
  );
}
