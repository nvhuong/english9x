import { useTranslation } from 'react-i18next';
import { NavLink, Outlet } from 'react-router-dom';
import { cn } from '../../design';
import { AREA_ROLES, type Area } from '../areas';
import { AppFooter } from '../components/AppFooter';
import { AppHeader, SkipLink } from '../components/AppHeader';
import { VerifyEmailBanner } from '../../features/auth/VerifyEmailBanner';
import { RequireRole } from '../guards/RequireRole';
import { useAreaNav } from '../nav';
import { useContextStore } from '../stores/context';
import { hasRole, useRoleStore } from '../stores/roles';

/** Area-level guard around the page outlet (the chrome stays visible so the user can navigate away). */
function Guarded({ area }: { area: Area }) {
  const roles = AREA_ROLES[area];
  return roles ? (
    <RequireRole role={roles}>
      <Outlet />
    </RequireRole>
  ) : (
    <Outlet />
  );
}

function NavItems({ area, vertical }: { area: Area; vertical?: boolean }) {
  const { t } = useTranslation();
  const all = useAreaNav(area);
  const grants = useRoleStore((s) => s.grants);
  const orgId = useContextStore((s) => s.orgId);
  const items = all.filter((it) => !it.role || hasRole(grants, orgId, it.role));
  if (items.length === 0) return null;
  return (
    <nav aria-label={t('app.areaNav')} className={cn('flex gap-1', vertical ? 'flex-col' : 'flex-wrap')}>
      {items.map((it) => (
        <NavLink
          key={it.to}
          to={it.to}
          className={({ isActive }) => cn('rounded-sm px-3 py-1.5 text-sm font-semibold no-underline', isActive ? 'bg-primary text-on-primary' : 'text-ink hover:bg-sunken')}
        >
          {t(it.labelKey, { ns: it.ns ?? 'common' })}
        </NavLink>
      ))}
    </nav>
  );
}

/** Learner area: warm paper, editorial single column, top navigation. */
export function LearnerLayout() {
  return (
    <div className="flex min-h-screen flex-col bg-paper">
      <SkipLink />
      <AppHeader area="learner" />
      <VerifyEmailBanner />
      <div className="mx-auto w-full max-w-6xl px-4 pt-3">
        <NavItems area="learner" />
      </div>
      <main id="main" tabIndex={-1} className="mx-auto w-full max-w-5xl flex-1 px-4 py-8 outline-none">
        <Guarded area="learner" />
      </main>
      <AppFooter />
    </div>
  );
}

/** CMS / Organisation / System admin: dense, sidebar navigation, full width. */
export function DenseLayout({ area }: { area: Exclude<Area, 'learner' | 'exam'> }) {
  const { t } = useTranslation();
  return (
    <div className="flex min-h-screen flex-col bg-paper text-[0.9375rem]">
      <SkipLink />
      <AppHeader area={area} dense />
      <div className="flex flex-1 flex-col md:flex-row">
        <aside className="border-b border-line bg-sunken p-3 md:w-60 md:shrink-0 md:border-b-0 md:border-r">
          <p className="mb-2 px-3 text-xs font-bold uppercase tracking-wide text-muted">{t(`area.${area}`)}</p>
          <NavItems area={area} vertical />
        </aside>
        <main id="main" tabIndex={-1} className="min-w-0 flex-1 p-4 outline-none">
          <Guarded area={area} />
        </main>
      </div>
      <AppFooter />
    </div>
  );
}

export const CmsLayout = () => <DenseLayout area="cms" />;
export const OrgLayout = () => <DenseLayout area="org" />;
export const AdminLayout = () => <DenseLayout area="admin" />;

/** Exam room: neutral surface, no navigation or switches, nothing to distract; the page supplies timer and tools. */
export function ExamLayout() {
  const { t } = useTranslation();
  return (
    <div data-surface="exam" className="flex min-h-screen flex-col bg-paper text-ink">
      <SkipLink />
      <header className="flex items-center gap-3 border-b border-line bg-surface px-4 py-2">
        <span className="font-sans text-lg font-bold text-primary">{t('app.name')}</span>
        <span className="text-sm text-muted">{t('area.exam')}</span>
      </header>
      <main id="main" tabIndex={-1} className="flex-1 outline-none">
        <Guarded area="exam" />
      </main>
    </div>
  );
}
