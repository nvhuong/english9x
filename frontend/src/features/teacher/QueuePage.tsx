import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { ApiProblemError } from '../../api/problem';
import { Badge, Button, EmptyState, Input } from '../../design';
import { FormAlert } from '../auth/components';
import { useClaim, useQueue, useRelease } from './queries';

/** /cms/grading-queue: the submissions waiting for a teacher of this side, and the ones this teacher holds (F35). A claimed submission is hidden from the other teachers. */
export default function QueuePage() {
  const { t } = useTranslation('teacher');
  const [exam, setExam] = useState('');
  const q = useQueue(exam.trim().toUpperCase());
  const claim = useClaim();
  const release = useRelease();
  const lost = claim.error instanceof ApiProblemError && (claim.error.status === 409 || claim.error.status === 404);

  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('queue.title')}</h1>
        <p className="mt-2 max-w-prose text-muted">{t('queue.description')}</p>
      </header>
      <Input label={t('queue.exam')} hint={t('queue.examHint')} value={exam} onChange={(e) => setExam(e.target.value)} className="max-w-xs" />
      {lost && <FormAlert>{t('queue.taken')}</FormAlert>}
      {q.isPending ? (
        <p role="status" className="text-muted">{t('loading')}</p>
      ) : q.isError ? (
        <div className="flex flex-col items-start gap-3">
          <EmptyState title={t('queue.errorTitle')} description={t('queue.error')} />
          <Button variant="secondary" onClick={() => void q.refetch()}>{t('retry')}</Button>
        </div>
      ) : q.data.length === 0 ? (
        <EmptyState title={t('queue.emptyTitle')} description={t('queue.empty')} />
      ) : (
        <ul aria-label={t('queue.title')} className="flex flex-col divide-y divide-line rounded-lg border border-line bg-surface">
          {q.data.map((i) => (
            <li key={i.id} data-testid="queue-item" data-item-id={i.id} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
              <div className="flex flex-col">
                <span className="font-semibold text-ink">{i.examCode}</span>
                <span className="text-xs text-muted">
                  {t('queue.submitted', { at: new Date(i.createdAt).toLocaleString() })}
                  {i.claimDueAt ? ` · ${t('queue.due', { at: new Date(i.claimDueAt).toLocaleString() })}` : ''}
                </span>
              </div>
              <div className="flex items-center gap-3">
                {i.mine ? <Badge tone="info">{t('queue.mine')}</Badge> : <Badge tone="neutral">{t('queue.open')}</Badge>}
                {i.mine ? (
                  <>
                    <Link to={`/cms/grading-queue/${i.id}`} className="text-primary underline">{t('queue.grade')}</Link>
                    <Button variant="secondary" onClick={() => release.mutate(i.id)} disabled={release.isPending}>{t('queue.release')}</Button>
                  </>
                ) : (
                  <Button onClick={() => claim.mutate(i.id)} disabled={claim.isPending}>{t('queue.claim')}</Button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
