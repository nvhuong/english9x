import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useContextStore } from '../../../app/stores/context';
import { hasRole, useRoleStore } from '../../../app/stores/roles';
import { Badge, Button, EmptyState, Select, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../../design';
import { useQuestionList, useQuestionTypeForms } from './queries';
import type { ContentStatus } from './types';

const STATUSES: ContentStatus[] = ['DRAFT', 'IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED', 'ARCHIVED'];
const ALL = 'all';

/** /cms/questions: the questions of the exams the person writes or reviews in this context (the latest version of each), filtered by exam, type and status. */
export default function QuestionListPage() {
  const { t } = useTranslation('questions');
  const grants = useRoleStore((s) => s.grants);
  const ctx = useContextStore((s) => s.orgId);
  const types = useQuestionTypeForms();
  const [exam, setExam] = useState(ALL);
  const [type, setType] = useState(ALL);
  const [status, setStatus] = useState(ALL);
  const [page, setPage] = useState(0);

  const exams = useMemo(() => [...new Set((types.data ?? []).map((x) => x.examCode))].sort(), [types.data]);
  const typesOfExam = (types.data ?? []).filter((x) => exam === ALL || x.examCode === exam);
  const list = useQuestionList({
    exam: exam === ALL ? undefined : exam,
    type: type === ALL ? undefined : type,
    status: status === ALL ? undefined : (status as ContentStatus),
    page,
  });
  const canWrite = hasRole(grants, ctx, 'AUTHOR');
  const data = list.data;

  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="font-serif text-3xl font-bold text-ink">{t('list.title')}</h1>
          <p className="mt-2 max-w-prose text-muted">{t('list.description')}</p>
        </div>
        {canWrite && (
          <Button asChild>
            <Link to="/cms/questions/new">{t('list.new')}</Link>
          </Button>
        )}
      </header>

      <div className="flex flex-wrap gap-3">
        <Select
          label={t('list.exam')}
          value={exam}
          onValueChange={(v) => {
            setExam(v);
            setType(ALL);
            setPage(0);
          }}
          options={[{ value: ALL, label: t('list.all') }, ...exams.map((e) => ({ value: e, label: e }))]}
          className="min-w-40"
        />
        <Select
          label={t('list.type')}
          value={type}
          onValueChange={(v) => {
            setType(v);
            setPage(0);
          }}
          options={[{ value: ALL, label: t('list.all') }, ...typesOfExam.map((x) => ({ value: x.code, label: x.code }))]}
          className="min-w-64"
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
                <TableHeaderCell>{t('list.colQuestion')}</TableHeaderCell>
                <TableHeaderCell>{t('list.colType')}</TableHeaderCell>
                <TableHeaderCell>{t('list.colStatus')}</TableHeaderCell>
                <TableHeaderCell>{t('list.colVersion')}</TableHeaderCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {data.items.map((q) => (
                <TableRow key={q.questionId}>
                  <TableCell>
                    <Link to={`/cms/questions/${q.questionId}`} className="text-primary underline">
                      {q.title || t('list.untitled')}
                    </Link>
                  </TableCell>
                  <TableCell>
                    <code className="text-xs">{q.typeCode}</code>
                  </TableCell>
                  <TableCell>
                    <Badge>{t(`status.${q.status}`)}</Badge>
                  </TableCell>
                  <TableCell>{q.versionNo}</TableCell>
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
