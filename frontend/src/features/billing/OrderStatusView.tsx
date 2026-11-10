import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Badge, Button, type BadgeTone } from '../../design';
import type { OrderView } from './queries';

const TONE: Record<string, BadgeTone> = { PENDING_PAYMENT: 'warning', CONFIRMED: 'success', REJECTED: 'danger', EXPIRED: 'neutral' };

const money = (vnd: number) => new Intl.NumberFormat('vi-VN').format(vnd);

/** A value the payer must type into the bank app exactly, with a button that copies it. */
function CopyField({ label, value, copyLabel }: { label: string; value: string; copyLabel: string }) {
  const { t } = useTranslation('billing');
  const [copied, setCopied] = useState(false);
  return (
    <div className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line bg-surface px-3 py-2">
      <div>
        <p className="text-sm text-muted">{label}</p>
        <p className="font-mono text-lg font-semibold text-ink" data-testid={`copy-value-${copyLabel}`}>
          {value}
        </p>
      </div>
      <Button
        variant="secondary"
        size="sm"
        aria-label={t('order.copy', { what: label })}
        onClick={() => {
          void navigator.clipboard?.writeText(value).then(() => setCopied(true));
        }}
      >
        {copied ? t('order.copied') : t('order.copyButton')}
      </Button>
    </div>
  );
}

/** What the payer sees for one order: the QR, the account, the exact amount and the exact transfer note, or how the order ended. */
export function OrderStatusView({ order, qrSrc, qrFailed }: { order: OrderView; qrSrc: string | null; qrFailed: boolean }) {
  const { t } = useTranslation('billing');
  const pending = order.status === 'PENDING_PAYMENT';
  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('order.title', { code: order.code })}</h1>
        <p className="mt-2 flex flex-wrap items-center gap-2 text-muted">
          <Badge tone={TONE[order.status] ?? 'neutral'} data-testid="order-status">
            {t(`order.status.${order.status}`)}
          </Badge>
          <span>{t('order.amount', { amount: money(order.amountVnd) })}</span>
        </p>
      </header>

      {order.status === 'CONFIRMED' && <p role="status" className="rounded-md border border-success bg-success-bg px-3 py-2">{t('order.confirmed')}</p>}
      {order.status === 'EXPIRED' && <p role="status" className="rounded-md border border-line bg-sunken px-3 py-2">{t('order.expired')}</p>}
      {order.status === 'REJECTED' && (
        <div role="status" className="rounded-md border border-danger bg-danger-bg px-3 py-2">
          <p className="font-semibold">{t('order.rejected')}</p>
          {/* the administrator's text is data: shown as text only */}
          {order.rejectReason && <p data-testid="reject-reason">{t('order.reason', { reason: order.rejectReason })}</p>}
        </div>
      )}

      {pending && !order.payTo && <p role="status" className="rounded-md border border-line bg-sunken px-3 py-2">{t('order.noAccount')}</p>}
      {pending && order.payTo && (
        <section aria-labelledby="pay-heading" className="grid gap-6 md:grid-cols-[auto_1fr]">
          <div className="flex flex-col items-center gap-2">
            <h2 id="pay-heading" className="font-serif text-xl font-semibold">
              {t('order.payTitle')}
            </h2>
            {qrSrc && <img src={qrSrc} width={256} height={256} alt={t('order.qrAlt', { code: order.code })} className="rounded-md border border-line" />}
            {!qrSrc && !qrFailed && <p role="status" className="text-muted">{t('order.qrLoading')}</p>}
            {qrFailed && <p role="alert" className="text-danger">{t('order.qrFailed')}</p>}
            <p className="max-w-xs text-center text-sm text-muted">{t('order.qrHint')}</p>
          </div>
          <div className="flex flex-col gap-3">
            <p className="text-muted">{t('order.manualHint')}</p>
            <CopyField label={t('order.bank')} value={order.payTo.bankBin} copyLabel="bank" />
            <CopyField label={t('order.accountNo')} value={order.payTo.accountNo} copyLabel="account" />
            <p className="px-1 text-sm text-muted">{t('order.accountName', { name: order.payTo.accountName })}</p>
            <CopyField label={t('order.amountLabel')} value={String(order.amountVnd)} copyLabel="amount" />
            <CopyField label={t('order.noteLabel')} value={order.transferNote} copyLabel="note" />
            <p className="px-1 text-sm text-muted">{t('order.noteHint')}</p>
          </div>
        </section>
      )}
    </div>
  );
}
