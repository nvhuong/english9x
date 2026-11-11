import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Input, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../design';
import { FormAlert, FormNotice } from '../auth/components';
import { classroomProblemMessage } from './problems';
import { useAttendanceSheet, useMarkAttendance, type AttendanceStatus } from './queries';

const STATUSES: AttendanceStatus[] = ['PRESENT', 'LATE', 'ABSENT', 'EXCUSED'];

type Draft = Record<string, { status: AttendanceStatus | null; note: string }>;

/** The attendance sheet of one session: every member of the class with the four states and a note; saving sends the marked rows (F48). */
export function AttendanceSheet({ orgId, classId, sessionId }: { orgId: string; classId: string; sessionId: string }) {
  const { t } = useTranslation('classroom');
  const sheet = useAttendanceSheet(orgId, classId, sessionId, true);
  if (sheet.isPending) return <p role="status" className="text-muted">{t('sessions.loading')}</p>;
  if (sheet.isError) return <FormAlert>{t('sessions.loadFailed')}</FormAlert>;
  if (sheet.data.rows.length === 0) return <p className="text-muted">{t('sheet.empty')}</p>;
  return <Form orgId={orgId} classId={classId} sessionId={sessionId} rows={sheet.data.rows} />;
}

function Form({ orgId, classId, sessionId, rows }: { orgId: string; classId: string; sessionId: string; rows: NonNullable<ReturnType<typeof useAttendanceSheet>['data']>['rows'] }) {
  const { t } = useTranslation('classroom');
  const mark = useMarkAttendance(orgId, classId, sessionId);
  const [draft, setDraft] = useState<Draft>(() => Object.fromEntries(rows.map((r) => [r.userId, { status: r.status ?? null, note: r.note ?? '' }])));
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const set = (userId: string, patch: Partial<Draft[string]>) => setDraft((d) => ({ ...d, [userId]: { ...d[userId]!, ...patch } }));
  return (
    <form
      aria-label={t('sheet.caption')}
      className="flex flex-col gap-3"
      onSubmit={(e) => {
        e.preventDefault();
        setError(null);
        setNotice(null);
        const marks = Object.entries(draft)
          .filter(([, v]) => v.status)
          .map(([userId, v]) => ({ userId, status: v.status!, note: v.note.trim() || undefined }));
        if (marks.length === 0) {
          setError(t('sheet.none'));
          return;
        }
        mark.mutate(marks, { onSuccess: () => setNotice(t('sheet.saved')), onError: (err) => setError(classroomProblemMessage(err, t)) });
      }}
    >
      {error && <FormAlert>{error}</FormAlert>}
      {notice && <FormNotice>{notice}</FormNotice>}
      <Table caption={t('sheet.caption')} dense>
        <TableHead>
          <TableRow>
            <TableHeaderCell>{t('sheet.member')}</TableHeaderCell>
            <TableHeaderCell>{t('sheet.status')}</TableHeaderCell>
            <TableHeaderCell>{t('sheet.note')}</TableHeaderCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {rows.map((r) => {
            const name = r.displayName ?? r.email ?? r.userId;
            const d = draft[r.userId]!;
            return (
              <TableRow key={r.userId} data-testid="attendance-row">
                <TableCell>{name}</TableCell>
                <TableCell>
                  <fieldset className="flex flex-wrap gap-3">
                    <legend className="sr-only">{t('sheet.statusOf', { name })}</legend>
                    {STATUSES.map((s) => (
                      <label key={s} className="flex items-center gap-1">
                        <input type="radio" name={`attendance-${sessionId}-${r.userId}`} checked={d.status === s} onChange={() => set(r.userId, { status: s })} />
                        <span>{t(`attendanceStatus.${s}`)}</span>
                      </label>
                    ))}
                    {!d.status && <span className="text-sm text-muted">{t('sheet.notMarked')}</span>}
                  </fieldset>
                </TableCell>
                <TableCell>
                  <Input label={<span className="sr-only">{t('sheet.noteOf', { name })}</span>} value={d.note} maxLength={500} onChange={(e) => set(r.userId, { note: e.target.value })} />
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
      <div>
        <Button type="submit" loading={mark.isPending}>
          {t('sheet.save')}
        </Button>
      </div>
    </form>
  );
}
