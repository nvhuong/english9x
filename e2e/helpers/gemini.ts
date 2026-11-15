import { mkdir, readFile, rm, stat, utimes, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { env, json, poll } from './env';

export type GeminiScenario = 'ok' | 'error500' | 'badSchema';

export interface GeminiRequest {
  model: string;
  scenario: GeminiScenario;
  body: unknown;
}

/**
 * The fake Gemini holds ONE global scenario, so tests that change it must not overlap. Use the `gemini` fixture
 * (fixtures/index.ts): it takes this lock for the whole test and resets the fake before and after.
 */
const LOCK_DIR = join(tmpdir(), 'e9x-e2e-gemini.lock');
/** A crash guard only: a live holder refreshes the lock's mtime every REFRESH_MS, so a lock this old belongs to a process that died. */
const STALE_MS = 5 * 60_000;
const REFRESH_MS = 30_000;
const OWNER_FILE = join(LOCK_DIR, 'owner');

export async function acquireGeminiLock(timeoutMs = 120_000): Promise<() => Promise<void>> {
  const token = `${process.pid}-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  await poll('the fake-gemini lock (another test is using the shared scenario)', async () => {
    try {
      await mkdir(LOCK_DIR); // atomic: fails if it exists
      await writeFile(OWNER_FILE, token);
      return true;
    } catch {
      try {
        if (Date.now() - (await stat(LOCK_DIR)).mtimeMs > STALE_MS) await rm(LOCK_DIR, { recursive: true, force: true });
      } catch { /* released meanwhile */ }
      return undefined;
    }
  }, { timeoutMs, intervalMs: 100 });
  // keep the lock fresh while held, so that a long test is never mistaken for a crashed one
  const refresh = setInterval(() => { const now = new Date(); utimes(LOCK_DIR, now, now).catch(() => {}); }, REFRESH_MS);
  refresh.unref();
  return async () => {
    clearInterval(refresh);
    // only the owner removes it: a lock taken over after a stall belongs to someone else now
    try {
      if ((await readFile(OWNER_FILE, 'utf8')) === token) await rm(LOCK_DIR, { recursive: true, force: true });
    } catch { /* already gone */ }
  };
}

export async function setScenario(scenario: GeminiScenario): Promise<void> {
  await json(`${env.geminiURL}/__scenario`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ scenario }),
  });
}

export async function getScenario(): Promise<GeminiScenario> {
  return (await json<{ scenario: GeminiScenario }>(`${env.geminiURL}/__scenario`)).scenario;
}

/** Requests the app made to the fake so far. */
export async function requests(): Promise<GeminiRequest[]> {
  return json<GeminiRequest[]>(`${env.geminiURL}/__requests`);
}

/** Back to the default: scenario 'ok', no recorded requests. */
export async function reset(): Promise<void> {
  await setScenario('ok');
  await json(`${env.geminiURL}/__requests`, { method: 'DELETE' });
}

export const gemini = { setScenario, getScenario, requests, reset };
