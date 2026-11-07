import { defineRoutes } from '../../app/routeTypes';

/** An organisation's admin: members, roles, invitations. Needs ORG_ADMIN in the current context. */
export default defineRoutes([
  {
    path: 'members',
    role: 'ORG_ADMIN',
    nav: { labelKey: 'nav.members', ns: 'org', order: 10 },
    lazy: async () => ({ Component: (await import('../../features/org/pages/OrgMembersPage')).default }),
  },
  {
    path: ':orgId/members',
    role: 'ORG_ADMIN',
    lazy: async () => ({ Component: (await import('../../features/org/pages/OrgMembersPage')).default }),
  },
]);
