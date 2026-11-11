import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { ApiProblemError } from '../../api/problem';
import { Badge, Button, EmptyState } from '../../design';
import { FormAlert, FormNotice } from '../auth/components';
import { useDetail, useRecording, useReview } from './queries';
import type { SkillWork, Task } from './types';

/** /cms/grading-queue/:id: the work of a submission the teacher holds (text, recording), the AI's judgement when there is one, the criteria to score and the comment (F35, F36). */
export default function GradingPage() {
  const { t } = useTranslation('teacher');
  const { id = '' } = useParams();
  const detail = useDetail(id);
  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-2">
        <Link to="/cms/grading-queue" className="text-sm text-primary underline">{t('grading.back')}</Link>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('grading.title')}</h1>
        <p className="max-w-prose text-muted">{t('grading.description')}</p>
      </header>
      {detail.isPending ? (
        <p role="status" className="text-muted">{t('loading')}</p>
      ) : detail.isError ? (
        <div className="flex flex-col items-start gap-3">
          <EmptyState title={t('grading.errorTitle')} description={detail.error instanceof ApiProblemError && detail.error.status === 409 ? t('grading.notYours') : t('grading.error')} />
          <Link to="/cms/grading-queue" className="text-primary underline">{t('grading.back')}</Link>
        </div>
      ) : (
        detail.data.skills.map((s) => <SkillCard key={s.skill} id={id} work={s} />)
      )}
    </div>
  );
}

function SkillCard({ id, work }: { id: string; work: SkillWork }) {
  const { t } = useTranslation('teacher');
  const review = useReview(id);
  const [scores, setScores] = useState<Record<string, string>>({});
  const [comment, setComment] = useState('');
  const reviewed = work.review !== null;
  const codes = work.criteria.length > 0 ? work.criteria.map((c) => c.code) : ['TA'];
  const complete = codes.every((c) => scores[c] !== undefined && scores[c] !== '' && !Number.isNaN(Number(scores[c])));
  const submit = () =>
    review.mutate({ skill: work.skill, criteria: Object.fromEntries(codes.map((c) => [c, Number(scores[c])])), comment: comment.trim() });

  return (
    <section aria-label={t(`skills.${work.skill}`, { defaultValue: work.skill })} className="flex flex-col gap-4 rounded-lg border border-line bg-surface p-4">
      <h2 className="flex items-center gap-3 font-serif text-xl font-bold text-ink">
        {t(`skills.${work.skill}`, { defaultValue: work.skill })}
        {work.aiBand !== null && <Badge tone="accent">{t('grading.aiBand', { band: work.aiBand })}</Badge>}
        {reviewed && <Badge tone="success">{t('grading.graded', { band: work.review!.band })}</Badge>}
      </h2>
      {work.tasks.map((task) => (
        <TaskView key={task.questionId} id={id} task={task} />
      ))}
      {review.isSuccess && <FormNotice>{review.data.done ? t('grading.allDone') : t('grading.saved')}</FormNotice>}
      {reviewed ? (
        <div className="flex flex-col gap-1">
          <p className="font-semibold text-ink">{t('grading.yourReview')}</p>
          <p className="text-sm text-muted">{Object.entries(work.review!.criteria).map(([k, v]) => `${k} ${v}`).join(' · ')}</p>
          {work.review!.comment && <p className="text-ink">{work.review!.comment}</p>}
        </div>
      ) : (
        <form className="flex flex-col gap-3" onSubmit={(e) => { e.preventDefault(); submit(); }}>
          <fieldset className="flex flex-wrap gap-4">
            <legend className="mb-1 text-sm font-semibold text-ink">{t('grading.criteria')}</legend>
            {codes.map((code) => {
              const c = work.criteria.find((x) => x.code === code);
              const label = c ? `${c.name} (${code})` : code;
              return (
                <label key={code} className="flex flex-col gap-1 text-sm text-ink">
                  {label}
                  <input
                    type="number"
                    inputMode="decimal"
                    aria-label={label}
                    min={c?.min ?? 0}
                    max={c?.max ?? 9}
                    step={c?.step ?? 0.5}
                    value={scores[code] ?? ''}
                    onChange={(e) => setScores({ ...scores, [code]: e.target.value })}
                    className="w-28 rounded-md border border-line bg-surface p-2 text-ink"
                  />
                </label>
              );
            })}
          </fieldset>
          <label className="flex flex-col gap-1 text-sm text-ink">
            {t('grading.comment')}
            <textarea value={comment} onChange={(e) => setComment(e.target.value)} rows={4} maxLength={4000} className="w-full rounded-md border border-line bg-surface p-2 text-ink" />
          </label>
          {review.isError && <FormAlert>{review.error instanceof ApiProblemError ? (review.error.errors[0]?.message ?? review.error.detail ?? review.error.title) : t('grading.error')}</FormAlert>}
          <div>
            <Button type="submit" disabled={!complete || review.isPending}>{t('grading.submit')}</Button>
          </div>
        </form>
      )}
    </section>
  );
}

function TaskView({ id, task }: { id: string; task: Task }) {
  const { t } = useTranslation('teacher');
  const recording = useRecording(id, task.questionId, task.hasRecording);
  return (
    <div className="flex flex-col gap-2" data-testid="task">
      {task.prompt && <p className="whitespace-pre-line text-sm text-muted">{task.prompt}</p>}
      {task.text ? <p className="whitespace-pre-line rounded-md bg-surface-alt p-3 text-ink">{task.text}</p> : !task.hasRecording && <p className="text-sm text-muted">{t('grading.noAnswer')}</p>}
      {task.hasRecording && (recording.data ? <audio controls src={recording.data} aria-label={t('grading.recording')} /> : <p role="status" className="text-sm text-muted">{t('loading')}</p>)}
      {task.ai && (
        <details className="rounded-md border border-line p-3">
          <summary className="cursor-pointer font-semibold text-ink">{t('grading.ai', { band: task.ai.band })}</summary>
          <dl className="mt-2 flex flex-col gap-1 text-sm">
            {Object.entries(task.ai.result).map(([k, v]) => (
              <div key={k} className="flex gap-2">
                <dt className="font-semibold text-ink">{k}</dt>
                <dd className="text-muted">{typeof v === 'string' || typeof v === 'number' ? String(v) : JSON.stringify(v)}</dd>
              </div>
            ))}
          </dl>
        </details>
      )}
    </div>
  );
}
