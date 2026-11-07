import type { QueryClient } from '@tanstack/react-query';
import { useAuthStore, type AuthUser } from '../../app/stores/auth';
import { useContextStore } from '../../app/stores/context';
import { useRoleStore } from '../../app/stores/roles';
import { forgetReturn } from './returnTo';

/**
 * Where the tokens live (decision recorded in the P1.4 report):
 *  - ACCESS token (15 min): memory only (the auth store). Lost on reload, restored by a refresh.
 *  - REFRESH token (30 days, rotating): localStorage `e9x.auth.refresh`. Chosen over an HttpOnly cookie because the
 *    API is bearer-only (no cookie authentication, so no CSRF surface) and the token must survive reloads and be shared
 *    by tabs. Trade-off: script running on the page (XSS) can read it. Mitigations: CSP `script-src 'self'`, no inline
 *    script, rotation on every use, reuse detection that revokes the family, and tokens never in URLs or logs.
 */
export const REFRESH_STORAGE_KEY = 'e9x.auth.refresh';
/**
 * Changes when somebody SIGNS IN (not when a token rotates). Another tab can then tell "same session, newer token", which
 * needs nothing, from "another sign-in, possibly another person", which it must follow. Not a secret.
 */
export const SESSION_MARKER_KEY = 'e9x.auth.session';

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
      localStorage.removeItem(SESSION_MARKER_KEY);
    } catch {
      /* ignore */
    }
  },
};

function readMarker(): string | null {
  try {
    return localStorage.getItem(SESSION_MARKER_KEY);
  } catch {
    return null;
  }
}

function writeNewMarker(): string {
  const marker = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
  try {
    localStorage.setItem(SESSION_MARKER_KEY, marker);
  } catch {
    /* storage unavailable: other tabs cannot follow either */
  }
  return marker;
}

/**
 * Whose session this tab is working for. Bumped whenever that changes (sign-in, sign-out, following another tab). Anything
 * asynchronous remembers the value it started under and drops its result when the value has moved on: the answer of a
 * refresh that was in flight during a sign-out must not bring the tokens back.
 */
let generation = 0;
/** The sign-in this tab is following. */
let knownMarker: string | null = null;
let inflight: Promise<string | null> | null = null;

function nextGeneration(): void {
  generation += 1;
  inflight = null; // a later caller must not wait for the old session's refresh
}

export interface TokenPair {
  accessToken: string;
  refreshToken: string;
}

export function applyTokens(tokens: TokenPair): void {
  refreshStorage.set(tokens.refreshToken);
  useAuthStore.getState().setAccessToken(tokens.accessToken);
}

/** This tab stops being signed in. Shared storage is left alone: another tab may already have written a new sign-in there. */
function forgetInThisTab(): void {
  nextGeneration();
  knownMarker = null;
  const s = useAuthStore.getState();
  s.setAccessToken(null);
  s.setUser(null);
  s.setStatus('anonymous');
  useRoleStore.getState().setGrants(null);
  useContextStore.setState({ orgId: null }); // the next person on this tab must not start in this one's organisation
}

/** Forget the session locally (no server call): tokens, user, status. */
export function endSession(): void {
  refreshStorage.clear();
  forgetInThisTab();
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

/** Tell the server to revoke the token family (best effort). */
async function revokeOnServer(refreshToken: string): Promise<void> {
  try {
    await fetch('/api/auth/logout', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
      keepalive: true,
    });
  } catch {
    /* the server will let the token expire; locally the person is signed out */
  }
}

/**
 * Store the tokens, load the profile and mark the person signed in. Returns the profile, or null when it could not be
 * loaded or the person signed out meanwhile. A session already in this browser is replaced, and revoked on the server.
 */
export async function startSession(tokens: TokenPair): Promise<AuthUser | null> {
  const previous = refreshStorage.get();
  nextGeneration();
  const started = generation;
  applyTokens(tokens);
  knownMarker = writeNewMarker();
  if (previous && previous !== tokens.refreshToken) void revokeOnServer(previous);
  const user = await fetchMe(tokens.accessToken);
  if (started !== generation) return null;
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
  const started = generation;
  const user = await fetchMe(token);
  if (user && started === generation) useAuthStore.getState().setUser(user);
}

