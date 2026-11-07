import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useAuthStore } from '../../app/stores/auth';
import { cn } from '../../design';
import { useUnreadCount } from './queries';

/** Header entry: a bell with the number of unread notifications, linking to the notification centre. Signed-in people only. */
export function NotificationBell() {
  const status = useAuthStore((s) => s.status);
  // a separate component, so that nothing queries (or needs a query client) while nobody is signed in
  return status === 'authenticated' ? <Bell /> : null;
}

function Bell() {
  const { t } = useTranslation('notifications');
  const unread = useUnreadCount();
  const n = unread.data ?? 0;
  return (
    <Link
      to="/notifications"
      aria-label={n > 0 ? t('bell.withUnread', { count: n }) : t('bell.none')}
      className="relative inline-flex size-9 items-center justify-center rounded-md text-ink no-underline hover:bg-sunken"
    >
      <svg aria-hidden="true" viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9" />
        <path d="M13.7 21a2 2 0 0 1-3.4 0" />
      </svg>
      {n > 0 && (
        <span aria-hidden="true" className={cn('absolute -right-1 -top-1 min-w-5 rounded-full bg-accent px-1 text-center text-xs font-bold leading-5 text-on-accent')}>
          {n > 99 ? '99+' : n}
        </span>
      )}
    </Link>
  );
}
