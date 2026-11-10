import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useContextStore } from '../../../app/stores/context';
import { hasRole, useRoleStore } from '../../../app/stores/roles';
import { Badge, Button, EmptyState, Select, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../../design';
import { useExams, useTestList } from './queries';
import type { ContentStatus } from './types';

const STATUSES: ContentStatus[] = ['DRAFT', 'IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED', 'ARCHIVED'];
const ALL = 'all';

/** /cms/tests: the tests of the exams the person writes or reviews (the latest version of each). */
export default function TestListPage() {
  const { t } = useTranslation('tests');
  const grants = useRoleStore((s) => s.grants);
  const ctx = useContextStore((s) => s.orgId);
  const exams = useExams();
  const [exam, setExam] = useState(ALL);
  const [status, setStatus] = useState(ALL);
  const [page, setPage] = useState(0);
  const list = useTestList({ exam: exam === ALL ? undefined : exam, status: status === ALL ? undefined : (status as ContentStatus), page });
  const data = list.data;
  const canWrite = hasRole(grants, ctx, 'AUTHOR');

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="font-serif text-3xl font-bold text-ink">{t('list.title')}</h1>
          <p className="mt-2 max-w-prose text-muted">{t('list.description')}</p>
        </div>
        {canWrite && (
          <Button asChild>
            <Link to="/cms/tests/new">{t('list.new')}</Link>
          </Button>
        )}
      </header>
      <div className="flex flex-wrap gap-3">
        <Select
          label={t('list.exam')}
          value={exam}
          onValueChange={(v) => {
            setExam(v);
            setPage(0);
          }}
          options={[{ value: ALL, label: t('list.all') }, ...(exams.data ?? []).map((e) => ({ value: e.code, label: e.code }))]}
          className="min-w-40"
        />
        <Select
          label={t('list.status')}
          value={status}
          onValueChange={(v) => {
            setStatus(v);
            setPage(0);
          }}
          options={[{ value: ALL, label: t('list.all') }, ...STATUSES.map((s) => ({ value: s, label: t(`status.${s}`) }))]}
          className="min-w-48"
        />
      </div>
      {list.isPending ? (
        <p role="status" className="text-muted">
          {t('loading')}
        </p>
      ) : list.isError ? (
        <EmptyState title={t('errors.generic')} />
      ) : !data || data.items.length === 0 ? (
        <EmptyState title={t('list.emptyTitle')} description={t('list.empty')} />
      ) : (
        <>
          <Table caption={t('list.caption')}>
            <TableHead>
              <TableRow>
                <TableHeaderCell>{t('list.colTitle')}</TableHeaderCell>
                <TableHeaderCell>{t('list.colExam')}</TableHeaderCell>
                <TableHeaderCell>{t('list.colStatus')}</TableHeaderCell>
                <TableHeaderCell>{t('list.colVersion')}</TableHeaderCell>
                <TableHeaderCell>{t('list.colParts')}</TableHeaderCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.items.map((s) => (
                <TableRow key={s.testId}>
                  <TableCell>
                    <Link to={`/cms/tests/${s.testId}`} className="text-primary underline">
                      {s.title}
                    </Link>
                  </TableCell>
                  <TableCell>{s.variant ? `${s.examCode} ${s.variant}` : s.examCode}</TableCell>
                  <TableCell>
                    <Badge>{t(`status.${s.status}`)}</Badge>
                  </TableCell>
                  <TableCell>{s.versionNo}</TableCell>
                  <TableCell>{s.parts}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <nav aria-label={t('list.pagination')} className="flex items-center gap-3">
            <Button variant="secondary" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
              {t('list.previous')}
            </Button>
            <Button variant="secondary" size="sm" disabled={data.items.length < data.limit} onClick={() => setPage(page + 1)}>
              {t('list.next')}
            </Button>
          </nav>
        </>
      )}
    </div>
  );
}
