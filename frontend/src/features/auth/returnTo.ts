import { isSafePath } from './problems';

/**
 * Where to go back to when the way there leaves this page: the mail that verifies an address opens in another tab, and
 * Google sign-in is a full-page round trip, so `?next=` alone cannot carry it. A new person following an invitation link
 * needs this to reach the invitation again without reopening the mail.
 *
 * It holds a same-site path, which for an invitation contains the invitation token, in localStorage next to the refresh token
 * (see session.ts for that trade-off). The token alone grants nothing: accepting needs the invited address, signed in and
 * verified. The entry is spent on first use, forgotten on sign-out, and expires with the verification link. Every read
 * checks the path again: storage is input.
 */
const KEY = 'e9x.auth.returnTo';
const MAX_AGE_MS = 24 * 60 * 60 * 1000; // e9x.auth.verification-ttl: the mail is the reason this exists

export function forgetReturn(): void {
  try {
    localStorage.removeItem(KEY);
  } catch {
    /* storage unavailable: nothing was kept */
  }
}

export function rememberReturn(path: string): void {
  if (path === '/' || !isSafePath(path)) return;
  try {
    localStorage.setItem(KEY, JSON.stringify({ path, at: Date.now() }));
  } catch {
    /* storage unavailable: the person reopens the mail */
  }
}

/** The remembered path, still there afterwards. */
export function peekReturn(): string | null {
  let raw: string | null;
  try {
    raw = localStorage.getItem(KEY);
  } catch {
    return null;
  }
  if (raw === null) return null;
  try {
    const v = JSON.parse(raw) as { path?: unknown; at?: unknown };
    if (typeof v.path === 'string' && typeof v.at === 'number' && isSafePath(v.path) && Date.now() - v.at < MAX_AGE_MS) return v.path;
  } catch {
    /* not ours: fall through and drop it */
  }
  forgetReturn();
  return null;
}

/** The remembered path or `fallback`; either way nothing stays remembered. */
export function takeReturn(fallback = '/'): string {
  const path = peekReturn();
  forgetReturn();
  return path ?? fallback;
}
