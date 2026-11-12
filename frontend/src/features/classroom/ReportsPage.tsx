import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { ForbiddenPage } from '../../app/pages/ForbiddenPage';
import { NotFoundPage } from '../../app/pages/NotFoundPage';
import { useContextStore } from '../../app/stores/context';
import { Button, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../design';
import { FormAlert } from '../auth/components';
import { pageState } from '../org/util';
import { useClassReport, useDownloadClassReport, type ReportLearner } from './queries';

/** /org/classes/:classId/report: the class report, one row per learner, and the same figures as an Excel file (F49). The server decides who may read it. */
export default function ReportsPage() {
  const orgId = useContextStore((s) => s.orgId);
  const { classId } = useParams<{ classId: string }>();
  if (!orgId || !classId) return <NotFoundPage />;
  return <Report orgId={orgId} classId={classId} />;
}

function Report({ orgId, classId }: { orgId: string; classId: string }) {
  const { t, i18n } = useTranslation('classroom');
  const report = useClassReport(orgId, classId);
  const download = useDownloadClassReport(orgId, classId);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const locale = i18n.language.startsWith('en') ? 'en' : 'vi';
  const pct = new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: 0 });

  if (report.isPending) return <p role="status" className="text-muted">{t('report.loading')}</p>;
  if (report.isError) {
    const state = pageState(report.error);
    if (state === 'not-found') return <NotFoundPage />;
    if (state === 'forbidden') return <ForbiddenPage />;
    return <FormAlert>{t('report.loadFailed')}</FormAlert>;
  }
  const data = report.data;
  const score = (s: ReportLearner['latestScore']) => (s ? `${s.value} (${t(`report.kind.${s.kind}`)})` : t('report.noScore'));
  return (
    <div className="flex flex-col gap-6">
      <header>
        <Link to={`/org/classes/${classId}`} className="text-primary underline">
          {t('report.back')}
        </Link>
        <h1 className="mt-2 font-serif text-3xl font-bold text-ink">
          {t('report.title')}: {data.className}
        </h1>
        <p className="mt-1 max-w-prose text-muted">{t('report.description')}</p>
        <p className="text-sm text-muted">{t('report.generatedAt', { at: new Date(data.generatedAt).toLocaleString(locale, { dateStyle: 'short', timeStyle: 'short' }) })}</p>
      </header>
      <div className="flex flex-col gap-3">
        {error && <FormAlert>{error}</FormAlert>}
        <div>
          <Button
            loading={busy}
            onClick={() => {
              setError(null);
              setBusy(true);
              download(locale, `class-report-${classId}.xlsx`)
                .catch(() => setError(t('report.downloadFailed')))
                .finally(() => setBusy(false));
            }}
          >
            {t('report.download')}
          </Button>
        </div>
      </div>
      {data.learners.length === 0 ? (
        <p className="text-muted">{t('report.empty')}</p>
      ) : (
        <Table caption={t('report.caption')} dense>
          <TableHead>
            <TableRow>
              <TableHeaderCell>{t('report.col.learner')}</TableHeaderCell>
              <TableHeaderCell>{t('report.col.progress')}</TableHeaderCell>
              <TableHeaderCell>{t('report.col.courses')}</TableHeaderCell>
              <TableHeaderCell>{t('report.col.assignments')}</TableHeaderCell>
              <TableHeaderCell>{t('report.col.latest')}</TableHeaderCell>
              <TableHeaderCell>{t('report.col.best')}</TableHeaderCell>
              <TableHeaderCell>{t('report.col.weak')}</TableHeaderCell>
              <TableHeaderCell>{t('report.col.attendance')}</TableHeaderCell>
              <TableHeaderCell>{t('report.col.minutes')}</TableHeaderCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {data.learners.map((l) => (
              <TableRow key={l.userId} data-testid="report-row">
                <TableCell>{l.displayName ?? l.email ?? l.userId}</TableCell>
                <TableCell data-testid="report-progress">{l.progressPercent == null ? t('report.noCourse') : `${l.progressPercent}%`}</TableCell>
                <TableCell>{l.courses.length === 0 ? t('report.noCourse') : l.courses.map((c) => `${c.title} ${c.percent}%`).join('; ')}</TableCell>
                <TableCell data-testid="report-assignments">
                  {l.assignments.total === 0 ? t('report.noAssignments') : t('report.assignments', l.assignments)}
                </TableCell>
                <TableCell data-testid="report-latest">{score(l.latestScore)}</TableCell>
                <TableCell data-testid="report-best">{score(l.bestScore)}</TableCell>
                <TableCell data-testid="report-weak">
                  {l.weakTypes.length === 0 ? t('report.noWeak') : l.weakTypes.map((w) => `${w.typeName} (${w.correct}/${w.total})`).join('; ')}
                </TableCell>
                <TableCell data-testid="report-attendance">{l.attendance.rate == null ? t('rate.none') : pct.format(l.attendance.rate)}</TableCell>
                <TableCell data-testid="report-minutes">{l.studyMinutes}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  );
}
