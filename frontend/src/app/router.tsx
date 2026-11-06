import { Outlet, createBrowserRouter, createMemoryRouter, type RouteObject } from 'react-router-dom';
import { AREAS, AREA_PATH, type Area } from './areas';
import { RequireRole } from './guards/RequireRole';
import { AdminLayout, CmsLayout, ExamLayout, LearnerLayout, OrgLayout } from './layouts/layouts';
import { NavContext, type NavByArea } from './nav';
import { AreaHomePage } from './pages/AreaHomePage';
import { LearnerHomePage } from './pages/LearnerHomePage';
import { NotFoundPage } from './pages/NotFoundPage';
import { RouteErrorPage } from './pages/RouteErrorPage';
import { discoveredRouteModules, groupByArea, navOf, type RouteModules } from './routeModules';
import type { AreaRouteDef } from './routeTypes';

const layouts: Record<Area, () => React.JSX.Element> = {
  learner: LearnerLayout,
  cms: CmsLayout,
  org: OrgLayout,
  admin: AdminLayout,
  exam: ExamLayout,
};

function toRouteObject(def: AreaRouteDef): RouteObject {
  const { role, exam, nav: _nav, children, ...rest } = def;
  void _nav;
  const route = { ...rest, children: children?.map(toRouteObject) } as RouteObject;
  if (!children) delete (route as { children?: unknown }).children;
  if (!role) return route;
  // Pathless wrapper so lazy/Component routes can be guarded without being rendered by us.
  return { element: <RequireRole role={role} exam={exam}><Outlet /></RequireRole>, children: [route] };
}

function defaultIndex(area: Area): RouteObject {
  return { index: true, element: area === 'learner' ? <LearnerHomePage /> : <AreaHomePage area={area} /> };
}

/** Pure: route tables in, React Router tree + menu entries out. Used by the app and by tests. */
export function buildRouteTree(modules: RouteModules): { routes: RouteObject[]; nav: NavByArea } {
  const grouped = groupByArea(modules);
  const nav = {} as NavByArea;
  const areaRoutes: RouteObject[] = AREAS.map((area) => {
    const defs = grouped[area];
    nav[area] = navOf(AREA_PATH[area], defs);
    const Layout = layouts[area];
    const children = defs.map(toRouteObject);
    if (!defs.some((d) => d.index)) children.unshift(defaultIndex(area));
    children.push({ path: '*', element: <NotFoundPage /> });
    return { path: AREA_PATH[area], element: <Layout />, children };
  });
  // The learner area is mounted at '/', so its catch-all must come last.
  const learner = areaRoutes[0]!;
  const rest = areaRoutes.slice(1);
  return {
    nav,
    routes: [
      {
        element: <RootFrame nav={nav} />,
        errorElement: <RouteErrorPage />,
        children: [...rest, learner],
      },
    ],
  };
}

function RootFrame({ nav }: { nav: NavByArea }) {
  return (
    <NavContext.Provider value={nav}>
      <Outlet />
    </NavContext.Provider>
  );
}

export function createAppRouter(modules: RouteModules = discoveredRouteModules, initialEntries?: string[]) {
  const { routes } = buildRouteTree(modules);
  return initialEntries ? createMemoryRouter(routes, { initialEntries }) : createBrowserRouter(routes);
}
