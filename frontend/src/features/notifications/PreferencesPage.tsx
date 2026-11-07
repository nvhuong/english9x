import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useAuthStore } from '../../app/stores/auth';
import { Button, EmptyState } from '../../design';
import { FormAlert } from '../auth/components';
import { PreferencesView, type PrefsEvent } from './PreferencesView';
import { TelegramLinkView } from './TelegramLinkView';
import { usePrefs, useSetPref, useTelegramLink, useTelegramStatus, useTelegramUnlink } from './queries';

/** /notifications/preferences: channels per event (F55) and the Telegram connection (F54). */
export default function NotificationPreferencesPage() {
  const { t } = useTranslation('notifications');
  const status = useAuthStore((s) => s.status);
  const prefs = usePrefs();
  const setPref = useSetPref();
  const telegram = useTelegramStatus();
  const createLink = useTelegramLink();
  const unlink = useTelegramUnlink();
  const [unlinkFailed, setUnlinkFailed] = useState(false);
  if (status === 'loading') return <p role="status" className="text-muted">{t('prefs.loading')}</p>;
  if (status !== 'authenticated') {
    return (
      <EmptyState
        headingLevel={1}
        icon="🔔"
        title={t('prefs.title')}
        description={t('list.signedOut')}
        action={
          <Button asChild>
            <Link to="/login">{t('list.signIn')}</Link>
          </Button>
        }
      />
    );
  }
  const linked = telegram.data?.linked ?? false;
  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('prefs.title')}</h1>
        <p className="mt-2 text-muted">{t('prefs.description')}</p>
      </header>
      {prefs.isPending && <p role="status" className="text-muted">{t('prefs.loading')}</p>}
      {prefs.isError && (
        <div className="flex flex-col items-start gap-2">
          <FormAlert>{t('prefs.loadFailed')}</FormAlert>
          <Button variant="secondary" onClick={() => void prefs.refetch()}>
            {t('prefs.retry')}
          </Button>
        </div>
      )}
      {setPref.isError && <FormAlert>{t('prefs.saveFailed')}</FormAlert>}
      {prefs.data && (
        <PreferencesView
          events={(prefs.data.events ?? []) as PrefsEvent[]}
          telegramLinked={linked}
          busy={setPref.isPending}
          onToggle={(eventType, channel, enabled) => setPref.mutate({ eventType, channel, enabled })}
        />
      )}
      {telegram.isError && (
        <div className="flex flex-col items-start gap-2">
          <FormAlert>{t('telegram.statusFailed')}</FormAlert>
          <Button variant="secondary" onClick={() => void telegram.refetch()}>
            {t('telegram.retryStatus')}
          </Button>
        </div>
      )}
      {telegram.data && (
        <TelegramLinkView
          enabled={telegram.data.enabled}
          linked={linked}
          failing={telegram.data.failing}
          link={createLink.data}
          creating={createLink.isPending}
          error={createLink.isError ? 'create' : unlinkFailed ? 'unlink' : undefined}
          onCreate={() => {
            setUnlinkFailed(false);
            createLink.mutate();
          }}
          onUnlink={() => {
            setUnlinkFailed(false);
            unlink.mutate(undefined, { onSuccess: () => createLink.reset(), onError: () => setUnlinkFailed(true) });
          }}
          onCheck={() => void telegram.refetch()}
        />
      )}
    </div>
  );
}
