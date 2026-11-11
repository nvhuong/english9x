import { defineRoutes } from '../../app/routeTypes';

/** Joining an organisation with its code (F44). Public page: the person signs in from here, then types or confirms the code. */
export default defineRoutes([
  { path: 'join', lazy: async () => ({ Component: (await import('../../features/org/members/JoinPage')).default }) },
]);