// ---- refresh --------------------------------------------------------------------------------------------------------------

/** Runs `fn` while holding a lock shared by every tab of this browser (Web Locks API), or directly where it does not exist. */
async function withCrossTabLock<T>(fn: () => Promise<T>): Promise<T> {
  const locks = typeof navigator === 'undefined' ? undefined : (navigator as Navigator & { locks?: LockManager }).locks;
  if (locks?.request) return locks.request('e9x-auth-refresh', fn) as Promise<T>;
  return fn();
}

async function rotate(attempt: number, started: number): Promise<string | null> {
  if (started !== generation) return null; // the session changed while this waited for the lock
  const sent = refreshStorage.get();
  if (!sent) return null;
  const marker = readMarker();
  const stale = () => started !== generation || readMarker() !== marker;
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
    if (stale()) {
      void revokeOnServer(tokens.refreshToken); // this session ended here or in another tab: its new token must not live on
      return null;
    }
    applyTokens(tokens);
    return tokens.accessToken;
  }
  if (stale()) return null; // says nothing about the session that is current now
  if (res.status === 401 || res.status === 400) {
    // Another tab may have rotated the token between our read and the server's answer. Only a token that is still the
    // one we sent is dead; otherwise try once more with what that tab stored.
    const now = refreshStorage.get();
    if (now && now !== sent && attempt < 2) return rotate(attempt + 1, started);
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
  if (inflight) return inflight;
  const started = generation;
  const running: Promise<string | null> = withCrossTabLock(() => rotate(0, started)).finally(() => {
    if (inflight === running) inflight = null;
  });
  inflight = running;
  return running;
}

/** On application start: restore the session from the stored refresh token. */
export async function bootstrapSession(): Promise<void> {
  const s = useAuthStore.getState();
  knownMarker = readMarker();
  if (!refreshStorage.get()) {
    s.setStatus('anonymous');
    return;
  }
  s.setStatus('loading');
  const started = generation;
  const token = await refreshSession();
  if (started !== generation) return;
  if (!token) {
    // dead token: endSession() already ran. Offline: stay anonymous but keep the stored token for the next load.
    useAuthStore.getState().setStatus('anonymous');
    return;
  }
  const user = await fetchMe(token);
  if (started !== generation) return;
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
  forgetReturn();
  if (token) await revokeOnServer(token);
}

/** Another tab signed in: this tab's token, user and cache belong to whoever was here before. Re-read who it is now. */
async function followSignInElsewhere(marker: string | null): Promise<void> {
  nextGeneration();
  knownMarker = marker;
  const started = generation;
  const s = useAuthStore.getState();
  const previousUser = s.user?.id;
  s.setAccessToken(null);
  s.setStatus('loading');
  const token = await refreshSession();
  if (started !== generation) return;
  const user = token ? await fetchMe(token) : null;
  if (started !== generation) return;
  if (!user) {
    forgetInThisTab(); // cannot tell who it is (a dead token already ended the session; offline keeps it for the next load)
    return;
  }
  const now = useAuthStore.getState();
  if (previousUser !== user.id) {
    useRoleStore.getState().setGrants(null);
    useContextStore.setState({ orgId: null });
  }
  now.setUser(user); // the query cache follows the user: see clearCacheOnUserChange
  now.setStatus('authenticated');
}

/**
 * Keeps this tab in step with the session the tabs share. A sign-out elsewhere signs this tab out; a sign-in elsewhere,
 * which can be another person, makes this tab re-read who it is; a rotation elsewhere changes nothing (rotating here in
 * answer would make the tabs chase each other). Returns the unsubscribe.
 */
export function syncSessionAcrossTabs(): () => void {
  if (typeof window === 'undefined') return () => {};
  const onStorage = (e: StorageEvent) => {
    if (e.key !== REFRESH_STORAGE_KEY && e.key !== SESSION_MARKER_KEY && e.key !== null) return;
    if (!refreshStorage.get()) {
      if (useAuthStore.getState().status !== 'anonymous') forgetInThisTab();
      return;
    }
    const marker = readMarker();
    if (marker !== knownMarker) void followSignInElsewhere(marker);
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
