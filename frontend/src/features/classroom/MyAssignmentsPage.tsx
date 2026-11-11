import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Badge, Button, EmptyState } from '../../design';
import { FormAlert } from '../auth/components';
import { STATUS_TONE } from './ClassDetailPage';
import { useMyAssignments, type MyAssignment } from './queries';

const when = (iso: string, locale: string) => new Date(iso).toLocaleString(locale, { dateStyle: 'short', timeStyle: 'short' });

/** /assignments: what my teacher gave me in the organisation of the current context, with the due date and where I stand (F46). */
export default function MyAssignmentsPage() {
  const { t, i18n } = useTranslation('classroom');
  const list = useMyAssignments();
  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('mine.title')}</h1>
        <p className="mt-2 max-w-prose text-muted">{t('mine.description')}</p>
      </header>
      {list.isPending && <p role="status" className="text-muted">{t('mine.loading')}</p>}
      {list.isError && (
        <div className="flex flex-col items-start gap-2">
          <FormAlert>{t('mine.loadFailed')}</FormAlert>
          <Button variant="secondary" onClick={() => void list.refetch()}>
            {t('retry')}
          </Button>
        </div>
      )}
      {list.data && list.data.length === 0 && <EmptyState icon="📝" title={t('mine.empty')} description={t('mine.emptyHint')} />}
      {list.data && list.data.length > 0 && (
        <ul aria-label={t('mine.listCaption')} className="flex flex-col gap-2">
          {list.data.map((a) => (
            <Item key={a.id} a={a} locale={i18n.language} />
          ))}
        </ul>
      )}
    </div>
  );
}

function Item({ a, locale }: { a: MyAssignment; locale: string }) {
  const { t } = useTranslation('classroom');
  const to = a.testVersionId ? `/exam/start/${a.testVersionId}` : a.courseId ? `/learn/courses/${a.courseId}` : null;
  const title = a.title ?? (a.testVersionId ? t('assignments.exam') : t('assignments.lesson'));
  return (
    <li data-testid="my-assignment" className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line px-3 py-2">
      <div>
        {to && a.status !== 'SUBMITTED' && a.status !== 'LATE' ? (
          <Link to={to} className="font-semibold text-primary underline">
            {title}
          </Link>
        ) : (
          <span className="font-semibold">{title}</span>
        )}
        <p className="text-sm text-muted">{t('mine.classAndDue', { class: a.className, date: when(a.dueAt, locale) })}</p>
      </div>
      <Badge tone={STATUS_TONE[a.status] ?? 'neutral'}>{t(`status.${a.status}`)}</Badge>
    </li>
  );
}
