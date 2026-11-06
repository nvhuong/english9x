import { mkdir, rm, stat } from 'node:fs/promises';
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
const STALE_MS = 5 * 60_000;

export async function acquireGeminiLock(timeoutMs = 120_000): Promise<() => Promise<void>> {
  await poll('the fake-gemini lock (another test is using the shared scenario)', async () => {
    try {
      await mkdir(LOCK_DIR); // atomic: fails if it exists
      return true;
    } catch {
      try {
        if (Date.now() - (await stat(LOCK_DIR)).mtimeMs > STALE_MS) await rm(LOCK_DIR, { recursive: true, force: true });
      } catch { /* released meanwhile */ }
      return undefined;
    }
  }, { timeoutMs, intervalMs: 100 });
  return () => rm(LOCK_DIR, { recursive: true, force: true });
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
