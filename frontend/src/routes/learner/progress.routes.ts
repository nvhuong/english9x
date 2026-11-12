import { defineRoutes } from '../../app/routeTypes';

/** My progress by plan (F24). Open to every signed-in person; the server answers with their own data only. */
export default defineRoutes([
  {
    path: 'progress',
    nav: { labelKey: 'nav.progress', ns: 'progress', order: 16 },
    lazy: async () => ({ Component: (await import('../../features/progress/ProgressPage')).default }),
  },
]);
