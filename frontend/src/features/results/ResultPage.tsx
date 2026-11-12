import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { ApiProblemError } from '../../api/problem';
import { Badge, Button, EmptyState, type BadgeTone } from '../../design';
import { FormAlert } from '../auth/components';
import { answerText, criteriaRows, feedbackText, keySlots } from './format';
import { useAttemptResult } from './queries';
import type { AttemptResult, ProductiveFeedback, QuestionResult, ScoreLabel, SkillScore } from './types';

const LABEL_TONE: Record<ScoreLabel, BadgeTone> = { PRELIMINARY: 'warning', OFFICIAL: 'success', PENDING: 'neutral' };

/** /results/attempts/:attemptId: the scores (labelled preliminary / official), every question with its key and explanation, the feedback on Writing and Speaking (F38). */
export default function ResultPage() {
  const { attemptId = '' } = useParams();
  const { t } = useTranslation('results');
  const q = useAttemptResult(attemptId);
  if (q.isPending) return <p role="status" className="p-6 text-muted">{t('attempt.loading')}</p>;
  if (q.isError) {
    const status = q.error instanceof ApiProblemError ? q.error.status : 0;
    return (
      <div className="mx-auto flex max-w-xl flex-col gap-3 p-6">
        {status === 404 ? <EmptyState title={t('attempt.notFound')} /> : status === 409 ? <EmptyState title={t('attempt.notSubmitted')} /> : <FormAlert>{t('attempt.loadFailed')}</FormAlert>}
        {status !== 404 && status !== 409 && <Button variant="secondary" onClick={() => void q.refetch()}>{t('retry')}</Button>}
      </div>
    );
  }
  return <Result r={q.data} />;
}

function Result({ r }: { r: AttemptResult }) {
  const { t } = useTranslation('results');
  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6 p-4 sm:p-6">
      <header className="flex flex-col gap-2">
        <h1 className="font-serif text-3xl font-bold text-ink">{t('attempt.title')}</h1>
        <p className="text-muted">{r.examCode}</p>
        {r.interrupted && (
          <p data-testid="interrupted-warning" role="note" className="rounded-md border border-warning bg-warning-bg px-3 py-2 text-warning">
            {t('attempt.interrupted')}
          </p>
        )}
      </header>
      {r.state === 'SCORING' ? (
        <p role="status" data-testid="scoring" className="text-muted">{t('attempt.scoring')}</p>
      ) : (
        <>
          <Scores skills={r.skills} />
          <Questions questions={r.questions} />
          <Feedback feedback={r.feedback} />
          <Link to="/results/weakness" className="text-primary underline">{t('attempt.weaknessLink')}</Link>
        </>
      )}
    </div>
  );
}

function Scores({ skills }: { skills: SkillScore[] }) {
  const { t } = useTranslation('results');
  if (skills.length === 0) return null;
  return (
    <section aria-labelledby="scores-h" className="flex flex-col gap-3">
      <h2 id="scores-h" className="text-xl font-semibold text-ink">{t('attempt.scoresTitle')}</h2>
      <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {skills.map((s) => (
          <li key={s.skill} data-testid="skill-score" data-skill={s.skill} data-label={s.label} className="flex flex-col gap-1 rounded-md border border-line p-3">
            <div className="flex items-center justify-between gap-2">
              <span className="font-semibold text-ink">{s.aggregate ? t('attempt.overall') : s.skill}</span>
              <Badge tone={LABEL_TONE[s.label]} title={t(`attempt.labelHelp.${s.label}`)}>{t(`attempt.label.${s.label}`)}</Badge>
            </div>
            <p className="text-2xl font-bold text-ink">
              {s.score !== null ? s.score : s.label === 'PENDING' ? t('attempt.pending') : t('attempt.noScore')}
            </p>
            {s.raw !== null && s.rawMax !== null && <p className="text-sm text-muted">{t('attempt.rawPoints', { raw: s.raw, max: s.rawMax })}</p>}
            {s.source && <p className="text-xs text-muted">{t(`attempt.source.${s.source}`)}</p>}
          </li>
        ))}
      </ul>
    </section>
  );
}

