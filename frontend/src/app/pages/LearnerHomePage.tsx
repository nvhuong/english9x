import { useTranslation } from 'react-i18next';
import { EmptyState } from '../../design';

export function LearnerHomePage() {
  const { t } = useTranslation();
  return (
    <div>
      <section className="border-b border-line pb-8 pt-4">
        <h1 className="font-serif text-4xl font-bold tracking-tight text-ink sm:text-5xl">{t('app.name')}</h1>
        <p className="mt-3 max-w-prose font-serif text-lg italic text-muted">{t('app.tagline')}</p>
      </section>
      <EmptyState icon="📚" title={t('empty.learner.title')} description={t('empty.learner.description')} />
    </div>
  );
}
