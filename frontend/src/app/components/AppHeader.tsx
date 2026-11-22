import { useTranslation } from 'react-i18next';
import { Link, NavLink } from 'react-router-dom';
import { cn } from '../../design';
import { AREAS, AREA_PATH, AREA_ROLES, type Area } from '../areas';
import { AuthMenu } from '../../features/auth/AuthMenu';
import { NotificationBell } from '../../features/notifications/NotificationBell';
import { useContextStore } from '../stores/context';
import { ContextSwitcher } from '../ContextSwitcher';
import { hasRole, useRoleStore } from '../stores/roles';
import { LanguageSwitch } from './LanguageSwitch';
import { ThemeSwitch } from './ThemeSwitch';

export function SkipLink() {
  const { t } = useTranslation();
  return (
    <a href="#main" className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-50 focus:rounded-md focus:bg-primary focus:px-4 focus:py-2 focus:text-on-primary">
      {t('app.skipToContent')}
    </a>
  );
}

/** Areas the signed-in user may enter (learner is open to all; the others need a role). */
function useVisibleAreas(): Area[] {
  const grants = useRoleStore((s) => s.grants);
  const orgId = useContextStore((s) => s.orgId);
  return AREAS.filter((a) => a !== 'exam' && (AREA_ROLES[a] === undefined || hasRole(grants, orgId, AREA_ROLES[a]!)));
}

export function AppHeader({ area, dense }: { area: Area; dense?: boolean }) {
  const { t } = useTranslation();
  const areas = useVisibleAreas();
  return (
    <header className={cn('sticky top-0 z-40 border-b border-line bg-surface/95 backdrop-blur-sm transition-colors', dense && 'bg-sunken/95')}>
      <div className={cn('mx-auto flex flex-wrap items-center gap-x-6 gap-y-2 px-4 py-2.5', dense ? 'max-w-none' : 'max-w-6xl')}>
        <Link to="/" className="group flex items-center gap-2.5 font-serif text-2xl font-bold text-primary no-underline">
          <span
            aria-hidden="true"
            className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary text-xs font-black text-on-primary shadow-sm transition-transform group-hover:scale-105"
          >
            9x
          </span>
          <span className="tracking-tight">{t('app.name')}</span>
        </Link>
        <nav aria-label={t('app.mainNav')} className="flex flex-wrap items-center gap-1.5">
          {areas.map((a) => (
            <NavLink
              key={a}
              to={AREA_PATH[a]}
              end={a === 'learner'}
              className={({ isActive }) =>
                cn(
                  'rounded-lg px-3 py-1.5 text-sm font-semibold no-underline transition-colors',
                  isActive || a === area
                    ? 'bg-primary text-on-primary shadow-sm'
                    : 'text-ink hover:bg-sunken hover:text-primary'
                )
              }
              aria-current={a === area ? 'page' : undefined}
            >
              {t(`area.${a}`)}
            </NavLink>
          ))}
        </nav>
        <div className="ml-auto flex flex-wrap items-center gap-2 sm:gap-3">
          <NotificationBell />
          <ContextSwitcher />
          <LanguageSwitch />
          <ThemeSwitch />
          <AuthMenu />
        </div>
      </div>
    </header>
  );
}
