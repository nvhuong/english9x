import { create } from 'zustand';

/**
 * Access token holder. P1.4 (identity) fills it after login/refresh; P1.10 only reads it (useApi attaches it).
 * Kept in memory on purpose: it is not persisted here.
 */
interface AuthState {
  accessToken: string | null;
  setAccessToken: (token: string | null) => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  accessToken: null,
  setAccessToken: (accessToken) => set({ accessToken }),
}));
