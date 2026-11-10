import { defineRoutes } from '../../app/routeTypes';

/** SYS_ADMIN: the queue of orders to confirm or reject, and the account that receives the money (F40). The server checks the role again. */
export default defineRoutes([
  {
    path: 'orders',
    role: 'SYS_ADMIN',
    nav: { labelKey: 'nav.orders', ns: 'billing', order: 60 },
    lazy: async () => ({ Component: (await import('../../features/admin/PendingOrdersPage')).default }),
  },
  {
    path: 'payment-account',
    role: 'SYS_ADMIN',
    nav: { labelKey: 'nav.paymentAccount', ns: 'billing', order: 61 },
    lazy: async () => ({ Component: (await import('../../features/admin/PaymentAccountPage')).default }),
  },
]);
