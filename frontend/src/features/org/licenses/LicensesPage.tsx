import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiProblemError } from '../../../api/problem';
import { ForbiddenPage } from '../../../app/pages/ForbiddenPage';
import { NotFoundPage } from '../../../app/pages/NotFoundPage';
import { useContextStore } from '../../../app/stores/context';
import { Badge, Button, Dialog, EmptyState, Select, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../../design';
import { FormAlert, FormNotice } from '../../auth/components';
import { useMembers } from '../queries';
import { licenseErrorKey } from './errors';
import { useAssignSeat, useOrgLicenses, useRevokeSeat, type OrgLicenseView, type SeatHolder } from './queries';

const date = (iso: string | null | undefined) => (iso ? new Date(iso).toLocaleDateString('vi-VN') : '–');

/** /org/licenses: the ORG_ADMIN's licences, who holds each seat, assigning a seat and taking one back (F42, F43). The server decides everything again. */
export default function LicensesPage() {
  const orgId = useContextStore((s) => s.orgId);
  if (!orgId) return <NotFoundPage />;
  return <Licenses orgId={orgId} />;
}

function Licenses({ orgId }: { orgId: string }) {
  const { t } = useTranslation('billing');
  const licenses = useOrgLicenses(orgId);
  if (licenses.isError && licenses.error instanceof ApiProblemError && licenses.error.status === 403) return <ForbiddenPage />;
  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('licenses.title')}</h1>
        <p className="mt-2 max-w-prose text-muted">{t('licenses.description')}</p>
      </header>
      {licenses.isPending && <p role="status" className="text-muted">{t('licenses.loading')}</p>}
      {licenses.isError && (
        <div className="flex flex-col items-start gap-2">
          <FormAlert>{t('licenses.loadFailed')}</FormAlert>
          <Button variant="secondary" onClick={() => void licenses.refetch()}>
            {t('licenses.retry')}
          </Button>
        </div>
      )}
      {licenses.data && licenses.data.length === 0 && <EmptyState icon="🪑" title={t('licenses.empty')} description={t('licenses.emptyHint')} />}
      {licenses.data?.map((l) => <LicenseCard key={l.id} orgId={orgId} license={l} />)}
    </div>
  );
}