function Questions({ questions }: { questions: QuestionResult[] }) {
  const { t } = useTranslation('results');
  const [wrongOnly, setWrongOnly] = useState(false);
  const shown = useMemo(() => questions.map((q, i) => ({ q, n: i + 1 })).filter(({ q }) => !wrongOnly || (q.scored && !q.correct)), [questions, wrongOnly]);
  if (questions.length === 0) return null;
  return (
    <section aria-labelledby="questions-h" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h2 id="questions-h" className="text-xl font-semibold text-ink">{t('attempt.questionsTitle')}</h2>
          <p className="text-sm text-muted">{t('attempt.questionsHint')}</p>
        </div>
        <label className="flex items-center gap-2 text-sm text-ink">
          <input type="checkbox" checked={wrongOnly} onChange={(e) => setWrongOnly(e.target.checked)} />
          {t('attempt.filterWrong')}
        </label>
      </div>
      <ol className="flex flex-col gap-3">
        {shown.map(({ q, n }) => (
          <QuestionRow key={`${q.questionId ?? n}`} q={q} n={n} />
        ))}
      </ol>
    </section>
  );
}

function QuestionRow({ q, n }: { q: QuestionResult; n: number }) {
  const { t } = useTranslation('results');
  const given = answerText(q.answer);
  const slots = keySlots(q.correctAnswer, t('attempt.alternatives'));
  return (
    <li data-testid="question-result" data-correct={q.scored ? String(q.correct) : 'n/a'} className="flex flex-col gap-2 rounded-md border border-line p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="font-semibold text-ink">{t('attempt.question', { n })} · <span className="font-normal text-muted">{q.typeName}</span></span>
        {q.scored ? <Badge tone={q.correct ? 'success' : 'danger'}>{q.correct ? t('attempt.right') : t('attempt.wrong')}</Badge> : <Badge>{t('attempt.notAutoScored')}</Badge>}
      </div>
      <p className="text-sm text-ink">
        <span className="font-semibold">{t('attempt.yourAnswer')}: </span>
        {given === '' ? <span className="text-muted">{t('attempt.noAnswer')}</span> : given}
      </p>
      {q.scored && slots.length > 0 && (
        <p className="text-sm text-ink">
          <span className="font-semibold">{t('attempt.correctAnswer')}: </span>
          {slots.join(' · ')}
        </p>
      )}
      {q.scored && <p className="text-xs text-muted">{t('attempt.points', { earned: q.earned, max: q.max })}</p>}
      {q.explanation && (
        <div className="rounded-md bg-sunken p-2 text-sm text-ink">
          <p className="font-semibold">{t('attempt.explanation')}</p>
          <p className="whitespace-pre-wrap">{q.explanation}</p>
        </div>
      )}
      {q.transcript && (
        <details className="rounded-md bg-sunken p-2 text-sm text-ink">
          <summary className="cursor-pointer font-semibold">{t('attempt.transcript')}</summary>
          <p className="mt-1 whitespace-pre-wrap">{q.transcript}</p>
        </details>
      )}
    </li>
  );
}

function Feedback({ feedback }: { feedback: ProductiveFeedback[] }) {
  const { t } = useTranslation('results');
  if (feedback.length === 0) return null;
  return (
    <section aria-labelledby="feedback-h" className="flex flex-col gap-3">
      <h2 id="feedback-h" className="text-xl font-semibold text-ink">{t('attempt.feedbackTitle')}</h2>
      {feedback.map((f) => (
        <div key={f.skill} data-testid="feedback" data-skill={f.skill} className="flex flex-col gap-3 rounded-md border border-line p-3">
          <h3 className="font-semibold text-ink">{t('attempt.feedbackSkill', { skill: f.skill })}</h3>
          {f.ai.map((a) => (
            <div key={a.questionId} className="flex flex-col gap-1">
              <p className="text-sm font-semibold text-ink">{t('attempt.aiFeedback')} · {t('attempt.band', { band: a.band })}</p>
              <CriteriaList criteria={(a.result as { criteria?: never } | null)?.criteria ?? null} />
              {feedbackText(a.result) && <p className="whitespace-pre-wrap text-sm text-ink">{feedbackText(a.result)}</p>}
            </div>
          ))}
          {f.teacher && (
            <div className="flex flex-col gap-1">
              <p className="text-sm font-semibold text-ink">{t('attempt.teacherFeedback')} · {t('attempt.band', { band: f.teacher.band })}</p>
              <CriteriaList criteria={f.teacher.criteria} />
              {f.teacher.comment && <p className="whitespace-pre-wrap text-sm text-ink">{f.teacher.comment}</p>}
            </div>
          )}
        </div>
      ))}
    </section>
  );
}

function CriteriaList({ criteria }: { criteria: unknown }) {
  const { t } = useTranslation('results');
  const rows = criteriaRows(criteria as never);
  if (rows.length === 0) return null;
  return (
    <dl aria-label={t('attempt.criteria')} className="grid grid-cols-[auto_1fr] gap-x-3 text-sm text-ink">
      {rows.map(([code, v]) => (
        <div key={code} className="contents">
          <dt className="font-semibold">{code}</dt>
          <dd>{v}</dd>
        </div>
      ))}
    </dl>
  );
}
