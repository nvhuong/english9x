import { useTranslation } from 'react-i18next';
import { Button } from '../../design';
import { FormAlert, FormNotice } from '../auth/components';

export interface TelegramLinkViewProps {
  enabled: boolean;
  linked: boolean;
  /** A freshly created one-time link, if any. */
  link?: { deepLink: string; expiresAt: string };
  creating?: boolean;
  error?: 'create' | 'unlink';
  onCreate: () => void;
  onUnlink: () => void;
  onCheck: () => void;
}

/** Telegram connection (F54): create a one-time link, open it in Telegram, come back and check. The chat id is never shown. */
export function TelegramLinkView({ enabled, linked, link, creating, error, onCreate, onUnlink, onCheck }: TelegramLinkViewProps) {
  const { t, i18n } = useTranslation('notifications');
  const safe = link && link.deepLink.startsWith('https://t.me/') ? link : undefined;
  return (
    <section aria-labelledby="telegram-title" className="flex flex-col gap-3 rounded-md border border-line bg-surface p-4">
      <h2 id="telegram-title" className="font-serif text-xl font-semibold">
        {t('telegram.title')}
      </h2>
      <p className="text-muted">{t('telegram.description')}</p>
      {!enabled ? (
        <FormNotice tone="info">{t('telegram.unavailable')}</FormNotice>
      ) : (
        <>
          <p role="status" className="font-semibold">
            {linked ? t('telegram.linked') : t('telegram.notLinked')}
          </p>
          {error === 'create' && <FormAlert>{t('telegram.createFailed')}</FormAlert>}
          {error === 'unlink' && <FormAlert>{t('telegram.unlinkFailed')}</FormAlert>}
          {safe && (
            <div className="flex flex-col gap-2">
              <Button asChild variant="accent">
                <a href={safe.deepLink} target="_blank" rel="noopener noreferrer">
                  {t('telegram.open')}
                </a>
              </Button>
              <p className="text-sm text-muted">
                {t('telegram.expires', { time: new Intl.DateTimeFormat(i18n.language, { timeStyle: 'short' }).format(new Date(safe.expiresAt)) })}
              </p>
              <p className="text-sm text-muted">{t('telegram.afterOpen')}</p>
              <div>
                <Button variant="secondary" size="sm" onClick={onCheck}>
                  {t('telegram.check')}
                </Button>
              </div>
            </div>
          )}
          <div className="flex flex-wrap gap-2">
            <Button variant={linked ? 'secondary' : 'primary'} loading={creating} onClick={onCreate}>
              {creating ? t('telegram.creating') : linked ? t('telegram.recreate') : t('telegram.create')}
            </Button>
            {linked && (
              <Button variant="danger" onClick={onUnlink}>
                {t('telegram.unlink')}
              </Button>
            )}
          </div>
          <p className="text-sm text-muted">{t('telegram.rule')}</p>
        </>
      )}
    </section>
  );
}
