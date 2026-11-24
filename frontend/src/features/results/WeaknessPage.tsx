import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { Button, EmptyState } from '../../design';
import { FormAlert } from '../auth/components';
import { percent } from './format';
import { useWeakness } from './queries';
import type { Recommendation, TypeRate } from './types';

/** /results/weakness: the share of right answers per question type over my scored tests, worst first, and the method lessons of my courses to open next (F38). */
export default function WeaknessPage() {
  const { t } = useTranslation('results');
  const [params] = useSearchParams();
  const exam = params.get('exam') ?? '';
  const q = useWeakness(exam);
  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-8 pb-12">
      <header className="rounded-2xl border border-line bg-surface p-6 shadow-card sm:p-8">
        <div className="inline-flex items-center gap-2 rounded-full border border-line bg-sunken px-3 py-0.5 text-xs font-semibold text-primary">
          <span>📊 RADAR ĐỊNH VỊ NĂNG LỰC</span>
        </div>
        <h1 className="mt-3 font-sans text-3xl font-black tracking-tight text-ink sm:text-4xl">{t('weakness.title')}</h1>
        <p className="mt-2 text-base leading-relaxed text-muted">{t('weakness.description')}</p>
      </header>
      {q.isPending && <p role="status" className="text-muted">{t('weakness.loading')}</p>}
      {q.isError && (
        <div className="flex flex-col items-start gap-2">
          <FormAlert>{t('weakness.loadFailed')}</FormAlert>
          <Button variant="secondary" onClick={() => void q.refetch()}>{t('retry')}</Button>
        </div>
      )}
      {q.data && q.data.types.length === 0 && <EmptyState icon="📊" title={t('weakness.empty')} description={t('weakness.emptyHint')} />}
      {q.data && q.data.types.length > 0 && (
        <div className="flex flex-col gap-6">
          <div className="flex items-center justify-between text-xs font-medium text-muted px-1">
            <span>{t('weakness.basedOn', { count: q.data.attempts })}</span>
            <span>Tự động cập nhật theo kết quả thi gần nhất</span>
          </div>
          <div className="rounded-2xl border border-line bg-surface p-6 shadow-card">
            <Rates types={q.data.types} />
          </div>
          <Recommendations items={q.data.recommendations} />
        </div>
      )}
    </div>
  );
}

function Rates({ types }: { types: TypeRate[] }) {
  const { t } = useTranslation('results');
  return (
    <table className="w-full border-collapse text-left text-sm" data-testid="weakness-table">
      <caption className="sr-only">{t('weakness.tableCaption')}</caption>
      <thead>
        <tr className="border-b border-line text-muted">
          <th scope="col" className="py-2 pr-3 font-semibold">{t('weakness.type')}</th>
          <th scope="col" className="py-2 pr-3 font-semibold">{t('weakness.rightOfTotal')}</th>
          <th scope="col" className="py-2 font-semibold">{t('weakness.rate')}</th>
        </tr>
      </thead>
      <tbody>
        {types.map((r) => (
          <tr key={r.typeCode} data-testid="type-rate" data-type={r.typeCode} className="border-b border-line">
            <th scope="row" className="py-2 pr-3 font-normal text-ink">{r.typeName}</th>
            <td className="py-2 pr-3 text-ink">{r.correct} / {r.total}</td>
            <td className="py-2">
              <div className="flex items-center gap-2">
                <div aria-hidden="true" className="h-2 w-32 overflow-hidden rounded-full bg-sunken">
                  <div className="h-full bg-primary" style={{ width: `${percent(r.rate)}%` }} />
                </div>
                <span className="font-semibold text-ink">{t('weakness.percent', { value: percent(r.rate) })}</span>
              </div>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Recommendations({ items }: { items: Recommendation[] }) {
  const { t } = useTranslation('results');
  return (
    <section aria-labelledby="rec-h" className="flex flex-col gap-4">
      <div>
        <h2 id="rec-h" className="font-sans text-xl font-bold tracking-tight text-ink">{t('weakness.recommendationsTitle')}</h2>
        <p className="mt-1 text-sm text-muted">{t('weakness.recommendationsHint')}</p>
      </div>
      {items.length === 0 ? (
        <p className="text-muted">{t('weakness.noRecommendations')}</p>
      ) : (
        <ul className="flex flex-col gap-3">
          {items.map((r) => (
            <li
              key={r.typeCode}
              data-testid="recommendation"
              data-type={r.typeCode}
              className="flex flex-col gap-3 rounded-2xl border border-line bg-surface p-5 shadow-card transition-all hover:border-primary"
            >
              <div className="flex items-center justify-between border-b border-line pb-2.5">
                <span className="font-sans font-bold text-ink">{r.typeName}</span>
                <span className="rounded-full bg-danger-bg px-2.5 py-0.5 font-sans text-xs font-bold text-danger border border-line">
                  {t('weakness.percent', { value: percent(r.rate) })} chính xác
                </span>
              </div>
              <ul className="flex flex-col gap-2">
                {r.lessons.map((l) => (
                  <li key={l.lessonId} className="flex items-center justify-between gap-3 text-sm">
                    <Link
                      to={`/learn/lessons/${l.lessonId}`}
                      className="font-medium text-primary hover:underline"
                    >
                      📖 {t('weakness.openLesson', { title: l.title })}
                    </Link>
                    <span className="text-xs text-muted">{t('weakness.fromCourse', { course: l.courseTitle })}</span>
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
