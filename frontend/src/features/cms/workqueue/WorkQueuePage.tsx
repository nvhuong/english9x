import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Badge, Button, EmptyState } from '../../../design';
import type { QuestionSummary } from '../questions/types';
import { useWorkQueue } from './queries';
import type { Section } from './model';

const SECTIONS: Section[] = ['drafts', 'changes', 'review'];

/** /cms/work-queue: "Hàng việc của tôi": my drafts, what was sent back to me for changes, and what waits for my review, in the exams I am assigned. */
export default function WorkQueuePage() {
  const { t } = useTranslation('workqueue');
  const { queue, isPending, isError, refetch, empty, planned } = useWorkQueue();

  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('title')}</h1>
        <p className="mt-2 max-w-prose text-muted">{t('description')}</p>
      </header>
      {planned === 0 ? (
        <EmptyState title={t('noRoleTitle')} description={t('noRole')} />
      ) : isPending ? (
        <p role="status" className="text-muted">
          {t('loading')}
        </p>
      ) : isError ? (
        <div className="flex flex-col items-start gap-3">
          <EmptyState title={t('errorTitle')} description={t('error')} />
          <Button variant="secondary" onClick={refetch}>
            {t('retry')}
          </Button>
        </div>
      ) : empty ? (
        <EmptyState title={t('emptyTitle')} description={t('empty')} />
      ) : null}
      {planned > 0 && !isPending && !isError && !empty && (
        <div className="flex flex-col gap-8">
          {SECTIONS.map((s) => (
            <QueueSection key={s} section={s} items={queue[s]} />
          ))}
        </div>
      )}
    </div>
  );
}

function QueueSection({ section, items }: { section: Section; items: QuestionSummary[] }) {
  const { t } = useTranslation('workqueue');
  const headingId = `queue-${section}`;
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3">
      <h2 id={headingId} className="flex items-center gap-2 font-serif text-xl font-bold text-ink">
        {t(`sections.${section}`)}
        <Badge tone={items.length > 0 ? 'info' : 'neutral'}>{items.length}</Badge>
      </h2>
      <p className="text-sm text-muted">{t(`hints.${section}`)}</p>
      {items.length === 0 ? (
        <p className="text-sm text-muted">{t('none')}</p>
      ) : (
        <ul className="flex flex-col divide-y divide-line rounded-lg border border-line bg-surface">
          {items.map((q) => (
            <li key={q.versionId} className="flex flex-wrap items-center justify-between gap-3 px-4 py-3">
              <div className="flex flex-col">
                <Link to={`/cms/questions/${q.questionId}`} className="text-primary underline">
                  {q.title || t('untitled')}
                </Link>
                <span className="text-xs text-muted">
                  {q.examCode} · <code>{q.typeCode}</code> · {t('version', { n: q.versionNo })}
                </span>
              </div>
              <Link to={`/cms/questions/${q.questionId}/preview`} className="text-sm text-primary underline">
                {t('preview')}
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