function LicenseCard({ orgId, license }: { orgId: string; license: OrgLicenseView }) {
  const { t } = useTranslation('billing');
  const [revoking, setRevoking] = useState<SeatHolder | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const revoke = useRevokeSeat(orgId, license.id);
  const nameOf = (h: SeatHolder) => h.displayName ?? h.email ?? h.userId;
  const courseCode = (id: string) => license.courses.find((c) => c.id === id)?.code ?? id;
  return (
    <section aria-label={t('licenses.title')} data-testid="license-card" className="flex flex-col gap-4 rounded-md border border-edge p-4">
      <div className="flex flex-wrap items-center gap-3">
        <Badge tone={license.status === 'ACTIVE' ? 'success' : license.status === 'PENDING_PAYMENT' ? 'warning' : 'neutral'}>{t(`licenses.status.${license.status}`)}</Badge>
        <span data-testid="license-seats" className="font-semibold">{t('licenses.seatsUsed', { used: license.seatsUsed, total: license.seats })}</span>
        {license.validUntil && <span className="text-muted">{t('licenses.validity', { date: date(license.validUntil) })}</span>}
      </div>
      <p className="text-muted">{t('licenses.courses', { list: license.courses.map((c) => `${c.code} ${c.title ?? ''}`.trim()).join(', ') })}</p>
      {license.status === 'PENDING_PAYMENT' && <FormNotice>{t('licenses.pending', { code: license.orderCode ?? '' })}</FormNotice>}
      {notice && <FormNotice>{notice}</FormNotice>}
      {error && <FormAlert>{error}</FormAlert>}

      {license.status === 'ACTIVE' && <AssignForm orgId={orgId} license={license} onDone={(msg) => { setError(null); setNotice(msg); }} onError={(msg) => { setNotice(null); setError(msg); }} />}

      <h2 className="font-serif text-xl font-semibold">{t('licenses.holders')}</h2>
      {license.holders.length === 0 ? (
        <p className="text-muted">{t('licenses.noHolders')}</p>
      ) : (
        <Table caption={t('licenses.holders')} dense>
          <TableHead>
            <TableRow>
              <TableHeaderCell>{t('licenses.col.name')}</TableHeaderCell>
              <TableHeaderCell>{t('licenses.col.email')}</TableHeaderCell>
              <TableHeaderCell>{t('licenses.col.courses')}</TableHeaderCell>
              <TableHeaderCell>{t('licenses.col.assigned')}</TableHeaderCell>
              <TableHeaderCell>{t('licenses.col.actions')}</TableHeaderCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {license.holders.map((h) => (
              <TableRow key={h.userId} data-testid="seat-row">
                <TableCell>{h.displayName ?? '–'}</TableCell>
                <TableCell>{h.email ?? ''}</TableCell>
                <TableCell className="font-mono">{h.courseIds.map(courseCode).join(', ')}</TableCell>
                <TableCell>{date(h.assignedAt)}</TableCell>
                <TableCell>
                  <Button size="sm" variant="danger" aria-label={t('licenses.revoke.label', { name: nameOf(h) })} onClick={() => { setError(null); setNotice(null); setRevoking(h); }}>
                    {t('licenses.revoke.submit')}
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      <Dialog
        open={revoking !== null}
        onOpenChange={(open) => !open && setRevoking(null)}
        title={t('licenses.revoke.confirmTitle')}
        description={revoking ? t('licenses.revoke.confirmDescription', { name: nameOf(revoking) }) : undefined}
        footer={
          <>
            <Button variant="secondary" onClick={() => setRevoking(null)}>
              {t('licenses.revoke.cancel')}
            </Button>
            <Button
              variant="danger"
              loading={revoke.isPending}
              onClick={() =>
                revoking &&
                revoke.mutate(revoking.userId, {
                  onSuccess: () => { setNotice(t('licenses.revoke.done', { name: nameOf(revoking) })); setRevoking(null); },
                  onError: (e) => { setError(t(`licenses.errors.${licenseErrorKey(e)}`)); setRevoking(null); },
                })
              }
            >
              {t('licenses.revoke.submit')}
            </Button>
          </>
        }
      >
        <span />
      </Dialog>
    </section>
  );
}

function AssignForm({ orgId, license, onDone, onError }: { orgId: string; license: OrgLicenseView; onDone: (msg: string) => void; onError: (msg: string) => void }) {
  const { t } = useTranslation('billing');
  const members = useMembers(orgId);
  const assign = useAssignSeat(orgId, license.id);
  const [userId, setUserId] = useState('');
  const [courseIds, setCourseIds] = useState<string[]>([]);
  const [touched, setTouched] = useState(false);
  const holding = new Set(license.holders.map((h) => h.userId));
  const options = (members.data ?? []).filter((m) => !holding.has(m.userId)).map((m) => ({ value: m.userId, label: `${m.displayName ?? m.email} (${m.email})` }));
  const full = license.seatsUsed >= license.seats;
  const toggle = (id: string) => setCourseIds((cur) => (cur.includes(id) ? cur.filter((c) => c !== id) : [...cur, id]));
  return (
    <form
      noValidate
      aria-label={t('licenses.assign.title')}
      className="flex max-w-xl flex-col gap-3"
      onSubmit={(e) => {
        e.preventDefault();
        setTouched(true);
        if (!userId || courseIds.length === 0) return;
        assign.mutate(
          { userId, courseIds },
          {
            onSuccess: (h) => { setUserId(''); setCourseIds([]); setTouched(false); onDone(t('licenses.assign.done', { name: h.displayName ?? h.email ?? h.userId })); },
            onError: (err) => onError(t(`licenses.errors.${licenseErrorKey(err)}`)),
          },
        );
      }}
    >
      <h2 className="font-serif text-xl font-semibold">{t('licenses.assign.title')}</h2>
      {full && <p className="text-muted">{t('licenses.assign.noSeats')}</p>}
      <Select label={t('licenses.assign.member')} value={userId} onValueChange={setUserId} options={options} error={touched && !userId ? t('licenses.assign.noMember') : undefined} disabled={full} />
      <fieldset className="flex flex-col gap-1" disabled={full}>
        <legend className="text-sm font-semibold text-ink">{t('licenses.assign.courses')}</legend>
        {license.courses.map((c) => (
          <label key={c.id} className="flex items-center gap-2">
            <input type="checkbox" checked={courseIds.includes(c.id)} onChange={() => toggle(c.id)} />
            <span>{`${c.code} ${c.title ?? ''}`.trim()}</span>
          </label>
        ))}
        {touched && courseIds.length === 0 && <p role="alert" className="text-sm text-danger">{t('licenses.assign.noCourses')}</p>}
      </fieldset>
      <div>
        <Button type="submit" loading={assign.isPending} disabled={full}>
          {t('licenses.assign.submit')}
        </Button>
      </div>
    </form>
  );
}
