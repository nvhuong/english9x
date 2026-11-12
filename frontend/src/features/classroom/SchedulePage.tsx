import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { Badge, Button, EmptyState, type BadgeTone } from '../../design';
import { FormAlert } from '../auth/components';
import { useMySchedule, type ScheduleItem } from './queries';

export const ATTENDANCE_TONE: Record<string, BadgeTone> = { PRESENT: 'success', LATE: 'warning', ABSENT: 'danger', EXCUSED: 'neutral' };

const SHOWN_DAYS = 60;

export const timeOf = (iso: string, locale: string) => new Date(iso).toLocaleTimeString(locale, { timeStyle: 'short' });
export const dayOf = (iso: string, locale: string) => new Date(iso).toLocaleDateString(locale, { weekday: 'long', day: 'numeric', month: 'numeric', year: 'numeric' });

/** Only an https link is ever put in an anchor (the server refuses the rest; this repeats it where the link is used). */
export const isHttps = (url: string | undefined): url is string => !!url && url.startsWith('https://');

/** /schedule: my sessions of the next 60 days in the organisation of the current context, as a learner or as a teacher (F47). */
export default function SchedulePage() {
  const { t, i18n } = useTranslation('classroom');
  const range = useMemo(() => {
    const from = new Date();
    from.setHours(0, 0, 0, 0);
    return { from: from.toISOString(), to: new Date(from.getTime() + SHOWN_DAYS * 86_400_000).toISOString() };
  }, []);
  const list = useMySchedule(range.from, range.to);
  const days = new Map<string, ScheduleItem[]>();
  for (const s of list.data ?? []) {
    const k = dayOf(s.startsAt, i18n.language);
    days.set(k, [...(days.get(k) ?? []), s]);
  }
  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('schedule.title')}</h1>
        <p className="mt-2 max-w-prose text-muted">{t('schedule.description')}</p>
      </header>
      {list.isPending && <p role="status" className="text-muted">{t('schedule.loading')}</p>}
      {list.isError && (
        <div className="flex flex-col items-start gap-2">
          <FormAlert>{t('schedule.loadFailed')}</FormAlert>
          <Button variant="secondary" onClick={() => void list.refetch()}>
            {t('retry')}
          </Button>
        </div>
      )}
      {list.data && list.data.length === 0 && <EmptyState icon="📅" title={t('schedule.empty')} description={t('schedule.emptyHint')} />}
      {[...days.entries()].map(([day, items]) => (
        <section key={day} aria-label={day} className="flex flex-col gap-2">
          <h2 className="font-serif text-xl font-semibold">{day}</h2>
          <ul aria-label={t('schedule.listCaption')} className="flex flex-col gap-2">
            {items.map((s) => (
              <Item key={s.id} s={s} locale={i18n.language} />
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}

function Item({ s, locale }: { s: ScheduleItem; locale: string }) {
  const { t } = useTranslation('classroom');
  return (
    <li data-testid="schedule-item" className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line px-3 py-2">
      <div>
        <p className="font-semibold">{s.className}</p>
        <p className="text-sm text-muted">
          {timeOf(s.startsAt, locale)}–{timeOf(s.endsAt, locale)}
        </p>
        {s.mode === 'OFFLINE' && s.room && <p className="text-sm">{t('sessions.roomLabel', { room: s.room })}</p>}
        {s.mode === 'ONLINE' && isHttps(s.meetingUrl) && (
          <a href={s.meetingUrl} target="_blank" rel="noopener noreferrer" className="text-sm text-primary underline">
            {t('sessions.join')}
          </a>
        )}
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone="neutral">{t(`mode.${s.mode}`)}</Badge>
        <Badge tone="neutral">{t(s.role === 'TEACHER' ? 'schedule.teacher' : 'schedule.learner')}</Badge>
        {s.attendance && <Badge tone={ATTENDANCE_TONE[s.attendance] ?? 'neutral'}>{t('schedule.attendance', { status: t(`attendanceStatus.${s.attendance}`) })}</Badge>}
      </div>
    </li>
  );
}
