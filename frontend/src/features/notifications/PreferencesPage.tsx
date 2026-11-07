import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { ApiProblemError } from '../../api/problem';
import { useApi } from '../../api/useApi';
import { useAuthStore, type AuthUser } from '../../app/stores/auth';
import { Button, EmptyState } from '../../design';
import { FormAlert, FormNotice } from '../auth/components';
import { PreferencesView, type PrefsEvent } from './PreferencesView';
import { TelegramLinkView } from './TelegramLinkView';
import { usePrefs, useSetPref, useTelegramLink, useTelegramStatus, useTelegramUnlink } from './queries';

const notVerified = (err: unknown) => err instanceof ApiProblemError && err.type === 'urn:e9x:problem:email-not-verified';

/**
 * While the profile in THIS tab says the address is unverified, read it again on mount and whenever the tab gets focus back: the
 * person may have verified in another tab, and only that tab reloads the profile. Once verified, nothing is asked.
 */
function useFreshProfileWhileUnverified() {
  const api = useApi();
  const apiRef = useRef(api);
  useEffect(() => {
    apiRef.current = api;
  }, [api]);
  const unverified = useAuthStore((s) => s.status === 'authenticated' && s.user?.emailVerified === false);
  const refresh = useCallback(async () => {
    try {
      const fresh = (await apiRef.current.GET('/api/auth/me')).data as AuthUser | undefined;
      const current = useAuthStore.getState();
      if (fresh && current.user?.id === fresh.id && fresh.emailVerified !== current.user.emailVerified) current.setUser(fresh);
    } catch {
      // offline or signed out meanwhile: the page keeps what it knows
    }
  }, []);
  useEffect(() => {
    if (!unverified) return;
    void refresh();
    const onFocus = () => void refresh();
    const onVisible = () => {
      if (document.visibilityState === 'visible') void refresh();
    };
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [unverified, refresh]);
}

/** /notifications/preferences: channels per event (F55) and the Telegram connection (F54). */
export default function NotificationPreferencesPage() {
  const { t } = useTranslation('notifications');
  const status = useAuthStore((s) => s.status);
  const user = useAuthStore((s) => s.user);
  const prefs = usePrefs();
  const setPref = useSetPref();
  const telegram = useTelegramStatus();
  const createLink = useTelegramLink();
  const unlink = useTelegramUnlink();
  const [unlinkFailed, setUnlinkFailed] = useState(false);
  useFreshProfileWhileUnverified();
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
  // the profile says so; a refusal says so too, when the profile in this tab is out of date
  const locked = user?.emailVerified === false || notVerified(setPref.error) || notVerified(createLink.error);
  return (
    <div className="flex max-w-3xl flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('prefs.title')}</h1>
        <p className="mt-2 text-muted">{t('prefs.description')}</p>
      </header>
      {locked && <FormNotice tone="info">{t('prefs.verifyFirst')}</FormNotice>}
      {prefs.isPending && <p role="status" className="text-muted">{t('prefs.loading')}</p>}
      {prefs.isError && (
        <div className="flex flex-col items-start gap-2">
          <FormAlert>{t('prefs.loadFailed')}</FormAlert>
          <Button variant="secondary" onClick={() => void prefs.refetch()}>
            {t('prefs.retry')}
          </Button>
        </div>
      )}
      {setPref.isError && !locked && <FormAlert>{t('prefs.saveFailed')}</FormAlert>}
      {prefs.data && (
        <PreferencesView
          events={(prefs.data.events ?? []) as PrefsEvent[]}
          telegramLinked={linked}
          busy={setPref.isPending}
          locked={locked}
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
          locked={locked}
          error={createLink.isError && !locked ? 'create' : unlinkFailed ? 'unlink' : undefined}
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
