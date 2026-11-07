import { defineRoutes } from '../../app/routeTypes';

/** The link in an invitation mail. Public page: the person signs in (with the invited address) from here. */
export default defineRoutes([
  { path: 'accept-invitation', lazy: async () => ({ Component: (await import('../../features/org/pages/AcceptInvitationPage')).default }) },
]);
