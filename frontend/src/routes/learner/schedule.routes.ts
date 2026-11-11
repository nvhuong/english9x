import { defineRoutes } from '../../app/routeTypes';

/** The person's own sessions (as a learner or as a teacher) in the organisation of the current context (F47). Open to every signed-in person; the server answers with their own list only. */
export default defineRoutes([
  {
    path: 'schedule',
    nav: { labelKey: 'nav.schedule', ns: 'classroom', order: 17 },
    lazy: async () => ({ Component: (await import('../../features/classroom/SchedulePage')).default }),
  },
]);
