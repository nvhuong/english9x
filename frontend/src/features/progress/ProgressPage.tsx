import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Badge, Button, EmptyState } from '../../design';
import { FormAlert } from '../auth/components';
import { useMyProgress } from './queries';
import type { CourseProgress, PendingAssignment, PlanCard, Progress, Score } from './types';

/** /progress: what I have learnt, grouped by the plan (or the seat of my organisation) that gives me each course (F24). Plans that ended are read only. */
export default function ProgressPage() {
  const { t } = useTranslation('progress');
  const q = useMyProgress();
  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-6 p-4 sm:p-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('title')}</h1>
        <p className="mt-2 max-w-prose text-muted">{t('description')}</p>
      </header>
      {q.isPending && <p role="status" className="text-muted">{t('loading')}</p>}
      {q.isError && (
        <div className="flex flex-col items-start gap-2">
          <FormAlert>{t('loadFailed')}</FormAlert>
          <Button variant="secondary" onClick={() => void q.refetch()}>{t('retry')}</Button>
        </div>
      )}
      {q.data && <Body data={q.data} />}
    </div>
  );
}

function Body({ data }: { data: Progress }) {
  const { t } = useTranslation('progress');
  const none = data.plans.length === 0 && data.expired.length === 0;
  return (
    <>
      {none && <EmptyState icon="📚" title={t('empty')} description={t('emptyHint')} />}
      {data.plans.length > 0 && (
        <section aria-labelledby="plans-h" className="flex flex-col gap-4">
          <h2 id="plans-h" className="text-xl font-semibold text-ink">{t('plansTitle')}</h2>
          {data.plans.map((p) => <Plan key={`${p.source}-${p.id}`} plan={p} />)}
        </section>
      )}
      {data.pendingAssignments.length > 0 && <Pending items={data.pendingAssignments} />}
      <Activity data={data} />
      <Series data={data} />
      {data.expired.length > 0 && (
        <section aria-labelledby="expired-h" className="flex flex-col gap-4">
          <div>
            <h2 id="expired-h" className="text-xl font-semibold text-ink">{t('expiredTitle')}</h2>
            <p className="text-sm text-muted">{t('expiredHint')}</p>
          </div>
          {data.expired.map((p) => <Plan key={`${p.source}-${p.id}`} plan={p} />)}
        </section>
      )}
    </>
  );
}

function date(value: string, lang: string) {
  return new Date(value).toLocaleDateString(lang);
}

function Plan({ plan }: { plan: PlanCard }) {
  const { t, i18n } = useTranslation('progress');
  const title = plan.source === 'SEAT' ? t('seatOf', { org: plan.organisation ?? '' }) : (plan.name ?? t('orderPlan'));
  return (
    <article data-testid="plan-card" data-source={plan.source} data-expired={plan.readOnly} aria-labelledby={`plan-${plan.id}`} className="rounded-lg border border-line bg-surface p-4">
      <header className="flex flex-wrap items-center gap-2">
        <h3 id={`plan-${plan.id}`} className="text-lg font-semibold text-ink">{title}</h3>
        <Badge tone={plan.source === 'SEAT' ? 'info' : 'neutral'}>{t(`source.${plan.source}`)}</Badge>
        {plan.readOnly && <Badge tone="warning">{t('readOnly')}</Badge>}
      </header>
      <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-sm sm:grid-cols-4">
        <div><dt className="text-muted">{t('activated')}</dt><dd className="text-ink">{date(plan.activatedAt, i18n.language)}</dd></div>
        <div><dt className="text-muted">{t('validUntil')}</dt><dd className="text-ink">{date(plan.validUntil, i18n.language)}</dd></div>
        <div><dt className="text-muted">{t('daysLeftLabel')}</dt><dd className="text-ink" data-testid="days-left">{plan.readOnly ? t('ended') : t('daysLeft', { count: plan.daysLeft })}</dd></div>
        <div>
          <dt className="text-muted">{t('coursesUsed')}</dt>
          <dd className="text-ink">{plan.coursesMax === null ? plan.coursesUsed : t('usedOfMax', { used: plan.coursesUsed, max: plan.coursesMax })}</dd>
        </div>
      </dl>
      <ul className="mt-3 flex flex-col gap-3">
        {plan.courses.map((c) => <Course key={c.courseId} course={c} />)}
      </ul>
    </article>
  );
}

function ScoreText({ score }: { score: Score | null }) {
  const { t } = useTranslation('progress');
  if (!score) return <>{t('noScore')}</>;
  return <>{score.value} <span className="text-muted">({t(`label.${score.kind}`)})</span></>;
}

