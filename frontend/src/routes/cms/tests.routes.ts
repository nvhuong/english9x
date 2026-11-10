import { defineRoutes } from '../../app/routeTypes';

/** Authors and reviewers: the tests built from published question groups (F13). The area guard asks for a CMS role; the server checks again. */
export default defineRoutes([
  {
    path: 'tests',
    role: ['AUTHOR', 'REVIEWER'],
    nav: { labelKey: 'nav.tests', ns: 'tests', order: 28 },
    lazy: async () => ({ Component: (await import('../../features/cms/test-builder/TestListPage')).default }),
  },
  {
    path: 'tests/new',
    role: 'AUTHOR',
    lazy: async () => ({ Component: (await import('../../features/cms/test-builder/TestBuilderPage')).default }),
  },
  {
    path: 'tests/:id',
    role: ['AUTHOR', 'REVIEWER'],
    lazy: async () => ({ Component: (await import('../../features/cms/test-builder/TestBuilderPage')).default }),
  },
]);
