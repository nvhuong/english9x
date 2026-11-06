import { create } from 'zustand';

/** The signed-in person, as returned by GET /api/auth/me. Grants / roles live in the role store (P1.5), not here. */
export interface AuthUser {
  id: string;
  email: string;
  displayName: string | null;
  emailVerified: boolean;
  locale: string;
}

/**
 * - `loading`: a stored session is being restored (refresh in flight); guards and menus should wait.
 * - `anonymous`: nobody is signed in.
 * - `authenticated`: `accessToken` and `user` are set.
 */
export type AuthStatus = 'loading' | 'anonymous' | 'authenticated';

/**
 * Auth state. The ACCESS token lives only here, in memory (never persisted, never in a cookie or URL): a page reload
 * drops it and the session is restored with the refresh token (see features/auth/session.ts). `useApi` reads
 * `accessToken` at request time.
 */
interface AuthState {
  accessToken: string | null;
  setAccessToken: (token: string | null) => void;
  user: AuthUser | null;
  setUser: (user: AuthUser | null) => void;
  status: AuthStatus;
  setStatus: (status: AuthStatus) => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  accessToken: null,
  setAccessToken: (accessToken) => set({ accessToken }),
  user: null,
  setUser: (user) => set({ user }),
  status: 'anonymous',
  setStatus: (status) => set({ status }),
}));
