import { useTranslation } from 'react-i18next';
import { Badge, Button, EmptyState } from '../../design';
import { eventText } from './eventText';

export interface ListItem {
  id: string;
  type: string;
  data?: Record<string, unknown>;
  createdAt: string;
  read: boolean;
}

export interface NotificationListViewProps {
  items: ListItem[];
  unread: number;
  hasMore: boolean;
  busy?: boolean;
  onMarkRead: (id: string) => void;
  onMarkAll: () => void;
  onMore: () => void;
}

function useWhen() {
  const { i18n } = useTranslation();
  return (iso: string) => new Intl.DateTimeFormat(i18n.language, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
}

/** The inbox as a list: newest first, unread ones marked with text (not colour alone), each with its own "mark as read". */
export function NotificationListView({ items, unread, hasMore, busy, onMarkRead, onMarkAll, onMore }: NotificationListViewProps) {
  const { t } = useTranslation('notifications');
  const when = useWhen();
  if (items.length === 0) {
    return <EmptyState icon="🔔" title={t('list.empty')} description={t('list.emptyHint')} headingLevel={2} />;
  }
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p role="status" className="text-muted">
          {unread > 0 ? t('list.unreadCount', { count: unread }) : t('list.allRead')}
        </p>
        <Button variant="secondary" size="sm" disabled={unread === 0 || busy} onClick={onMarkAll}>
          {t('list.markAll')}
        </Button>
      </div>
      <ul aria-label={t('list.caption')} className="flex flex-col divide-y divide-line rounded-md border border-line bg-surface">
        {items.map((n) => {
          const text = eventText(t, n.type, n.data);
          return (
            <li key={n.id} data-unread={n.read ? undefined : 'true'} className="flex flex-wrap items-start justify-between gap-3 p-4">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <h2 className={n.read ? 'font-semibold text-ink' : 'font-bold text-ink'}>{text.title}</h2>
                  <Badge tone={n.read ? 'neutral' : 'info'}>{n.read ? t('list.read') : t('list.unread')}</Badge>
                </div>
                {text.body && <p className="mt-1 text-ink">{text.body}</p>}
                <time dateTime={n.createdAt} className="mt-1 block text-sm text-muted">
                  {when(n.createdAt)}
                </time>
              </div>
              {!n.read && (
                <Button variant="ghost" size="sm" disabled={busy} aria-label={t('list.markReadOf', { title: text.title })} onClick={() => onMarkRead(n.id)}>
                  {t('list.markRead')}
                </Button>
              )}
            </li>
          );
        })}
      </ul>
      {hasMore && (
        <div>
          <Button variant="secondary" onClick={onMore}>
            {t('list.more')}
          </Button>
        </div>
      )}
    </div>
  );
}
