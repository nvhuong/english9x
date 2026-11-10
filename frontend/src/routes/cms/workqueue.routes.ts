import { defineRoutes } from '../../app/routeTypes';

/** "Hàng việc của tôi" (F06): my drafts, my change requests and what waits for my review. The server scopes every list to the exams the person is granted. */
export default defineRoutes([
  {
    path: 'work-queue',
    role: ['AUTHOR', 'REVIEWER'],
    nav: { labelKey: 'nav', ns: 'workqueue', order: 10 },
    lazy: async () => ({ Component: (await import('../../features/cms/workqueue/WorkQueuePage')).default }),
  },
]);
