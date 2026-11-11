import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { Badge, EmptyState } from '../../../design';
import { DecisionBar } from './DecisionBar';
import { useBundleMembers } from './queries';

/** /cms/reviews/bundles/:bundleId: the whole bundle (an Excel import, a course with its lessons) at a glance; it is approved or sent back as one. */
export default function BundleReviewPage() {
  const { t } = useTranslation('review');
  const { bundleId = '' } = useParams();
  const m = useBundleMembers(bundleId);
  if (m.isPending) return <p role="status" className="text-muted">{t('loading')}</p>;
  if (m.isError || m.data.length === 0) {
    return (
      <div className="flex flex-col items-start gap-3">
        <EmptyState title={t('detail.notFoundTitle')} description={t('detail.notFound')} />
        <Link to="/cms/reviews" className="text-primary underline">{t('detail.back')}</Link>
      </div>
    );
  }
  const decided = m.data.every((x) => x.review.status !== 'IN_REVIEW');
  const own = m.data.some((x) => x.ownVersion);
  const author = m.data[0]?.authorName;
  return (
    <div className="flex flex-col gap-6">
      <Link to="/cms/reviews" className="text-sm text-primary underline">{t('detail.back')}</Link>
      <header className="flex flex-col gap-2">
        <h1 className="font-serif text-3xl font-bold text-ink">{t('bundle.title', { count: m.data.length })}</h1>
        <p className="text-muted">{t('queue.author', { name: author ?? t('unknownAuthor') })}</p>
        <p className="max-w-prose text-sm text-muted">{t('bundle.hint')}</p>
      </header>
      <ul aria-label={t('bundle.members')} className="flex flex-col divide-y divide-line rounded-lg border border-line bg-surface">
        {m.data.map((x) => (
          <li key={x.review.id} data-testid="bundle-member" className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
            <Link to={`/cms/reviews/${x.review.id}`} className="text-primary underline">
              {t(`types.${x.review.type}`, { defaultValue: x.review.type })} · <code>{x.review.versionId.slice(0, 8)}</code>
            </Link>
            <span className="flex items-center gap-2 text-xs text-muted">
              {x.review.examCode}
              <Badge tone={x.review.status === 'IN_REVIEW' ? 'info' : 'neutral'}>{t(`status.${x.review.status}`, { defaultValue: x.review.status })}</Badge>
            </span>
          </li>
        ))}
      </ul>
      <DecisionBar id={bundleId} bundle ownVersion={own} decided={decided} />
    </div>
  );
}
