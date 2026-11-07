import { useTranslation } from 'react-i18next';
import { Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../design';
import { eventText } from './eventText';

export interface PrefsEvent {
  type: string;
  audience: string;
  email: boolean;
  telegram: boolean;
}

export interface PreferencesViewProps {
  events: PrefsEvent[];
  telegramLinked: boolean;
  busy?: boolean;
  onToggle: (eventType: string, channel: 'EMAIL' | 'TELEGRAM', enabled: boolean) => void;
}

/** Events x channels. In-app is shown as always on (no control); email and Telegram are one checkbox per event. */
export function PreferencesView({ events, telegramLinked, busy, onToggle }: PreferencesViewProps) {
  const { t } = useTranslation('notifications');
  return (
    <div className="flex flex-col gap-3">
      {!telegramLinked && <p className="text-sm text-muted">{t('prefs.telegramNotLinked')}</p>}
      <Table caption={t('prefs.caption')} dense>
        <TableHead>
          <TableRow>
            <TableHeaderCell>{t('prefs.event')}</TableHeaderCell>
            <TableHeaderCell>{t('prefs.inApp')}</TableHeaderCell>
            <TableHeaderCell>{t('prefs.email')}</TableHeaderCell>
            <TableHeaderCell>{t('prefs.telegram')}</TableHeaderCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {events.map((e) => {
            const title = eventText(t, e.type, undefined).title;
            return (
              <TableRow key={e.type} data-event={e.type}>
                <TableCell>
                  <div className="font-semibold">{title}</div>
                  <div className="text-muted">{t('prefs.audience', { role: t(`audience.${e.audience}`, { defaultValue: e.audience }) })}</div>
                </TableCell>
                <TableCell>
                  <span className="text-muted">{t('prefs.alwaysOn')}</span>
                </TableCell>
                {(['EMAIL', 'TELEGRAM'] as const).map((channel) => {
                  const on = channel === 'EMAIL' ? e.email : e.telegram;
                  const label = t('prefs.toggle', { channel: t(channel === 'EMAIL' ? 'prefs.email' : 'prefs.telegram'), event: title });
                  return (
                    <TableCell key={channel}>
                      <input
                        type="checkbox"
                        className="size-5 accent-primary"
                        aria-label={label}
                        checked={on}
                        disabled={busy}
                        onChange={(ev) => onToggle(e.type, channel, ev.target.checked)}
                      />
                    </TableCell>
                  );
                })}
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
    </div>
  );
}
