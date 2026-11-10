import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiProblemError } from '../../api/problem';
import { ForbiddenPage } from '../../app/pages/ForbiddenPage';
import { Badge, Button, EmptyState, Input, Select, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow } from '../../design';
import { FormAlert, FormNotice } from '../auth/components';
import { useAllOrganizations } from '../org/queries';
import { useAdminLicenses, useCreateLicense, useLicensableCourses } from '../org/licenses/queries';

const money = (vnd: number) => new Intl.NumberFormat('vi-VN').format(vnd);

/** /admin/org-licenses: the SYS_ADMIN sells seats to an organisation (F42). CONTRACT is active at once; QR files an order the organisation's administrator pays. */
export default function OrgLicensesPage() {
  const { t } = useTranslation('billing');
  const orgs = useAllOrganizations();
  const courses = useLicensableCourses();
  const list = useAdminLicenses();
  const create = useCreateLicense();
  const [orgId, setOrgId] = useState('');
  const [seats, setSeats] = useState('');
  const [months, setMonths] = useState('');
  const [price, setPrice] = useState('');
  const [activation, setActivation] = useState<'QR' | 'CONTRACT'>('CONTRACT');
  const [contractNo, setContractNo] = useState('');
  const [picked, setPicked] = useState<string[]>([]);
  const [touched, setTouched] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (list.isError && list.error instanceof ApiProblemError && list.error.status === 403) return <ForbiddenPage />;

  const orgName = (id: string) => orgs.data?.find((o) => o.id === id)?.name ?? id;
  const n = (s: string) => (/^[0-9]+$/.test(s.trim()) ? Number(s.trim()) : NaN);
  const valid =
    orgId !== '' && n(seats) >= 1 && n(seats) <= 100000 && n(months) >= 1 && n(months) <= 60 && n(price) >= 1 && picked.length > 0 && (activation === 'QR' || contractNo.trim() !== '');
  const toggle = (id: string) => setPicked((cur) => (cur.includes(id) ? cur.filter((c) => c !== id) : [...cur, id]));

  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('licenses.admin.title')}</h1>
        <p className="mt-2 max-w-prose text-muted">{t('licenses.admin.description')}</p>
      </header>

      <form
        noValidate
        aria-label={t('licenses.admin.formTitle')}
        className="flex max-w-xl flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          setTouched(true);
          setNotice(null);
          setError(null);
          if (!valid) {
            setError(t('licenses.admin.invalid'));
            return;
          }
          create.mutate(
            {
              orgId,
              seats: n(seats),
              months: n(months),
              priceVnd: n(price),
              courseIds: picked,
              activation,
              ...(activation === 'CONTRACT' ? { contractNo: contractNo.trim() } : {}),
            },
            {
              onSuccess: (r) => {
                setNotice(r.license.activation === 'QR' ? t('licenses.admin.createdQr', { code: r.license.orderCode ?? '', note: r.transferNote ?? '' }) : t('licenses.admin.createdContract'));
                setTouched(false);
                setSeats('');
                setMonths('');
                setPrice('');
                setContractNo('');
                setPicked([]);
              },
              onError: (err) => setError(err instanceof ApiProblemError && err.type === 'urn:e9x:problem:org-has-no-admin' ? t('licenses.admin.noAdmin') : t('licenses.admin.failed')),
            },
          );
        }}
      >
        <h2 className="font-serif text-xl font-semibold">{t('licenses.admin.formTitle')}</h2>
        <Select label={t('licenses.admin.org')} value={orgId} onValueChange={setOrgId} options={(orgs.data ?? []).map((o) => ({ value: o.id, label: o.name }))} error={touched && !orgId ? t('licenses.admin.invalid') : undefined} />
        <Input label={t('licenses.admin.seats')} inputMode="numeric" value={seats} onChange={(e) => setSeats(e.target.value)} />
        <Input label={t('licenses.admin.months')} inputMode="numeric" value={months} onChange={(e) => setMonths(e.target.value)} />
        <Input label={t('licenses.admin.price')} inputMode="numeric" value={price} onChange={(e) => setPrice(e.target.value)} />
        <Select
          label={t('licenses.admin.activation')}
          value={activation}
          onValueChange={(v) => setActivation(v === 'QR' ? 'QR' : 'CONTRACT')}
          options={[
            { value: 'CONTRACT', label: t('licenses.admin.CONTRACT') },
            { value: 'QR', label: t('licenses.admin.QR') },
          ]}
        />
        {activation === 'CONTRACT' && <Input label={t('licenses.admin.contractNo')} maxLength={100} value={contractNo} onChange={(e) => setContractNo(e.target.value)} />}
        <fieldset className="flex flex-col gap-1">
          <legend className="text-sm font-semibold text-ink">{t('licenses.admin.courses')}</legend>
          {(courses.data ?? []).map((c) => (
            <label key={c.id} className="flex items-center gap-2">
              <input type="checkbox" checked={picked.includes(c.id)} onChange={() => toggle(c.id)} />
              <span>{`${c.code} ${c.title ?? ''}`.trim()}</span>
            </label>
          ))}
        </fieldset>
        {error && <FormAlert>{error}</FormAlert>}
        {notice && <FormNotice>{notice}</FormNotice>}
        <div>
          <Button type="submit" loading={create.isPending}>
            {t('licenses.admin.submit')}
          </Button>
        </div>
      </form>

      <h2 className="font-serif text-xl font-semibold">{t('licenses.admin.listTitle')}</h2>
      {list.isPending && <p role="status" className="text-muted">{t('licenses.admin.loading')}</p>}
      {list.isError && <FormAlert>{t('licenses.admin.loadFailed')}</FormAlert>}
      {list.data && list.data.length === 0 && <EmptyState icon="🪑" title={t('licenses.admin.empty')} />}
      {list.data && list.data.length > 0 && (
        <Table caption={t('licenses.admin.listCaption')} dense>
          <TableHead>
            <TableRow>
              <TableHeaderCell>{t('licenses.admin.col.org')}</TableHeaderCell>
              <TableHeaderCell>{t('licenses.admin.col.seats')}</TableHeaderCell>
              <TableHeaderCell>{t('licenses.admin.col.price')}</TableHeaderCell>
              <TableHeaderCell>{t('licenses.admin.col.status')}</TableHeaderCell>
              <TableHeaderCell>{t('licenses.admin.col.order')}</TableHeaderCell>
              <TableHeaderCell>{t('licenses.admin.col.contract')}</TableHeaderCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {list.data.map((l) => (
              <TableRow key={l.id} data-testid="license-row">
                <TableCell>{orgName(l.orgId)}</TableCell>
                <TableCell>{`${l.seatsUsed}/${l.seats}`}</TableCell>
                <TableCell>{money(l.priceVnd)}</TableCell>
                <TableCell>
                  <Badge tone={l.status === 'ACTIVE' ? 'success' : l.status === 'PENDING_PAYMENT' ? 'warning' : 'neutral'}>{t(`licenses.status.${l.status}`)}</Badge>
                </TableCell>
                <TableCell className="font-mono">{l.orderCode ?? '–'}</TableCell>
                <TableCell>{l.contractNo ?? '–'}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  );
}
