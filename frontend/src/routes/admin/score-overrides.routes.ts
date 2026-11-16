import { defineRoutes } from '../../app/routeTypes';

/** SYS_ADMIN: attach the override of one score to a test of English9x (F13, F15); the author writes its rows on the score-table editor. The server checks again. */
export default defineRoutes([
  {
    path: 'score-overrides',
    role: 'SYS_ADMIN',
    nav: { labelKey: 'nav.scoreOverrides', ns: 'scoring', order: 91 },
    lazy: async () => ({ Component: (await import('../../features/cms/score-tables/OverrideAttachPage')).default }),
  },
]);