function Course({ course }: { course: CourseProgress }) {
  const { t } = useTranslation('progress');
  return (
    <li data-testid="course-progress" data-course={course.courseId} className="rounded-md border border-line p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="font-semibold text-ink">{course.title}</span>
        {course.resumeLessonId && (
          <Link className="text-primary underline" to={`/learn/lessons/${course.resumeLessonId}`}>{t('resume')}</Link>
        )}
      </div>
      <div className="mt-2 flex items-center gap-2">
        <div role="progressbar" aria-label={t('percentOf', { title: course.title })} aria-valuemin={0} aria-valuemax={100} aria-valuenow={course.percent} className="h-2 w-48 overflow-hidden rounded-full bg-sunken">
          <div className="h-full bg-primary" style={{ width: `${course.percent}%` }} />
        </div>
        <span className="text-sm font-semibold text-ink" data-testid="course-percent">{t('percent', { value: course.percent })}</span>
      </div>
      <dl className="mt-2 grid grid-cols-2 gap-x-4 gap-y-1 text-sm sm:grid-cols-3">
        <div><dt className="text-muted">{t('lessons')}</dt><dd className="text-ink">{course.lessonsDone} / {course.lessonsTotal}</dd></div>
        <div><dt className="text-muted">{t('chapters')}</dt><dd className="text-ink">{course.chaptersDone} / {course.chaptersTotal}</dd></div>
        <div><dt className="text-muted">{t('chapterTests')}</dt><dd className="text-ink">{course.chapterTestsDone} / {course.chapterTestsTotal}</dd></div>
        <div><dt className="text-muted">{t('examsTaken')}</dt><dd className="text-ink">{course.examsTaken}</dd></div>
        <div><dt className="text-muted">{t('latestScore')}</dt><dd className="text-ink"><ScoreText score={course.latestScore} /></dd></div>
        <div><dt className="text-muted">{t('bestScore')}</dt><dd className="text-ink"><ScoreText score={course.bestScore} /></dd></div>
        <div><dt className="text-muted">{t('pendingAssignments')}</dt><dd className="text-ink" data-testid="course-pending">{course.pendingAssignments}</dd></div>
      </dl>
    </li>
  );
}

function Pending({ items }: { items: PendingAssignment[] }) {
  const { t, i18n } = useTranslation('progress');
  return (
    <section aria-labelledby="pending-h" className="flex flex-col gap-2">
      <h2 id="pending-h" className="text-xl font-semibold text-ink">{t('pendingTitle')}</h2>
      <ul className="flex flex-col gap-1 text-sm">
        {items.map((a) => (
          <li key={a.id} data-testid="pending-assignment" className="text-ink">
            {a.className} · {t('due', { date: date(a.dueAt, i18n.language) })} {a.overdue && <Badge tone="danger">{t('overdue')}</Badge>}
          </li>
        ))}
      </ul>
    </section>
  );
}

function Activity({ data }: { data: Progress }) {
  const { t, i18n } = useTranslation('progress');
  const max = Math.max(1, ...data.weeklyMinutes.map((w) => w.minutes));
  return (
    <section aria-labelledby="activity-h" className="flex flex-col gap-3">
      <h2 id="activity-h" className="text-xl font-semibold text-ink">{t('activityTitle')}</h2>
      <p className="text-sm text-ink" data-testid="streak">{t('streak', { current: data.streak.currentDays, longest: data.streak.longestDays })}</p>
      <table className="w-full border-collapse text-left text-sm" data-testid="weekly-minutes">
        <caption className="sr-only">{t('weeklyCaption')}</caption>
        <thead>
          <tr className="border-b border-line text-muted">
            <th scope="col" className="py-2 pr-3 font-semibold">{t('week')}</th>
            <th scope="col" className="py-2 font-semibold">{t('minutes')}</th>
          </tr>
        </thead>
        <tbody>
          {data.weeklyMinutes.map((w) => (
            <tr key={w.weekStart} className="border-b border-line">
              <th scope="row" className="py-2 pr-3 font-normal text-ink">{date(w.weekStart, i18n.language)}</th>
              <td className="py-2">
                <div className="flex items-center gap-2">
                  <div aria-hidden="true" className="h-2 w-40 overflow-hidden rounded-full bg-sunken"><div className="h-full bg-primary" style={{ width: `${(w.minutes / max) * 100}%` }} /></div>
                  <span className="text-ink">{w.minutes}</span>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

function Series({ data }: { data: Progress }) {
  const { t, i18n } = useTranslation('progress');
  return (
    <section aria-labelledby="series-h" className="flex flex-col gap-2">
      <div>
        <h2 id="series-h" className="text-xl font-semibold text-ink">{t('seriesTitle')}</h2>
        <p className="text-sm text-muted">{t('seriesHint')}</p>
      </div>
      {data.scoreSeries.length === 0 ? (
        <p className="text-muted">{t('seriesEmpty')}</p>
      ) : (
        <table className="w-full border-collapse text-left text-sm" data-testid="score-series">
          <caption className="sr-only">{t('seriesCaption')}</caption>
          <thead>
            <tr className="border-b border-line text-muted">
              <th scope="col" className="py-2 pr-3 font-semibold">{t('date')}</th>
              <th scope="col" className="py-2 pr-3 font-semibold">{t('exam')}</th>
              <th scope="col" className="py-2 pr-3 font-semibold">{t('score')}</th>
              <th scope="col" className="py-2 font-semibold">{t('predicted')}</th>
            </tr>
          </thead>
          <tbody>
            {data.scoreSeries.map((p) => (
              <tr key={p.attemptId} className="border-b border-line">
                <th scope="row" className="py-2 pr-3 font-normal text-ink">{date(p.at, i18n.language)}</th>
                <td className="py-2 pr-3 text-ink">{p.examCode}</td>
                <td className="py-2 pr-3 text-ink">{p.score} <span className="text-muted">({t(`label.${p.kind}`)})</span></td>
                <td className="py-2 font-semibold text-ink">{p.predicted}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
