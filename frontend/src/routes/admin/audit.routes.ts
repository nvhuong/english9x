import { defineRoutes } from '../../app/routeTypes';

/** SYS_ADMIN: the audit log (F57). The area guard already asks for SYS_ADMIN; the server checks again. There is no organisation-level log. */
export default defineRoutes([
  {
    path: 'audit-log',
    role: 'SYS_ADMIN',
    nav: { labelKey: 'nav.audit', ns: 'audit', order: 90 },
    lazy: async () => ({ Component: (await import('../../features/audit/AuditLogPage')).default }),
  },
]);
