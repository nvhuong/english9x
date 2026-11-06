import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { cn, getThemeMode, setThemeMode, type ThemeMode } from '../../design';

const MODES: Array<{ mode: ThemeMode; icon: string }> = [
  { mode: 'light', icon: '☀' },
  { mode: 'dark', icon: '☾' },
  { mode: 'system', icon: '◐' },
];

/** light / dark / system toggle; saved in this browser. */
export function ThemeSwitch({ className }: { className?: string }) {
  const { t } = useTranslation();
  const [current, setCurrent] = useState<ThemeMode>(getThemeMode);
  return (
    <div role="group" aria-label={t('theme.label')} className={cn('inline-flex items-center gap-1', className)}>
      {MODES.map(({ mode, icon }) => (
        <button
          key={mode}
          type="button"
          aria-pressed={current === mode}
          onClick={() => {
            setThemeMode(mode);
            setCurrent(mode);
          }}
          className={cn('min-h-8 min-w-8 rounded-sm border px-2 text-sm font-semibold', current === mode ? 'border-primary bg-primary text-on-primary' : 'border-edge bg-surface text-ink')}
        >
          <span aria-hidden="true">{icon}</span>
          <span className="sr-only">{t(`theme.${mode}`)}</span>
        </button>
      ))}
    </div>
  );
}
