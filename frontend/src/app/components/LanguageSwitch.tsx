import { useTranslation } from 'react-i18next';
import { LANGUAGES, setLanguage, type Language } from '../../i18n';
import { cn } from '../../design';

/** vi / en toggle. Choice is saved in this browser (per viewer) and sets <html lang>. */
export function LanguageSwitch({ className }: { className?: string }) {
  const { t, i18n } = useTranslation();
  return (
    <div role="group" aria-label={t('lang.label')} className={cn('inline-flex items-center gap-1', className)}>
      {LANGUAGES.map((lng: Language) => (
        <button
          key={lng}
          type="button"
          lang={lng}
          aria-pressed={i18n.language === lng}
          onClick={() => void setLanguage(lng)}
          className={cn('min-h-8 rounded-sm border px-2 text-sm font-semibold', i18n.language === lng ? 'border-primary bg-primary text-on-primary' : 'border-edge bg-surface text-ink')}
        >
          {lng === 'vi' ? 'VI' : 'EN'}
          <span className="sr-only"> — {t(`lang.${lng}`)}</span>
        </button>
      ))}
    </div>
  );
}
