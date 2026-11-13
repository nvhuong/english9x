import { defineRoutes } from '../../app/routeTypes';

/** English9x REVIEWER: the centre courses offered to the shared catalogue (F21). A bare SYS_ADMIN is not a reviewer; the server checks the grant again. */
export default defineRoutes([
  {
    path: 'shared-catalog',
    role: 'REVIEWER',
    nav: { labelKey: 'nav', ns: 'sharedcatalog', order: 40 },
    lazy: async () => ({ Component: (await import('../../features/admin/SharedCatalogQueue')).default }),
  },
]);
