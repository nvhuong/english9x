import { defineRoutes } from '../../app/routeTypes';

/** The learner's results (F38): the result of one of my attempts, and my weak question types with the method lessons to open. Open to every signed-in person; the server answers with their own data only. */
export default defineRoutes([
  {
    path: 'results/weakness',
    nav: { labelKey: 'nav.weakness', ns: 'results', order: 17 },
    lazy: async () => ({ Component: (await import('../../features/results/WeaknessPage')).default }),
  },
  {
    path: 'results/attempts/:attemptId',
    lazy: async () => ({ Component: (await import('../../features/results/ResultPage')).default }),
  },
]);
