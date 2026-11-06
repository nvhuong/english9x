import { createContext, useContext } from 'react';
import type { Area } from './areas';
import type { NavItem } from './routeModules';

export type NavByArea = Record<Area, NavItem[]>;
export const NavContext = createContext<NavByArea>({ learner: [], cms: [], org: [], admin: [], exam: [] });
/** Menu entries contributed by `*.routes.ts` files for an area. */
export const useAreaNav = (area: Area): NavItem[] => useContext(NavContext)[area];
