import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Button, EmptyState } from '../../design';

export function ForbiddenPage() {
  const { t } = useTranslation();
  return (
    <EmptyState
      alert
      headingLevel={1}
      icon="🔒"
      title={t('denied.title')}
      description={t('denied.description')}
      action={
        <Button asChild variant="secondary">
          <Link to="/">{t('denied.back')}</Link>
        </Button>
      }
    />
  );
}
