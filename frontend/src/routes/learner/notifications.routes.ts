import { defineRoutes } from '../../app/routeTypes';

/**
 * The notification centre and its preferences (learner area, open to every signed-in person: a notification belongs to the person
 * whatever roles they hold; signed-out visitors are told to sign in).
 */
export default defineRoutes([
  {
    path: 'notifications',
    nav: { labelKey: 'nav.title', ns: 'notifications', order: 800 },
    lazy: async () => ({ Component: (await import('../../features/notifications/NotificationsPage')).default }),
  },
  {
    path: 'notifications/preferences',
    lazy: async () => ({ Component: (await import('../../features/notifications/PreferencesPage')).default }),
  },
]);
