import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiProblemError } from '../../api/problem';
import { useApi } from '../../api/useApi';
import { ForbiddenPage } from '../../app/pages/ForbiddenPage';
import { useCtxKey } from '../../app/stores/context';
import { Badge, Button, Input } from '../../design';
import { FormAlert } from '../auth/components';

interface DayCost { day: string; costVnd: number; tokens: number; evaluations: number; overThreshold: boolean }
interface PersonCost { day: string; userId: string; email: string; costVnd: number; inputTokens: number; outputTokens: number; evaluations: number }
interface FailedJob { id: string; type: string; attempts: number; lastError: string | null; failedAt: string }
interface Deviation { typeCode: string; typeName: string; samples: number; meanDeviation: number; meanAbsDeviation: number }
interface Monitor {
  from: string; to: string; dailyCostAlert: number; costAlert: boolean; alertDays: string[]; totalCostVnd: number;
  days: DayCost[]; costByDayAndPerson: PersonCost[]; failedJobs: FailedJob[]; aiFailedGradings: number; deviation: Deviation[];
}

function useMonitor(from: string, to: string) {
  const api = useApi();
  return useQuery({
    queryKey: useCtxKey('ai-monitor', from, to),
    retry: false,
    queryFn: async () => (await api.GET('/api/admin/ai-monitor', { params: { query: { ...(from ? { from } : {}), ...(to ? { to } : {}) } } })).data as unknown as Monitor,
  });
}

