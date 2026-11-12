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
    <div className="mx-auto flex max-w-4xl flex-col gap-6 p-4 sm:p-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('weakness.title')}</h1>
        <p className="mt-2 max-w-prose text-muted">{t('weakness.description')}</p>
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
        <>
          <p className="text-sm text-muted">{t('weakness.basedOn', { count: q.data.attempts })}</p>
          <Rates types={q.data.types} />
          <Recommendations items={q.data.recommendations} />
        </>
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
    <section aria-labelledby="rec-h" className="flex flex-col gap-3">
      <div>
        <h2 id="rec-h" className="text-xl font-semibold text-ink">{t('weakness.recommendationsTitle')}</h2>
        <p className="text-sm text-muted">{t('weakness.recommendationsHint')}</p>
      </div>
      {items.length === 0 ? (
        <p className="text-muted">{t('weakness.noRecommendations')}</p>
      ) : (
        <ul className="flex flex-col gap-3">
          {items.map((r) => (
            <li key={r.typeCode} data-testid="recommendation" data-type={r.typeCode} className="flex flex-col gap-1 rounded-md border border-line p-3">
              <p className="font-semibold text-ink">{r.typeName} · {t('weakness.percent', { value: percent(r.rate) })}</p>
              <ul className="flex flex-col gap-1">
                {r.lessons.map((l) => (
                  <li key={l.lessonId}>
                    <Link to={`/learn/lessons/${l.lessonId}`} className="text-primary underline">{t('weakness.openLesson', { title: l.title })}</Link>{' '}
                    <span className="text-sm text-muted">{t('weakness.fromCourse', { course: l.courseTitle })}</span>
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
