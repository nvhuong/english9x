import type { QueryClient } from '@tanstack/react-query';
import { useAuthStore, type AuthUser } from '../../app/stores/auth';
import { useRoleStore } from '../../app/stores/roles';

/**
 * Where the tokens live (decision recorded in the P1.4 report):
 *  - ACCESS token (15 min): memory only (the auth store). Lost on reload, restored by a refresh.
 *  - REFRESH token (30 days, rotating): localStorage `e9x.auth.refresh`. Chosen over an HttpOnly cookie because the
 *    API is bearer-only (no cookie authentication, so no CSRF surface) and the token must survive reloads and be shared
 *    by tabs. Trade-off: script running on the page (XSS) can read it. Mitigations: CSP `script-src 'self'`, no inline
 *    script, rotation on every use, reuse detection that revokes the family, and tokens never in URLs or logs.
 */
export const REFRESH_STORAGE_KEY = 'e9x.auth.refresh';

export const refreshStorage = {
  get(): string | null {
    try {
      return localStorage.getItem(REFRESH_STORAGE_KEY);
    } catch {
      return null;
    }
  },
  set(token: string): void {
    try {
      localStorage.setItem(REFRESH_STORAGE_KEY, token);
    } catch {
      /* storage unavailable: the session lasts until reload */
    }
  },
  clear(): void {
    try {
      localStorage.removeItem(REFRESH_STORAGE_KEY);
    } catch {
      /* ignore */
    }
  },
};

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
}

export function applyTokens(tokens: TokenPair): void {
  refreshStorage.set(tokens.refreshToken);
  useAuthStore.getState().setAccessToken(tokens.accessToken);
}

/** Forget the session locally (no server call): tokens, user, status. */
export function endSession(): void {
  refreshStorage.clear();
  const s = useAuthStore.getState();
  s.setAccessToken(null);
  s.setUser(null);
  s.setStatus('anonymous');
  useRoleStore.getState().setGrants(null);
}

async function fetchMe(accessToken: string): Promise<AuthUser | null> {
  try {
    const res = await fetch('/api/auth/me', { headers: { Authorization: `Bearer ${accessToken}` } });
    if (!res.ok) return null;
    return (await res.json()) as AuthUser;
  } catch {
    return null;
  }
}

/** Store the tokens, load the profile and mark the person signed in. Returns the profile, or null when it could not be loaded. */
export async function startSession(tokens: TokenPair): Promise<AuthUser | null> {
  applyTokens(tokens);
  const user = await fetchMe(tokens.accessToken);
  const s = useAuthStore.getState();
  if (user) {
    s.setUser(user);
    s.setStatus('authenticated');
  } else {
    endSession();
  }
  return user;
}

/** Re-read the profile (for example after the mailbox was verified in this browser). */
export async function reloadUser(): Promise<void> {
  const token = useAuthStore.getState().accessToken;
  if (!token) return;
  const user = await fetchMe(token);
  if (user) useAuthStore.getState().setUser(user);
}

// ---- refresh --------------------------------------------------------------------------------------------------------------

let inflight: Promise<string | null> | null = null;

/** Runs `fn` while holding a lock shared by every tab of this browser (Web Locks API), or directly where it does not exist. */
async function withCrossTabLock<T>(fn: () => Promise<T>): Promise<T> {
  const locks = typeof navigator === 'undefined' ? undefined : (navigator as Navigator & { locks?: LockManager }).locks;
  if (locks?.request) return locks.request('e9x-auth-refresh', fn) as Promise<T>;
  return fn();
}

async function rotate(attempt: number): Promise<string | null> {
  const sent = refreshStorage.get();
  if (!sent) return null;
  let res: Response;
  try {
    res = await fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ refreshToken: sent }),
    });
  } catch {
    return null; // offline or server down: keep the session, the caller just does not get a new token
  }
  if (res.ok) {
    const tokens = (await res.json()) as TokenPair;
    applyTokens(tokens);
    return tokens.accessToken;
  }
  if (res.status === 401 || res.status === 400) {
    // Another tab may have rotated the token between our read and the server's answer. Only a token that is still the
    // one we sent is dead; otherwise try once more with what that tab stored.
    const now = refreshStorage.get();
    if (now && now !== sent && attempt < 2) return rotate(attempt + 1);
    endSession();
  }
  return null;
}

/**
 * Get a new access token with the refresh token. SINGLE-FLIGHT: while one refresh is running every caller gets the same
 * promise (a burst of 401s causes one rotation), and across tabs a Web Lock serialises the rotation so two tabs do
 * not spend the same token (if they ever do, the server treats a use within 10 s of the rotation as a harmless race).
 * Resolves to the new access token, or null (no session / refresh token dead / offline). A dead token ends the session.
 */
export function refreshSession(): Promise<string | null> {
  inflight ??= withCrossTabLock(() => rotate(0)).finally(() => {
    inflight = null;
  });
  return inflight;
}

/** On application start: restore the session from the stored refresh token. */
export async function bootstrapSession(): Promise<void> {
  const s = useAuthStore.getState();
  if (!refreshStorage.get()) {
    s.setStatus('anonymous');
    return;
  }
  s.setStatus('loading');
  const token = await refreshSession();
  if (!token) {
    // dead token: endSession() already ran. Offline: stay anonymous but keep the stored token for the next load.
    useAuthStore.getState().setStatus('anonymous');
    return;
  }
  const user = await fetchMe(token);
  if (user) {
    useAuthStore.getState().setUser(user);
    useAuthStore.getState().setStatus('authenticated');
  } else {
    useAuthStore.getState().setStatus('anonymous');
  }
}

/** Sign out: forget the session here, tell the server to revoke the token family. */
export async function logout(): Promise<void> {
  const token = refreshStorage.get();
  endSession();
  if (!token) return;
  try {
    await fetch('/api/auth/logout', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ refreshToken: token }),
      keepalive: true,
    });
  } catch {
    /* the server will let the token expire; locally the person is signed out */
  }
}

/** Signing out in one tab signs out the others (they see the stored refresh token disappear). Returns the unsubscribe. */
export function syncLogoutAcrossTabs(): () => void {
  if (typeof window === 'undefined') return () => {};
  const onStorage = (e: StorageEvent) => {
    if (e.key === REFRESH_STORAGE_KEY && e.newValue === null && useAuthStore.getState().status !== 'anonymous') endSession();
  };
  window.addEventListener('storage', onStorage);
  return () => window.removeEventListener('storage', onStorage);
}

/**
 * Everything cached belongs to one person: when the signed-in user changes (sign-in, sign-out, a different account),
 * the whole query cache is dropped so nobody sees the previous person's data. Returns the unsubscribe.
 */
export function clearCacheOnUserChange(queryClient: QueryClient): () => void {
  return useAuthStore.subscribe((state, prev) => {
    if (state.user?.id !== prev.user?.id) void queryClient.clear();
  });
}
