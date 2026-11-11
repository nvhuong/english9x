import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Badge, EmptyState, Select } from '../../design';
import { useCatalog } from './queries';

const ALL = 'all';

/** /courses: what the catalog offers, for anybody. Outlines only. */
export default function CatalogPage() {
  const { t } = useTranslation('catalog');
  const [exam, setExam] = useState(ALL);
  const list = useCatalog(exam === ALL ? undefined : exam);
  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('list.title')}</h1>
        <p className="mt-2 max-w-prose text-muted">{t('list.description')}</p>
      </header>
      <Select
        label={t('list.exam')}
        value={exam}
        onValueChange={setExam}
        options={[
          { value: ALL, label: t('list.all') },
          { value: 'IELTS', label: 'IELTS' },
          { value: 'TOEIC', label: 'TOEIC' },
        ]}
        className="min-w-40 max-w-xs"
      />
      {list.isPending ? (
        <p role="status" className="text-muted">
          {t('loading')}
        </p>
      ) : list.isError ? (
        <EmptyState title={t('errors.generic')} />
      ) : list.data.length === 0 ? (
        <EmptyState title={t('list.emptyTitle')} description={t('list.empty')} />
      ) : (
        <ul className="grid gap-4 sm:grid-cols-2" aria-label={t('list.caption')}>
          {list.data.map((c) => (
            <li key={c.code} className="flex flex-col gap-2 rounded-md border border-edge p-4">
              <h2 className="font-serif text-xl font-bold text-ink">
                <Link to={`/courses/${c.code}`} className="text-primary underline">
                  {c.title}
                </Link>
              </h2>
              <p className="flex flex-wrap items-center gap-2 text-sm text-muted">
                <Badge>{c.examCode}</Badge>
                <span>{c.code}</span>
                <span>{t('list.counts', { chapters: c.chapters, lessons: c.lessons })}</span>
              </p>
              {c.description && <p className="text-ink">{c.description}</p>}
              {c.authorDisplay && <p className="text-sm text-muted">{t('list.by', { author: c.authorDisplay })}</p>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
