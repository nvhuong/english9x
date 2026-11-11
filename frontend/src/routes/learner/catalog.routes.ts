import { defineRoutes } from '../../app/routeTypes';

/** The public course catalog (F20): open to everybody, signed in or not. Outlines only; the lessons themselves open after an order. */
export default defineRoutes([
  {
    path: 'courses',
    nav: { labelKey: 'nav.catalog', ns: 'catalog', order: 20 },
    lazy: async () => ({ Component: (await import('../../features/catalog/CatalogPage')).default }),
  },
  {
    path: 'courses/:code',
    lazy: async () => ({ Component: (await import('../../features/catalog/CourseOutlinePage')).default }),
  },
]);
