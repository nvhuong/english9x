import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import { Button } from '../../design';
import { useAuthStore } from '../../app/stores/auth';
import { logout } from './session';

/** Header entry: sign in / sign up links, or the signed-in person's name and a sign-out button. */
export function AuthMenu() {
  const { t } = useTranslation('auth');
  const navigate = useNavigate();
  const status = useAuthStore((s) => s.status);
  const user = useAuthStore((s) => s.user);
  if (status === 'loading') return null;
  if (status === 'authenticated' && user) {
    return (
      <div className="flex items-center gap-2">
        <span className="max-w-40 truncate text-sm text-muted" title={user.email}>
          {t('nav.signedInAs', { name: user.displayName || user.email })}
        </span>
        <Button
          size="sm"
          variant="secondary"
          onClick={async () => {
            await logout();
            void navigate('/login', { replace: true });
          }}
        >
          {t('nav.logout')}
        </Button>
      </div>
    );
  }
  return (
    <div className="flex items-center gap-2">
      <Link to="/login" className="rounded-sm px-3 py-1 text-sm font-semibold text-primary no-underline hover:bg-sunken">
        {t('nav.login')}
      </Link>
      <Link to="/register" className="rounded-sm bg-primary px-3 py-1 text-sm font-semibold text-on-primary no-underline hover:bg-primary-hover">
        {t('nav.register')}
      </Link>
    </div>
  );
}
