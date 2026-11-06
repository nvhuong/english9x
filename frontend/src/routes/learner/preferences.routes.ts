import { defineRoutes } from '../../app/routeTypes';

/** Real route file for the learner area; also the proof that `src/routes/<area>/*.routes.ts` is loaded without editing the router. */
export default defineRoutes([
  {
    path: 'preferences',
    nav: { labelKey: 'preferences.title', order: 900 },
    lazy: async () => ({ Component: (await import('../../app/pages/PreferencesPage')).default }),
  },
]);
