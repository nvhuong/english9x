import { defineRoutes } from '../../app/routeTypes';

/** The learner's assignments in the organisation of the current context (F46). Open to every signed-in person; the server answers with their own list only. */
export default defineRoutes([
  {
    path: 'assignments',
    nav: { labelKey: 'nav.assignments', ns: 'classroom', order: 16 },
    lazy: async () => ({ Component: (await import('../../features/classroom/MyAssignmentsPage')).default }),
  },
]);
