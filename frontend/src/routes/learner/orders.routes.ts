import { defineRoutes } from '../../app/routeTypes';

/** Buying a plan, the person's orders and one order (QR, account, transfer note, status). Open to every signed-in person; the server answers 404 for anybody else's order. */
export default defineRoutes([
  {
    path: 'checkout',
    nav: { labelKey: 'nav.checkout', ns: 'billing', order: 30 },
    lazy: async () => ({ Component: (await import('../../features/billing/CheckoutPage')).default }),
  },
  {
    path: 'orders',
    nav: { labelKey: 'nav.myOrders', ns: 'billing', order: 31 },
    lazy: async () => ({ Component: (await import('../../features/billing/MyOrdersPage')).default }),
  },
  {
    path: 'orders/:code',
    lazy: async () => ({ Component: (await import('../../features/billing/OrderStatusPage')).default }),
  },
]);
