import { useTranslation } from 'react-i18next';
import { BackendStatus } from './BackendStatus';

export function AppFooter() {
  const { t } = useTranslation();
  return (
    <footer className="border-t border-line px-4 py-4 text-sm text-muted">
      <div className="mx-auto flex max-w-6xl flex-wrap justify-between gap-2">
        <p>{t('app.footer')}</p>
        <BackendStatus />
      </div>
    </footer>
  );
}
