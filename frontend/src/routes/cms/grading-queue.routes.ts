import { defineRoutes } from '../../app/routeTypes';

/** Teachers: the grading queue of their side and the grading screen of a submission they hold (F35, F36). The server decides what each teacher sees. */
export default defineRoutes([
  {
    path: 'grading-queue',
    role: 'TEACHER',
    nav: { labelKey: 'nav', ns: 'teacher', order: 14 },
    lazy: async () => ({ Component: (await import('../../features/teacher/QueuePage')).default }),
  },
  {
    path: 'grading-queue/:id',
    role: 'TEACHER',
    lazy: async () => ({ Component: (await import('../../features/teacher/GradingPage')).default }),
  },
]);
