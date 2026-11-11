import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Badge, Button, EmptyState } from '../../../design';
import { useReviewQueue } from './queries';

/** /cms/reviews: the open review queue the person may see (a reviewer's, an author's pending submissions): one line per standalone review, one per bundle. */
export default function ReviewQueuePage() {
  const { t } = useTranslation('review');
  const q = useReviewQueue();
  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('queue.title')}</h1>
        <p className="mt-2 max-w-prose text-muted">{t('queue.description')}</p>
      </header>
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
          {q.data.map((l) => {
            const to = l.bundleId ? `/cms/reviews/bundles/${l.bundleId}` : `/cms/reviews/${l.reviewId}`;
            return (
              <li key={l.bundleId ?? l.reviewId} data-testid="review-line" className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
                <div className="flex flex-col">
                  <Link to={to} className="text-primary underline">
                    {l.bundleId ? t('queue.bundle', { count: l.size }) : t(`types.${l.type}`, { defaultValue: l.type })}
                  </Link>
                  <span className="text-xs text-muted">
                    {l.examCode} · {t('queue.author', { name: l.authorName ?? t('unknownAuthor') })} · {new Date(l.submittedAt).toLocaleString()}
                  </span>
                </div>
                <Badge tone="info">{l.bundleId ? t('queue.bundleBadge') : t('queue.single')}</Badge>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
