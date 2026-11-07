import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useAuthStore } from '../../app/stores/auth';
import { Button, EmptyState } from '../../design';
import { FormAlert } from '../auth/components';
import { NotificationListView } from './NotificationListView';
import { useMarkAllRead, useMarkRead, useNotifications } from './queries';

/** /notifications: the person's inbox (F52). The same list whichever organisation context is selected. */
export default function NotificationsPage() {
  const { t } = useTranslation('notifications');
  const status = useAuthStore((s) => s.status);
  const [pages, setPages] = useState(1);
  const list = useNotifications(pages);
  const markRead = useMarkRead();
  const markAll = useMarkAllRead();
  if (status === 'loading') return <p role="status" className="text-muted">{t('list.loading')}</p>;
  if (status !== 'authenticated') {
    return (
      <EmptyState
        headingLevel={1}
        icon="🔔"
        title={t('list.title')}
        description={t('list.signedOut')}
        action={
          <Button asChild>
            <Link to="/login">{t('list.signIn')}</Link>
          </Button>
        }
      />
    );
  }
  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="font-serif text-3xl font-bold text-ink">{t('list.title')}</h1>
          <p className="mt-2 text-muted">{t('list.description')}</p>
        </div>
        <Button asChild variant="secondary" size="sm">
          <Link to="/notifications/preferences">{t('nav.preferences')}</Link>
        </Button>
      </header>
      {list.isPending && <p role="status" className="text-muted">{t('list.loading')}</p>}
      {list.isError && (
        <div className="flex flex-col items-start gap-2">
          <FormAlert>{t('list.loadFailed')}</FormAlert>
          <Button variant="secondary" onClick={() => void list.refetch()}>
            {t('list.retry')}
          </Button>
        </div>
      )}
      {markRead.isError && (
        <div className="flex flex-col items-start gap-2">
          <FormAlert>{t('list.markFailed')}</FormAlert>
          <Button variant="secondary" onClick={() => markRead.variables && markRead.mutate(markRead.variables)}>
            {t('list.retry')}
          </Button>
        </div>
      )}
      {markAll.isError && (
        <div className="flex flex-col items-start gap-2">
          <FormAlert>{t('list.markAllFailed')}</FormAlert>
          <Button variant="secondary" onClick={() => markAll.mutate()}>
            {t('list.retry')}
          </Button>
        </div>
      )}
      {list.data && (
        <NotificationListView
          items={list.data.items}
          unread={list.data.unread}
          hasMore={list.data.hasMore}
          busy={markRead.isPending || markAll.isPending}
          onMarkRead={(id) => markRead.mutate(id)}
          onMarkAll={() => markAll.mutate()}
          onMore={() => setPages((p) => p + 1)}
        />
      )}
    </div>
  );
}
