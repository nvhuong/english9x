import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { Badge, EmptyState } from '../../../design';
import { DecisionBar } from './DecisionBar';
import { useReview } from './queries';
import { VersionDiff } from './VersionDiff';

/** /cms/reviews/:id: one version under review against the earlier published one, with the decision buttons. */
export default function ReviewPage() {
  const { t } = useTranslation('review');
  const { id = '' } = useParams();
  const r = useReview(id);
  if (r.isPending) return <p role="status" className="text-muted">{t('loading')}</p>;
  if (r.isError || !r.data) {
    return (
      <div className="flex flex-col items-start gap-3">
        <EmptyState title={t('detail.notFoundTitle')} description={t('detail.notFound')} />
        <Link to="/cms/reviews" className="text-primary underline">{t('detail.back')}</Link>
      </div>
    );
  }
  const { review, authorName, ownVersion, version, previous } = r.data;
  const decided = review.status !== 'IN_REVIEW';
  return (
    <div className="flex flex-col gap-6">
      <Link to={review.bundleId ? `/cms/reviews/bundles/${review.bundleId}` : '/cms/reviews'} className="text-sm text-primary underline">
        {review.bundleId ? t('detail.backBundle') : t('detail.back')}
      </Link>
      <header className="flex flex-col gap-2">
        <h1 className="font-serif text-3xl font-bold text-ink">{t(`types.${review.type}`, { defaultValue: review.type })}</h1>
        <p className="text-muted">
          {review.examCode} · {t('queue.author', { name: authorName ?? t('unknownAuthor') })} · {new Date(review.submittedAt).toLocaleString()}
        </p>
        <div className="flex flex-wrap gap-2">
          <Badge tone={decided ? 'neutral' : 'info'}>{t(`status.${review.status}`, { defaultValue: review.status })}</Badge>
          {version && <Badge tone="neutral">{t('detail.version', { n: version.versionNo })}</Badge>}
          {previous && <Badge tone="neutral">{t('detail.comparedWith', { n: previous.versionNo })}</Badge>}
        </div>
        {review.comment && <p className="rounded-md border border-line bg-sunken px-3 py-2 text-sm">{t('detail.comment', { text: review.comment })}</p>}
      </header>
      {version ? (
        <section aria-labelledby="diff-title" className="flex flex-col gap-3">
          <h2 id="diff-title" className="font-serif text-xl font-bold text-ink">{t('detail.contentTitle')}</h2>
          <VersionDiff before={previous?.content} after={version.content} />
        </section>
      ) : (
        <p className="text-muted">{t('detail.noContent')}</p>
      )}
      <DecisionBar id={review.id} bundle={false} ownVersion={ownVersion} decided={decided} />
    </div>
  );
}
