import type { Role } from './stores/roles';

/** The four role areas plus the exam room. Folder name under `src/routes/` = area id. */
export const AREAS = ['learner', 'cms', 'org', 'admin', 'exam'] as const;
export type Area = (typeof AREAS)[number];

/** URL prefix each area is mounted under. */
export const AREA_PATH: Record<Area, string> = {
  learner: '/',
  cms: '/cms',
  org: '/org',
  admin: '/admin',
  exam: '/exam',
};

/**
 * Roles allowed to enter an area (UI convenience; the server enforces). `undefined` = open to everyone, signed in or not.
 * Teachers grade from CMS and run classes from Organisation.
 */
export const AREA_ROLES: Record<Area, readonly Role[] | undefined> = {
  learner: undefined,
  exam: undefined,
  cms: ['AUTHOR', 'REVIEWER', 'TEACHER', 'SYS_ADMIN'],
  org: ['ORG_ADMIN', 'TEACHER', 'SYS_ADMIN'],
  admin: ['SYS_ADMIN'],
};
