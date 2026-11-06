import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Button, EmptyState } from '../../design';

export function NotFoundPage() {
  const { t } = useTranslation();
  return (
    <EmptyState
      headingLevel={1}
      icon="🧭"
      title={t('notFound.title')}
      description={t('notFound.description')}
      action={
        <Button asChild variant="secondary">
          <Link to="/">{t('notFound.back')}</Link>
        </Button>
      }
    />
  );
}
