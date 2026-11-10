import { defineRoutes } from '../../app/routeTypes';

/** Authors: bulk import of questions from an Excel template (F11). The area guard asks for a CMS role, this one for AUTHOR; the server checks again. */
export default defineRoutes([
  {
    path: 'import',
    role: 'AUTHOR',
    nav: { labelKey: 'nav.import', ns: 'import', order: 22 },
    lazy: async () => ({ Component: (await import('../../features/cms/import/ImportPage')).default }),
  },
]);
