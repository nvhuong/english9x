import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';
import { ForbiddenPage } from '../../app/pages/ForbiddenPage';
import { NotFoundPage } from '../../app/pages/NotFoundPage';
import { ApiProblemError } from '../../api/problem';
import { useAuthStore } from '../../app/stores/auth';
import { Button } from '../../design';
import { FormAlert } from '../auth/components';
import { OrderStatusView } from './OrderStatusView';
import { useOrder, useQrImage } from './queries';

/** /orders/:code: one of the person's orders, with the QR to pay it while it waits. Anybody else's order is a 404. */
export default function OrderStatusPage() {
  const { t } = useTranslation('billing');
  const { code = '' } = useParams();
  const status = useAuthStore((s) => s.status);
  const order = useOrder(code);
  const qr = useQrImage(order.data?.status === 'PENDING_PAYMENT' ? order.data.qrPngUrl : null);
  if (status === 'loading' || order.isPending) return <p role="status" className="text-muted">{t('order.loading')}</p>;
  if (order.isError && order.error instanceof ApiProblemError) {
    if (order.error.status === 404) return <NotFoundPage />;
    if (order.error.status === 403 || order.error.status === 401) return <ForbiddenPage />;
  }
  if (order.isError) {
    return (
      <div className="flex flex-col items-start gap-2">
        <FormAlert>{t('order.loadFailed')}</FormAlert>
        <Button variant="secondary" onClick={() => void order.refetch()}>
          {t('order.retry')}
        </Button>
      </div>
    );
  }
  return <OrderStatusView order={order.data} qrSrc={qr.src} qrFailed={qr.failed} />;
}
