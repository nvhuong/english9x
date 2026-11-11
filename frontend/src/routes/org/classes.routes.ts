import { defineRoutes } from '../../app/routeTypes';

/** An organisation's classes / groups and their assignments (F45, F46): the ORG_ADMIN and the teachers of the organisation. The server decides who manages which class. */
export default defineRoutes([
  {
    path: 'classes',
    role: ['ORG_ADMIN', 'TEACHER'],
    nav: { labelKey: 'nav.classes', ns: 'classroom', order: 30 },
    lazy: async () => ({ Component: (await import('../../features/classroom/ClassesPage')).default }),
  },
  {
    path: 'classes/:classId',
    role: ['ORG_ADMIN', 'TEACHER'],
    lazy: async () => ({ Component: (await import('../../features/classroom/ClassDetailPage')).default }),
  },
]);
