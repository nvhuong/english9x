import { defineRoutes } from '../../app/routeTypes';

/** An organisation's admin: the seat licences of the organisation, assigning and taking back seats (F42, F43). Needs ORG_ADMIN in the current context. */
export default defineRoutes([
  {
    path: 'licenses',
    role: 'ORG_ADMIN',
    nav: { labelKey: 'nav.licenses', ns: 'billing', order: 20 },
    lazy: async () => ({ Component: (await import('../../features/org/licenses/LicensesPage')).default }),
  },
]);
