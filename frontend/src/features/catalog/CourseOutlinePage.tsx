import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { ApiProblemError } from '../../api/problem';
import { Badge, EmptyState } from '../../design';
import { useCourseOutline } from './queries';

/** /courses/:code: the outline of a course (chapters and lesson titles, author), never a lesson's content. */
export default function CourseOutlinePage() {
  const { t } = useTranslation('catalog');
  const { code } = useParams();
  const course = useCourseOutline(code);
  if (course.isPending) {
    return (
      <p role="status" className="text-muted">
        {t('loading')}
      </p>
    );
  }
  const c = course.data;
  if (course.isError || !c) {
    const notFound = course.error instanceof ApiProblemError && course.error.status === 404;
    return (
      <EmptyState
        title={t(notFound ? 'outline.notFoundTitle' : 'errors.generic')}
        description={notFound ? t('outline.notFound') : undefined}
        action={<Link to="/courses" className="text-primary underline">{t('outline.back')}</Link>}
      />
    );
  }
  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-col gap-2">
        <Link to="/courses" className="text-sm text-primary underline">
          {t('outline.back')}
        </Link>
        <h1 className="font-serif text-3xl font-bold text-ink">{c.title}</h1>
        <p className="flex flex-wrap items-center gap-2 text-sm text-muted">
          <Badge>{c.examCode}</Badge>
          <span>{c.code}</span>
          {c.authorDisplay && <span>{t('list.by', { author: c.authorDisplay })}</span>}
        </p>
        {c.description && <p className="max-w-prose text-ink">{c.description}</p>}
        <p className="text-sm text-muted">{t(`rule.${c.unlockRule}`)}</p>
      </header>
      <section aria-labelledby="outline-h" className="flex flex-col gap-4">
        <h2 id="outline-h" className="font-serif text-xl font-bold text-ink">
          {t('outline.heading')}
        </h2>
        <ol className="flex flex-col gap-4">
          {c.chapters.map((ch, i) => (
            <li key={i} className="rounded-md border border-edge p-4">
              <h3 className="font-semibold text-ink">{t('outline.chapterN', { n: i + 1, title: ch.title })}</h3>
              <ul className="mt-2 flex flex-col gap-1">
                {ch.lessons.map((l) => (
                  <li key={l.id} className="flex flex-wrap items-center gap-2 text-ink">
                    <span>{l.title}</span>
                    <Badge>{t(`kind.${l.kind}`)}</Badge>
                  </li>
                ))}
              </ul>
            </li>
          ))}
        </ol>
      </section>
    </div>
  );
}
