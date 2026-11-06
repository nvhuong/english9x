import { useTranslation } from 'react-i18next';
import { Button, EmptyState } from '../../design';

/** errorElement of the root route: a failed lazy chunk or a render error shows this, not a blank page. */
export function RouteErrorPage() {
  const { t } = useTranslation();
  return (
    <main id="main" className="min-h-screen bg-paper">
      <EmptyState alert headingLevel={1} icon="⚠" title={t('error.title')} description={t('error.description')} action={<Button onClick={() => window.location.reload()}>{t('error.reload')}</Button>} />
    </main>
  );
}
