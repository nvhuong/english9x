import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useContextStore } from '../../../app/stores/context';
import { hasRole, useRoleStore } from '../../../app/stores/roles';
import { Badge, Button, EmptyState, Select, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../../design';
import { useQuestionTypeForms } from '../questions/queries';
import { useSkillList } from './queries';
import type { ContentStatus } from './types';

const STATUSES: ContentStatus[] = ['DRAFT', 'IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED', 'ARCHIVED'];
const ALL = 'all';

/** /cms/grading-skills: the grading skills of the exams the person writes, reviews or teaches (the latest version each of them may read). */
export default function SkillListPage() {
  const { t } = useTranslation('skills');
  const grants = useRoleStore((s) => s.grants);
  const ctx = useContextStore((s) => s.orgId);
  const types = useQuestionTypeForms();
  const [exam, setExam] = useState(ALL);
  const [status, setStatus] = useState(ALL);
  const [page, setPage] = useState(0);
  const exams = useMemo(() => [...new Set((types.data ?? []).map((x) => x.examCode))].sort(), [types.data]);
  const list = useSkillList({ exam: exam === ALL ? undefined : exam, status: status === ALL ? undefined : (status as ContentStatus), page });
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
            <Link to="/cms/grading-skills/new">{t('list.new')}</Link>
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
          options={[{ value: ALL, label: t('list.all') }, ...exams.map((e) => ({ value: e, label: e }))]}
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
                <TableHeaderCell>{t('list.colType')}</TableHeaderCell>
                <TableHeaderCell>{t('list.colExam')}</TableHeaderCell>
                <TableHeaderCell>{t('list.colStatus')}</TableHeaderCell>
                <TableHeaderCell>{t('list.colVersion')}</TableHeaderCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.items.map((s) => (
                <TableRow key={s.skillId}>
                  <TableCell>
                    <Link to={`/cms/grading-skills/${s.skillId}`} className="text-primary underline">
                      {s.questionTypeCode}
                    </Link>
                  </TableCell>
                  <TableCell>{s.examCode}</TableCell>
                  <TableCell>
                    <Badge>{t(`status.${s.status}`)}</Badge>
                  </TableCell>
                  <TableCell>{s.versionNo}</TableCell>
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
