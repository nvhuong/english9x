import { defineRoutes } from '../../app/routeTypes';

/** One of the person's orders (QR, account, transfer note, status). Open to every signed-in person; the server answers 404 for anybody else's order. */
export default defineRoutes([
  {
    path: 'orders/:code',
    lazy: async () => ({ Component: (await import('../../features/billing/OrderStatusPage')).default }),
  },
]);
