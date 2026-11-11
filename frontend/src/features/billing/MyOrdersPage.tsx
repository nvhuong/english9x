import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Badge, Button, EmptyState, type BadgeTone } from '../../design';
import { FormAlert } from '../auth/components';
import { useMyEntitlements, useMyOrders } from './queries';

const TONE: Record<string, BadgeTone> = { PENDING_PAYMENT: 'warning', CONFIRMED: 'success', REJECTED: 'danger', EXPIRED: 'neutral' };
const money = (vnd: number, locale: string) => new Intl.NumberFormat(locale).format(vnd);
const day = (iso: string, locale: string) => new Date(iso).toLocaleDateString(locale);

/** /orders: the person's orders with their status, and the access they hold; a plan that ran out is shown as read only. */
export default function MyOrdersPage() {
  const { t, i18n } = useTranslation('billing');
  const lang = i18n.language;
  const orders = useMyOrders();
  const ents = useMyEntitlements();
  return (
    <div className="flex flex-col gap-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="font-serif text-3xl font-bold text-ink">{t('mine.title')}</h1>
          <p className="mt-2 max-w-prose text-muted">{t('mine.description')}</p>
        </div>
        <Link to="/checkout" className="text-primary underline">
          {t('mine.buy')}
        </Link>
      </header>

      {orders.isPending && <p role="status" className="text-muted">{t('mine.loading')}</p>}
      {orders.isError && (
        <div className="flex flex-col items-start gap-2">
          <FormAlert>{t('mine.loadFailed')}</FormAlert>
          <Button variant="secondary" onClick={() => void orders.refetch()}>
            {t('order.retry')}
          </Button>
        </div>
      )}
      {orders.data && orders.data.length === 0 && <EmptyState icon="🧾" title={t('mine.empty')} description={t('mine.emptyHint')} />}
      {orders.data && orders.data.length > 0 && (
        <ul aria-label={t('mine.listCaption')} className="flex flex-col gap-2">
          {orders.data.map((o) => (
            <li key={o.code} data-testid="my-order" className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line px-3 py-2">
              <div>
                <Link to={`/orders/${o.code}`} className="font-mono font-semibold text-primary underline">
                  {o.code}
                </Link>
                <p className="text-sm text-muted">
                  {t('mine.placed', { date: day(o.createdAt, lang), months: o.planMonths, amount: money(o.amountVnd, lang) })}
                </p>
                {o.courses.length > 0 && <p className="text-sm text-muted">{o.courses.map((c) => c.code).join(', ')}</p>}
              </div>
              <Badge tone={TONE[o.status] ?? 'neutral'}>{t(`order.status.${o.status}`)}</Badge>
            </li>
          ))}
        </ul>
      )}

      {ents.data && ents.data.length > 0 && (
        <section aria-labelledby="ents-heading" className="flex flex-col gap-2">
          <h2 id="ents-heading" className="font-serif text-xl font-semibold">
            {t('mine.access')}
          </h2>
          <ul className="flex flex-col gap-2">
            {ents.data.map((e) => (
              <li key={e.id} data-testid="my-entitlement" className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line px-3 py-2">
                <span>
                  <span className="font-semibold">{e.courseCode ?? '–'}</span>{' '}
                  <span className="text-sm text-muted">{t('mine.until', { date: day(e.validUntil, lang) })}</span>
                </span>
                <Badge tone={e.active ? 'success' : 'neutral'}>{e.active ? t('mine.active') : t('mine.readOnly')}</Badge>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
