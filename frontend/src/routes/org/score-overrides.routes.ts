import { defineRoutes } from '../../app/routeTypes';

/** ORG_ADMIN: attach the override of one score to a test of the organisation (F13, F15); the author writes its rows on the score-table editor. The server checks again. */
export default defineRoutes([
  {
    path: 'score-overrides',
    role: 'ORG_ADMIN',
    nav: { labelKey: 'nav.scoreOverrides', ns: 'scoring', order: 25 },
    lazy: async () => ({ Component: (await import('../../features/cms/score-tables/OverrideAttachPage')).default }),
  },
]);
