import { useTranslation } from 'react-i18next';
import { LanguageSwitch } from '../components/LanguageSwitch';
import { ThemeSwitch } from '../components/ThemeSwitch';

export default function PreferencesPage() {
  const { t } = useTranslation();
  return (
    <div className="flex max-w-xl flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold">{t('preferences.title')}</h1>
        <p className="mt-2 text-muted">{t('preferences.description')}</p>
      </header>
      <LanguageSwitch />
      <ThemeSwitch />
    </div>
  );
}
