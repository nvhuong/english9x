import type { ComponentType, ReactNode } from 'react';
import type { RouteObject } from 'react-router-dom';
import type { Role } from './stores/roles';

export interface NavMeta {
  /** i18n key of the menu label (in `ns`, default `common`). */
  labelKey: string;
  ns?: string;
  /** Ascending sort in the menu; default 100. */
  order?: number;
}

/**
 * One route of an area. Relative `path`s are mounted under the area prefix (`/cms`, `/org`, ...; learner under `/`).
 * Prefer `lazy` so each feature is its own chunk.
 */
export interface AreaRouteDef {
  path?: string;
  index?: boolean;
  element?: ReactNode;
  Component?: ComponentType;
  lazy?: RouteObject['lazy'];
  /** Extra guard for this route on top of the area's. Any of the roles (and `exam`, if given) lets the user in. */
  role?: Role | readonly Role[];
  exam?: string;
  /** Show in the area menu. */
  nav?: NavMeta;
  children?: AreaRouteDef[];
}

/** Identity helper: gives route files type-checking and autocompletion. */
export function defineRoutes(routes: AreaRouteDef[]): AreaRouteDef[] {
  return routes;
}
