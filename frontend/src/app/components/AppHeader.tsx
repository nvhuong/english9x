import { useTranslation } from 'react-i18next';
import { Link, NavLink } from 'react-router-dom';
import { cn } from '../../design';
import { AREAS, AREA_PATH, AREA_ROLES, type Area } from '../areas';
import { AuthMenu } from '../../features/auth/AuthMenu';
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
    <header className={cn('border-b border-line bg-surface', dense && 'bg-sunken')}>
      <div className={cn('mx-auto flex flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3', dense ? 'max-w-none' : 'max-w-6xl')}>
        <Link to="/" className="font-serif text-2xl font-bold text-primary no-underline">
          {t('app.name')}
        </Link>
        <nav aria-label={t('app.mainNav')} className="flex flex-wrap gap-1">
          {areas.map((a) => (
            <NavLink
              key={a}
              to={AREA_PATH[a]}
              end={a === 'learner'}
              className={({ isActive }) => cn('rounded-sm px-3 py-1 text-sm font-semibold no-underline', isActive || a === area ? 'bg-primary text-on-primary' : 'text-ink hover:bg-sunken')}
              aria-current={a === area ? 'page' : undefined}
            >
              {t(`area.${a}`)}
            </NavLink>
          ))}
        </nav>
        <div className="ml-auto flex flex-wrap items-center gap-3">
          <ContextSwitcher />
          <LanguageSwitch />
          <ThemeSwitch />
          <AuthMenu />
        </div>
      </div>
    </header>
  );
}
