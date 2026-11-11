import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { ApiProblemError } from '../../api/problem';
import { Button, EmptyState } from '../../design';
import { useApi } from '../../api/useApi';
import { sendHeartbeat, useChapterTest, useHeartbeatIntervalMs, useSubmitAttempt } from './queries';
import { useStudyHeartbeat } from './useStudyHeartbeat';
import type { AttemptResultData, ChapterTestData, LearningQuestion } from './types';

function Question({ q, n, value, onChange }: { q: LearningQuestion; n: number; value: string; onChange: (v: string) => void }) {
  const { t } = useTranslation('learning');
  const { stem, options } = q.content;
  return (
    <fieldset className="rounded-md border border-edge p-3 font-sans">
      <legend className="px-1 text-sm font-semibold text-muted">{t('test.questionN', { n })}</legend>
      {stem && <p className="text-ink">{stem}</p>}
      {Array.isArray(options) && options.length > 0 ? (
        <div className="mt-2 flex flex-col gap-1">
          {options.map((o) => (
            <label key={o.key} className="flex items-start gap-2 text-ink">
              <input type="radio" name={`q-${q.versionId}`} value={o.key} checked={value === o.key} onChange={() => onChange(o.key)} />
              <span>
                <strong>{o.key}.</strong> {o.text ?? ''}
              </span>
            </label>
          ))}
        </div>
      ) : (
        <label className="mt-2 flex flex-col gap-1 text-sm font-semibold text-ink">
          {t('test.yourAnswer')}
          <input type="text" value={value} onChange={(e) => onChange(e.target.value)} className="min-h-10 rounded-md border border-edge bg-surface px-3 font-normal" />
        </label>
      )}
    </fieldset>
  );
}

export function AttemptResult({ result, threshold }: { result: AttemptResultData; threshold: number }) {
  const { t } = useTranslation('learning');
  return (
    <div role="status" data-testid="attempt-result" className="rounded-md border border-edge bg-surface p-4 font-sans text-ink">
      <p className="text-lg font-bold">{result.passed ? t('test.passed') : t('test.notPassed', { threshold: Math.round(threshold * 100) })}</p>
      <p>{t('test.score', { score: Math.round(result.score * 100), raw: result.raw, max: result.rawMax })}</p>
      <p>{t('test.best', { score: Math.round(result.bestScore * 100) })}</p>
      <p>{result.attemptsLeft === null || result.attemptsLeft === undefined ? t('test.unlimited') : t('test.attemptsLeft', { count: result.attemptsLeft })}</p>
    </div>
  );
}

/** The form of a chapter test: the questions (no answers), submit once, get the score. Pure: the page below feeds it. */
export function ChapterTestForm({ test, onSubmit, busy, result, failed }: {
  test: ChapterTestData;
  onSubmit: (answers: { questionVersionId: string; answer: string }[]) => void;
  busy?: boolean;
  result?: AttemptResultData | null;
  failed?: boolean;
}) {
  const { t } = useTranslation('learning');
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const canSit = test.canRetake && !result;
  let n = 0;
  return (
    <form
      className="flex max-w-prose flex-col gap-4"
      onSubmit={(e) => {
        e.preventDefault();
        const all = test.groups.flatMap((g) => g.questions);
        onSubmit(all.filter((q) => answers[q.versionId]).map((q) => ({ questionVersionId: q.versionId, answer: answers[q.versionId]! })));
      }}
    >
      <h1 className="font-serif text-3xl font-bold text-ink">{test.title}</h1>
      <p className="text-sm text-muted">
        {t('test.rules', { threshold: Math.round(test.passThreshold * 100) })}{' '}
        {test.maxAttempts === 0 ? t('test.unlimited') : t('test.attempts', { used: test.attemptsUsed, max: test.maxAttempts })}
        {test.bestScore !== null && test.bestScore !== undefined ? ` ${t('test.best', { score: Math.round(test.bestScore * 100) })}` : ''}
      </p>
      {!test.canRetake && !result && (
        <p role="alert" className="font-medium text-danger">
          {t('test.exhausted')}
        </p>
      )}
      {canSit &&
        test.groups.map((g, gi) => (
          <section key={gi} className="flex flex-col gap-3">
            {g.content?.title && <h2 className="font-serif text-xl font-bold text-ink">{g.content.title}</h2>}
            {g.content?.passage && <p className="whitespace-pre-line text-ink">{g.content.passage}</p>}
            {g.questions.map((q) => {
              n += 1;
              return <Question key={q.versionId} q={q} n={n} value={answers[q.versionId] ?? ''} onChange={(v) => setAnswers((a) => ({ ...a, [q.versionId]: v }))} />;
            })}
          </section>
        ))}
      {failed && (
        <p role="alert" className="text-sm font-medium text-danger">
          {t('errors.generic')}
        </p>
      )}
      {result && <AttemptResult result={result} threshold={test.passThreshold} />}
      {canSit && (
        <Button type="submit" loading={busy}>
          {t('test.submit')}
        </Button>
      )}
    </form>
  );
}

/** /learn/chapter-tests/:lessonId (F23). */
export default function ChapterTestPage() {
  const { t } = useTranslation('learning');
  const { lessonId = '' } = useParams();
  const test = useChapterTest(lessonId);
  const submit = useSubmitAttempt(lessonId);
  const api = useApi();
  const intervalMs = useHeartbeatIntervalMs();
  const courseId = test.data?.courseId;
  useStudyHeartbeat({ enabled: Boolean(courseId), intervalMs, send: () => sendHeartbeat(api, courseId!, lessonId) });
  if (test.isPending) return <p role="status" className="text-muted">{t('loading')}</p>;
  if (test.isError || !test.data) {
    const status = test.error instanceof ApiProblemError ? test.error.status : 0;
    return <EmptyState title={t(status === 403 ? 'errors.notEntitled' : status === 409 ? 'errors.locked' : 'errors.notFound')} action={<Link to="/learn" className="text-primary underline">{t('back')}</Link>} />;
  }
  return (
    <div className="flex flex-col gap-4">
      <Link to={`/learn/courses/${test.data.courseId}`} className="text-sm text-primary underline">
        {t('back')}
      </Link>
      <ChapterTestForm test={test.data} onSubmit={(a) => submit.mutate(a)} busy={submit.isPending} result={submit.data} failed={submit.isError} />
    </div>
  );
}
