import { AREAS, type Area } from './areas';
import type { AreaRouteDef, NavMeta } from './routeTypes';

export type RouteModules = Record<string, { default: AreaRouteDef[] }>;
export interface NavItem extends NavMeta {
  to: string;
}

/**
 * Every `src/routes/<area>/*.routes.ts` is loaded automatically (no shared file to edit).
 * `eager: true` only imports the route TABLES; the pages themselves are `lazy` and become separate chunks.
 */
export const discoveredRouteModules = import.meta.glob<{ default: AreaRouteDef[] }>('../routes/*/*.routes.ts', { eager: true }) as RouteModules;

const PATH = /(?:^|\/)routes\/([^/]+)\/[^/]+\.routes\.ts$/;

/** Group route tables by area; a folder that is not a known area is a mistake and fails loudly. */
export function groupByArea(modules: RouteModules): Record<Area, AreaRouteDef[]> {
  const out: Record<Area, AreaRouteDef[]> = { learner: [], cms: [], org: [], admin: [], exam: [] };
  for (const file of Object.keys(modules).sort()) {
    const area = PATH.exec(file)?.[1];
    if (!area || !(AREAS as readonly string[]).includes(area)) {
      throw new Error(`Route file ${file}: folder must be one of ${AREAS.join(', ')} (src/routes/<area>/<name>.routes.ts)`);
    }
    const routes = modules[file]!.default;
    if (!Array.isArray(routes)) throw new Error(`Route file ${file}: default export must be defineRoutes([...])`);
    out[area as Area].push(...routes);
  }
  return out;
}

export function navOf(base: string, routes: AreaRouteDef[]): NavItem[] {
  return routes
    .filter((r) => r.nav)
    .map((r) => ({ ...r.nav!, to: `${base.replace(/\/$/, '')}/${r.path ?? ''}`.replace(/\/$/, '') || '/' }))
    .sort((a, b) => (a.order ?? 100) - (b.order ?? 100));
}
