import { defineRoutes } from '../../app/routeTypes';

/** SYS_ADMIN: organisations (F05) and English9x staff invitations (F04). The area guard already asks for SYS_ADMIN. */
export default defineRoutes([
  {
    path: 'organizations',
    role: 'SYS_ADMIN',
    nav: { labelKey: 'nav.organizations', ns: 'org', order: 10 },
    lazy: async () => ({ Component: (await import('../../features/org/pages/OrganizationsPage')).default }),
  },
  {
    path: 'organizations/:orgId',
    role: 'SYS_ADMIN',
    lazy: async () => ({ Component: (await import('../../features/org/pages/OrganizationDetailPage')).default }),
  },
  {
    path: 'staff',
    role: 'SYS_ADMIN',
    nav: { labelKey: 'nav.staff', ns: 'org', order: 20 },
    lazy: async () => ({ Component: (await import('../../features/org/pages/StaffPage')).default }),
  },
]);