/** /admin/ai-monitor: failed jobs, AI cost per day and per person, AI-vs-teacher deviation per question type, and the alert for a day above the threshold (F56). SYS_ADMIN only. */
export default function AiMonitorPage() {
  const { t, i18n } = useTranslation('aimonitor');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const q = useMonitor(from, to);
  const money = (v: number) => t('vnd', { value: new Intl.NumberFormat(i18n.language).format(v) });
  if (q.isError && q.error instanceof ApiProblemError && q.error.status === 403) return <ForbiddenPage />;
  const d = q.data;
  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-6 p-4 sm:p-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('title')}</h1>
        <p className="mt-2 max-w-prose text-muted">{t('description')}</p>
      </header>
      <form className="flex flex-wrap items-end gap-3" onSubmit={(e) => e.preventDefault()} aria-label={t('range')}>
        <Input label={t('from')} type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        <Input label={t('to')} type="date" value={to} onChange={(e) => setTo(e.target.value)} />
      </form>
      {q.isPending && <p role="status" className="text-muted">{t('loading')}</p>}
      {q.isError && (
        <div className="flex flex-col items-start gap-2">
          <FormAlert>{t('loadFailed')}</FormAlert>
          <Button variant="secondary" onClick={() => void q.refetch()}>{t('retry')}</Button>
        </div>
      )}
      {d && (
        <>
          {d.costAlert ? (
            <div role="alert" data-testid="cost-alert" className="rounded-md border border-danger bg-danger-bg p-3 text-danger">
              {t('alert', { threshold: money(d.dailyCostAlert), days: d.alertDays.join(', ') })}
            </div>
          ) : (
            <p data-testid="cost-ok" className="text-sm text-muted">{t('noAlert', { threshold: money(d.dailyCostAlert) })}</p>
          )}
          <section aria-labelledby="cost-h" className="flex flex-col gap-2">
            <h2 id="cost-h" className="text-xl font-semibold text-ink">{t('costTitle')}</h2>
            <p className="text-sm text-muted">{t('total', { value: money(d.totalCostVnd), from: d.from, to: d.to })}</p>
            <table className="w-full border-collapse text-left text-sm" data-testid="cost-days">
              <caption className="sr-only">{t('costCaption')}</caption>
              <thead><tr className="border-b border-line text-muted">
                <th scope="col" className="py-2 pr-3 font-semibold">{t('day')}</th>
                <th scope="col" className="py-2 pr-3 font-semibold">{t('cost')}</th>
                <th scope="col" className="py-2 pr-3 font-semibold">{t('tokens')}</th>
                <th scope="col" className="py-2 font-semibold">{t('evaluations')}</th>
              </tr></thead>
              <tbody>
                {d.days.map((x) => (
                  <tr key={x.day} data-testid="cost-day" data-day={x.day} data-over={x.overThreshold} className="border-b border-line">
                    <th scope="row" className="py-2 pr-3 font-normal text-ink">{x.day}</th>
                    <td className="py-2 pr-3 text-ink">{money(x.costVnd)} {x.overThreshold && <Badge tone="danger">{t('overThreshold')}</Badge>}</td>
                    <td className="py-2 pr-3 text-ink">{x.tokens}</td>
                    <td className="py-2 text-ink">{x.evaluations}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
          <section aria-labelledby="person-h" className="flex flex-col gap-2">
            <h2 id="person-h" className="text-xl font-semibold text-ink">{t('personTitle')}</h2>
            {d.costByDayAndPerson.length === 0 ? <p className="text-muted">{t('personEmpty')}</p> : (
              <table className="w-full border-collapse text-left text-sm" data-testid="cost-persons">
                <caption className="sr-only">{t('personCaption')}</caption>
                <thead><tr className="border-b border-line text-muted">
                  <th scope="col" className="py-2 pr-3 font-semibold">{t('day')}</th>
                  <th scope="col" className="py-2 pr-3 font-semibold">{t('person')}</th>
                  <th scope="col" className="py-2 pr-3 font-semibold">{t('cost')}</th>
                  <th scope="col" className="py-2 font-semibold">{t('evaluations')}</th>
                </tr></thead>
                <tbody>
                  {d.costByDayAndPerson.map((p) => (
                    <tr key={`${p.day}-${p.userId}`} data-testid="cost-person" className="border-b border-line">
                      <th scope="row" className="py-2 pr-3 font-normal text-ink">{p.day}</th>
                      <td className="py-2 pr-3 text-ink">{p.email}</td>
                      <td className="py-2 pr-3 text-ink">{money(p.costVnd)}</td>
                      <td className="py-2 text-ink">{p.evaluations}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
          <section aria-labelledby="jobs-h" className="flex flex-col gap-2">
            <h2 id="jobs-h" className="text-xl font-semibold text-ink">{t('jobsTitle')}</h2>
            <p className="text-sm text-muted">{t('aiFailed', { count: d.aiFailedGradings })}</p>
            {d.failedJobs.length === 0 ? <p className="text-muted" data-testid="no-failed-jobs">{t('jobsEmpty')}</p> : (
              <table className="w-full border-collapse text-left text-sm" data-testid="failed-jobs">
                <caption className="sr-only">{t('jobsCaption')}</caption>
                <thead><tr className="border-b border-line text-muted">
                  <th scope="col" className="py-2 pr-3 font-semibold">{t('jobType')}</th>
                  <th scope="col" className="py-2 pr-3 font-semibold">{t('attempts')}</th>
                  <th scope="col" className="py-2 pr-3 font-semibold">{t('failedAt')}</th>
                  <th scope="col" className="py-2 font-semibold">{t('error')}</th>
                </tr></thead>
                <tbody>
                  {d.failedJobs.map((j) => (
                    <tr key={j.id} data-testid="failed-job" className="border-b border-line">
                      <th scope="row" className="py-2 pr-3 font-normal text-ink">{j.type}</th>
                      <td className="py-2 pr-3 text-ink">{j.attempts}</td>
                      <td className="py-2 pr-3 text-ink">{new Date(j.failedAt).toLocaleString(i18n.language)}</td>
                      <td className="py-2 text-ink">{j.lastError ?? ''}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
          <section aria-labelledby="dev-h" className="flex flex-col gap-2">
            <div>
              <h2 id="dev-h" className="text-xl font-semibold text-ink">{t('deviationTitle')}</h2>
              <p className="text-sm text-muted">{t('deviationHint')}</p>
            </div>
            {d.deviation.length === 0 ? <p className="text-muted">{t('deviationEmpty')}</p> : (
              <table className="w-full border-collapse text-left text-sm" data-testid="deviation">
                <caption className="sr-only">{t('deviationCaption')}</caption>
                <thead><tr className="border-b border-line text-muted">
                  <th scope="col" className="py-2 pr-3 font-semibold">{t('questionType')}</th>
                  <th scope="col" className="py-2 pr-3 font-semibold">{t('samples')}</th>
                  <th scope="col" className="py-2 pr-3 font-semibold">{t('meanDeviation')}</th>
                  <th scope="col" className="py-2 font-semibold">{t('meanAbsDeviation')}</th>
                </tr></thead>
                <tbody>
                  {d.deviation.map((x) => (
                    <tr key={x.typeCode} data-testid="deviation-row" data-type={x.typeCode} className="border-b border-line">
                      <th scope="row" className="py-2 pr-3 font-normal text-ink">{x.typeName}</th>
                      <td className="py-2 pr-3 text-ink">{x.samples}</td>
                      <td className="py-2 pr-3 text-ink">{x.meanDeviation}</td>
                      <td className="py-2 text-ink">{x.meanAbsDeviation}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </>
      )}
    </div>
  );
}
