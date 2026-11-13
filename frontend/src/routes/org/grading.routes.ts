import { defineRoutes } from '../../app/routeTypes';

/** An organisation's admin: who grades the centre's learners, and in which mode (F50). Needs ORG_ADMIN in the current context; the server checks again. */
export default defineRoutes([
  {
    path: 'grading',
    role: 'ORG_ADMIN',
    nav: { labelKey: 'nav', ns: 'gradingsettings', order: 30 },
    lazy: async () => ({ Component: (await import('../../features/org/settings/GradingSettingsPage')).default }),
  },
]);
