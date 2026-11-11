import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Badge, Button, Input, Select, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../design';
import { FormAlert, FormNotice } from '../auth/components';
import { AttendanceSheet } from './AttendanceSheet';
import { classroomProblemMessage } from './problems';
import { dayOf, isHttps, timeOf } from './SchedulePage';
import { useAttendanceReport, useClassSessions, useCreateSession, type SessionView } from './queries';

/** The class page's schedule and attendance: add online / offline sessions, take attendance per session, see each learner's attendance rate (F47, F48). */
export function SessionsPanel({ orgId, classId }: { orgId: string; classId: string }) {
  const { t, i18n } = useTranslation('classroom');
  const list = useClassSessions(orgId, classId);
  return (
    <div className="flex flex-col gap-4">
      <AddSession orgId={orgId} classId={classId} />
      {list.isPending && <p role="status" className="text-muted">{t('sessions.loading')}</p>}
      {list.isError && <FormAlert>{t('sessions.loadFailed')}</FormAlert>}
      {list.data && list.data.length === 0 && <p className="text-muted">{t('sessions.empty')}</p>}
      {list.data && list.data.length > 0 && (
        <ul aria-label={t('sessions.listCaption')} className="flex flex-col gap-2">
          {list.data.map((s) => (
            <SessionRow key={s.id} orgId={orgId} classId={classId} s={s} locale={i18n.language} />
          ))}
        </ul>
      )}
      <RateReport orgId={orgId} classId={classId} />
    </div>
  );
}

function SessionRow({ orgId, classId, s, locale }: { orgId: string; classId: string; s: SessionView; locale: string }) {
  const { t } = useTranslation('classroom');
  const [open, setOpen] = useState(false);
  return (
    <li data-testid="session-row" className="flex flex-col gap-2 rounded-md border border-line px-3 py-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <p className="font-semibold">{t('sessions.when', { date: dayOf(s.startsAt, locale), from: timeOf(s.startsAt, locale), to: timeOf(s.endsAt, locale) })}</p>
          {s.mode === 'OFFLINE' && s.room && <p className="text-sm">{t('sessions.roomLabel', { room: s.room })}</p>}
          {s.mode === 'ONLINE' && isHttps(s.meetingUrl) && (
            <a href={s.meetingUrl} target="_blank" rel="noopener noreferrer" className="text-sm text-primary underline">
              {t('sessions.join')}
            </a>
          )}
        </div>
        <Badge tone="neutral">{t(`mode.${s.mode}`)}</Badge>
        <Button variant="secondary" aria-expanded={open} onClick={() => setOpen(!open)}>
          {open ? t('sessions.hideAttendance') : t('sessions.attendance')}
        </Button>
      </div>
      {open && <AttendanceSheet orgId={orgId} classId={classId} sessionId={s.id} />}
    </li>
  );
}

function AddSession({ orgId, classId }: { orgId: string; classId: string }) {
  const { t } = useTranslation('classroom');
  const create = useCreateSession(orgId, classId);
  const [mode, setMode] = useState<'OFFLINE' | 'ONLINE'>('OFFLINE');
  const [startsAt, setStartsAt] = useState('');
  const [endsAt, setEndsAt] = useState('');
  const [room, setRoom] = useState('');
  const [url, setUrl] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  return (
    <form
      className="flex max-w-xl flex-col gap-3"
      aria-label={t('sessions.createTitle')}
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        setError(null);
        setNotice(null);
        if (!startsAt || !endsAt) {
          setError(t('validation.session'));
          return;
        }
        const from = new Date(startsAt);
        const to = new Date(endsAt);
        if (!(to > from)) {
          setError(t('validation.sessionEnd'));
          return;
        }
        if (mode === 'ONLINE' && !isHttps(url.trim())) {
          setError(t('validation.meetingUrl'));
          return;
        }
        const body = { mode, startsAt: from.toISOString(), endsAt: to.toISOString(), ...(mode === 'ONLINE' ? { meetingUrl: url.trim() } : room.trim() ? { room: room.trim() } : {}) };
        create.mutate(body, {
          onSuccess: () => {
            setNotice(t('sessions.created'));
            setRoom('');
            setUrl('');
          },
          onError: (err) => setError(classroomProblemMessage(err, t)),
        });
      }}
    >
      <h3 className="font-semibold">{t('sessions.createTitle')}</h3>
      {error && <FormAlert>{error}</FormAlert>}
      {notice && <FormNotice>{notice}</FormNotice>}
      <Select
        label={t('sessions.mode')}
        options={[
          { value: 'OFFLINE', label: t('mode.OFFLINE') },
          { value: 'ONLINE', label: t('mode.ONLINE') },
        ]}
        value={mode}
        onValueChange={(v) => setMode(v as 'OFFLINE' | 'ONLINE')}
      />
      <Input label={t('sessions.startsAt')} type="datetime-local" value={startsAt} onChange={(e) => setStartsAt(e.target.value)} />
      <Input label={t('sessions.endsAt')} type="datetime-local" value={endsAt} onChange={(e) => setEndsAt(e.target.value)} />
      {mode === 'OFFLINE' ? (
        <Input label={t('sessions.room')} value={room} maxLength={100} onChange={(e) => setRoom(e.target.value)} />
      ) : (
        <Input label={t('sessions.meetingUrl')} type="url" inputMode="url" value={url} maxLength={500} onChange={(e) => setUrl(e.target.value)} />
      )}
      <div>
        <Button type="submit" loading={create.isPending}>
          {t('sessions.add')}
        </Button>
      </div>
    </form>
  );
}

function RateReport({ orgId, classId }: { orgId: string; classId: string }) {
  const { t, i18n } = useTranslation('classroom');
  const report = useAttendanceReport(orgId, classId);
  const pct = new Intl.NumberFormat(i18n.language, { style: 'percent', maximumFractionDigits: 0 });
  return (
    <div className="flex flex-col gap-2">
      <h3 className="font-semibold">{t('rate.title')}</h3>
      <p className="max-w-prose text-sm text-muted">{t('rate.description')}</p>
      {report.isPending && <p role="status" className="text-muted">{t('sessions.loading')}</p>}
      {report.isError && <FormAlert>{t('sessions.loadFailed')}</FormAlert>}
      {report.data && report.data.rows.length > 0 && (
        <Table caption={t('rate.caption')} dense>
          <TableHead>
            <TableRow>
              <TableHeaderCell>{t('rate.member')}</TableHeaderCell>
              <TableHeaderCell>{t('rate.present')}</TableHeaderCell>
              <TableHeaderCell>{t('rate.late')}</TableHeaderCell>
              <TableHeaderCell>{t('rate.absent')}</TableHeaderCell>
              <TableHeaderCell>{t('rate.excused')}</TableHeaderCell>
              <TableHeaderCell>{t('rate.rate')}</TableHeaderCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {report.data.rows.map((r) => (
              <TableRow key={r.userId} data-testid="rate-row">
                <TableCell>{r.displayName ?? r.email ?? r.userId}</TableCell>
                <TableCell>{r.present}</TableCell>
                <TableCell>{r.late}</TableCell>
                <TableCell>{r.absent}</TableCell>
                <TableCell>{r.excused}</TableCell>
                <TableCell data-testid="rate-value">{r.rate == null ? t('rate.none') : pct.format(r.rate)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  );
}
