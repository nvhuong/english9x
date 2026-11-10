import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useContextStore } from '../../../app/stores/context';
import { hasRole, useRoleStore } from '../../../app/stores/roles';
import { Badge, Button, EmptyState, Select, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../../design';
import { useExamScores, useScoreTableList } from './queries';
import type { ContentStatus } from './types';

const STATUSES: ContentStatus[] = ['DRAFT', 'IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED', 'ARCHIVED'];
const ALL = 'all';

/** /cms/score-tables: the conversion tables of the exams the person writes or reviews (the latest version of each). */
export default function ScoreTableListPage() {
  const { t } = useTranslation('scoring');
  const grants = useRoleStore((s) => s.grants);
  const ctx = useContextStore((s) => s.orgId);
  const exams = useExamScores();
  const [exam, setExam] = useState(ALL);
  const [status, setStatus] = useState(ALL);
  const [page, setPage] = useState(0);
  const examCodes = useMemo(() => (exams.data ?? []).map((e) => e.code).sort(), [exams.data]);
  const list = useScoreTableList({ exam: exam === ALL ? undefined : exam, status: status === ALL ? undefined : (status as ContentStatus), page });
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
            <Link to="/cms/score-tables/new">{t('list.new')}</Link>
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
          options={[{ value: ALL, label: t('list.all') }, ...examCodes.map((e) => ({ value: e, label: e }))]}
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
                <TableHeaderCell>{t('list.colSkill')}</TableHeaderCell>
                <TableHeaderCell>{t('list.colExam')}</TableHeaderCell>
                <TableHeaderCell>{t('list.colStatus')}</TableHeaderCell>
                <TableHeaderCell>{t('list.colVersion')}</TableHeaderCell>
                <TableHeaderCell>{t('list.colRows')}</TableHeaderCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.items.map((s) => (
                <TableRow key={s.tableId}>
                  <TableCell>
                    <Link to={`/cms/score-tables/${s.tableId}`} className="text-primary underline">
                      {s.skill}
                    </Link>
                  </TableCell>
                  <TableCell>{s.examCode}</TableCell>
                  <TableCell>
                    <Badge>{t(`status.${s.status}`)}</Badge>
                  </TableCell>
                  <TableCell>{s.versionNo}</TableCell>
                  <TableCell>{s.function === 'TABLE' ? s.rowCount : t('list.computed', { function: s.function })}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <nav aria-label={t('list.pagination')} className="flex items-center gap-3">
            <Button variant="secondary" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
              {t('list.previous')}
            </Button>
            <span className="text-sm text-muted">{t('list.range', { from: data.offset + 1, to: data.offset + data.items.length, total: data.total })}</span>
            <Button variant="secondary" size="sm" disabled={data.offset + data.items.length >= data.total} onClick={() => setPage(page + 1)}>
              {t('list.next')}
            </Button>
          </nav>
        </>
      )}
    </div>
  );
}
