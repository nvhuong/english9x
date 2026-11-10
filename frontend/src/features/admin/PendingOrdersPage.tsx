import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiProblemError } from '../../api/problem';
import { ForbiddenPage } from '../../app/pages/ForbiddenPage';
import { Badge, Button, Dialog, Input, Select, Table, TableBody, TableCell, TableHead, TableHeaderCell, TableRow, EmptyState } from '../../design';
import { FormAlert } from '../auth/components';
import { useAdminOrders, useDecideOrder, type AdminOrderView } from '../billing/queries';

const STATUSES = ['PENDING_PAYMENT', 'CONFIRMED', 'REJECTED', 'EXPIRED'] as const;
const money = (vnd: number) => new Intl.NumberFormat('vi-VN').format(vnd);

/** /admin/orders: the queue of orders for the SYS_ADMIN, who confirms (payment arrived) or rejects (with a reason) each pending one. */
export default function PendingOrdersPage() {
  const { t } = useTranslation('billing');
  const [status, setStatus] = useState<string>('PENDING_PAYMENT');
  const [q, setQ] = useState('');
  const [applied, setApplied] = useState('');
  const orders = useAdminOrders(status, applied);
  const { confirm, reject } = useDecideOrder();
  const [confirming, setConfirming] = useState<AdminOrderView | null>(null);
  const [rejecting, setRejecting] = useState<AdminOrderView | null>(null);
  const [bankRef, setBankRef] = useState('');
  const [reason, setReason] = useState('');
  const [decisionError, setDecisionError] = useState<string | null>(null);

  if (orders.isError && orders.error instanceof ApiProblemError && orders.error.status === 403) return <ForbiddenPage />;

  const fail = (err: unknown) => {
    setDecisionError(err instanceof ApiProblemError && err.status === 409 ? t('admin.orders.alreadyDecided') : err instanceof ApiProblemError && err.status === 422 ? t('admin.orders.reasonRequired') : t('admin.orders.decisionFailed'));
  };

  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('admin.orders.title')}</h1>
        <p className="mt-2 max-w-prose text-muted">{t('admin.orders.description')}</p>
      </header>

      <form
        aria-label={t('admin.orders.filters')}
        className="flex flex-wrap items-end gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          setApplied(q);
        }}
      >
        <Select
          label={t('admin.orders.status')}
          value={status}
          onValueChange={setStatus}
          options={STATUSES.map((s) => ({ value: s, label: t(`order.status.${s}`) }))}
        />
        <Input label={t('admin.orders.search')} hint={t('admin.orders.searchHint')} value={q} onChange={(e) => setQ(e.target.value)} />
        <Button type="submit" variant="secondary">
          {t('admin.orders.apply')}
        </Button>
      </form>

      {decisionError && <FormAlert>{decisionError}</FormAlert>}
      {orders.isPending && <p role="status" className="text-muted">{t('admin.orders.loading')}</p>}
      {orders.isError && (
        <div className="flex flex-col items-start gap-2">
          <FormAlert>{t('admin.orders.loadFailed')}</FormAlert>
          <Button variant="secondary" onClick={() => void orders.refetch()}>
            {t('order.retry')}
          </Button>
        </div>
      )}
      {orders.data && orders.data.length === 0 && <EmptyState icon="🧾" title={t('admin.orders.empty')} description={t('admin.orders.emptyHint')} />}
      {orders.data && orders.data.length > 0 && (
        <Table caption={t('admin.orders.tableCaption')} dense>
          <TableHead>
            <TableRow>
              <TableHeaderCell>{t('admin.orders.col.code')}</TableHeaderCell>
              <TableHeaderCell>{t('admin.orders.col.payer')}</TableHeaderCell>
              <TableHeaderCell>{t('admin.orders.col.amount')}</TableHeaderCell>
              <TableHeaderCell>{t('admin.orders.col.note')}</TableHeaderCell>
              <TableHeaderCell>{t('admin.orders.col.status')}</TableHeaderCell>
              <TableHeaderCell>{t('admin.orders.col.actions')}</TableHeaderCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {orders.data.map((o) => (
              <TableRow key={o.code} data-testid="order-row">
                <TableCell className="font-mono">{o.code}</TableCell>
                <TableCell>
                  {o.userName ?? '–'}
                  <br />
                  <span className="text-muted">{o.userEmail ?? ''}</span>
                </TableCell>
                <TableCell>{money(o.amountVnd)}</TableCell>
                <TableCell className="font-mono">{o.transferNote}</TableCell>
                <TableCell>
                  <Badge tone={o.status === 'CONFIRMED' ? 'success' : o.status === 'PENDING_PAYMENT' ? 'warning' : o.status === 'REJECTED' ? 'danger' : 'neutral'}>{t(`order.status.${o.status}`)}</Badge>
                </TableCell>
                <TableCell>
                  {o.status === 'PENDING_PAYMENT' && (
                    <div className="flex gap-2">
                      <Button size="sm" onClick={() => { setDecisionError(null); setBankRef(''); setConfirming(o); }} aria-label={t('admin.orders.confirmFor', { code: o.code })}>
                        {t('admin.orders.confirm')}
                      </Button>
                      <Button size="sm" variant="danger" onClick={() => { setDecisionError(null); setReason(''); setRejecting(o); }} aria-label={t('admin.orders.rejectFor', { code: o.code })}>
                        {t('admin.orders.reject')}
                      </Button>
                    </div>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      <Dialog
        open={confirming !== null}
        onOpenChange={(open) => !open && setConfirming(null)}
        title={t('admin.orders.confirmTitle', { code: confirming?.code ?? '' })}
        description={confirming ? t('admin.orders.confirmDescription', { amount: money(confirming.amountVnd), note: confirming.transferNote }) : undefined}
        footer={
          <>
            <Button variant="secondary" onClick={() => setConfirming(null)}>
              {t('admin.orders.cancel')}
            </Button>
            <Button
              loading={confirm.isPending}
              onClick={() => confirming && confirm.mutate({ code: confirming.code, bankRef }, { onSuccess: () => setConfirming(null), onError: (e) => { setConfirming(null); fail(e); } })}
            >
              {t('admin.orders.confirmSubmit')}
            </Button>
          </>
        }
      >
        <Input label={t('admin.orders.bankRef')} hint={t('admin.orders.bankRefHint')} maxLength={100} value={bankRef} onChange={(e) => setBankRef(e.target.value)} />
      </Dialog>

      <Dialog
        open={rejecting !== null}
        onOpenChange={(open) => !open && setRejecting(null)}
        title={t('admin.orders.rejectTitle', { code: rejecting?.code ?? '' })}
        description={t('admin.orders.rejectDescription')}
        footer={
          <>
            <Button variant="secondary" onClick={() => setRejecting(null)}>
              {t('admin.orders.cancel')}
            </Button>
            <Button
              variant="danger"
              loading={reject.isPending}
              disabled={reason.trim() === ''}
              onClick={() => rejecting && reject.mutate({ code: rejecting.code, reason: reason.trim() }, { onSuccess: () => setRejecting(null), onError: (e) => { setRejecting(null); fail(e); } })}
            >
              {t('admin.orders.rejectSubmit')}
            </Button>
          </>
        }
      >
        <Input label={t('admin.orders.reason')} maxLength={500} required value={reason} onChange={(e) => setReason(e.target.value)} />
      </Dialog>
    </div>
  );
}
