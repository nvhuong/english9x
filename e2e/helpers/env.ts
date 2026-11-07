import http from 'node:http';
import https from 'node:https';

/** Where the E2E stack's services are reachable from the test runner (the host). scripts/e2e.sh exports the ports it chose. */
export const env = {
  baseURL: process.env.E2E_BASE_URL ?? 'https://localhost',
  mailpitURL: process.env.E2E_MAILPIT_URL ?? `http://localhost:${process.env.E9X_MAILPIT_PORT ?? '8025'}`,
  telegramURL: process.env.E2E_TELEGRAM_URL ?? `http://localhost:${process.env.E9X_FAKE_TELEGRAM_PORT ?? '8081'}`,
  geminiURL: process.env.E2E_GEMINI_URL ?? `http://localhost:${process.env.E9X_FAKE_GEMINI_PORT ?? '8082'}`,
  /** The fake "Google" (OpenID provider) as the BROWSER reaches it. */
  oidcURL: process.env.E2E_OIDC_URL ?? `http://localhost:${process.env.E9X_FAKE_OIDC_PORT ?? '8083'}`,
  /** The secret the app expects in X-Telegram-Bot-Api-Secret-Token (scripts/e2e.sh generates one per run): the specs act as Telegram with it. */
  telegramWebhookSecret: process.env.E9X_E2E_TELEGRAM_WEBHOOK_SECRET ?? '',
  /** Shared secret of the seed API (X-E2E-Seed-Secret). scripts/e2e.sh generates one per run and exports it. */
  seedSecret: process.env.E9X_E2E_SEED_SECRET ?? process.env.E2E_SEED_SECRET ?? '',
};

export interface PollOptions {
  /** Give up after this many ms (default 15000). */
  timeoutMs?: number;
  /** Delay between attempts (default 250). */
  intervalMs?: number;
}

/** Calls `attempt` until it returns a value (not undefined/null), or throws a descriptive error at the timeout. No fixed sleeps. */
export async function poll<T>(what: string, attempt: () => Promise<T | undefined | null>, opts: PollOptions = {}): Promise<T> {
  const { timeoutMs = 15_000, intervalMs = 250 } = opts;
  const deadline = Date.now() + timeoutMs;
  let lastError: unknown;
  for (;;) {
    try {
      const value = await attempt();
      if (value !== undefined && value !== null) return value;
      lastError = undefined;
    } catch (e) {
      lastError = e;
    }
    if (Date.now() >= deadline) {
      const why = lastError instanceof Error ? ` (last error: ${lastError.message})` : '';
      throw new Error(`Timed out after ${timeoutMs} ms waiting for ${what}${why}`);
    }
    await new Promise((r) => setTimeout(r, intervalMs));
  }
}

/** One HTTP call; returns the status and body text whatever the status (see {@link json} for the throwing variant). */
export async function request(url: string, init: { method?: string; headers?: Record<string, string>; body?: string } = {}): Promise<{ status: number; text: string }> {
  const target = new URL(url);
  const client = target.protocol === 'https:' ? https : http;
  const method = init.method ?? 'GET';
  return new Promise<{ status: number; text: string }>((resolve, reject) => {
    const req = client.request(
      target,
      { method, headers: init.headers, rejectUnauthorized: false, timeout: 20_000 }, // E2E only: Caddy's own CA
      (res) => {
        const chunks: Buffer[] = [];
        res.on('data', (c: Buffer) => chunks.push(c));
        res.on('end', () => resolve({ status: res.statusCode ?? 0, text: Buffer.concat(chunks).toString('utf8') }));
      },
    );
    req.on('timeout', () => req.destroy(new Error(`${method} ${url} timed out`)));
    req.on('error', reject);
    req.end(init.body);
  });
}

/**
 * HTTP + JSON (plain-text answers come back as the string) with a clear error on a non-2xx answer. Uses node:http(s) rather than fetch so that the stack's HTTPS
 * (Caddy's internal CA) can be accepted for these harness calls only; the browser side does it in playwright.config.ts.
 */
export async function json<T>(url: string, init: { method?: string; headers?: Record<string, string>; body?: string } = {}): Promise<T> {
  const method = init.method ?? 'GET';
  const { status, text } = await request(url, init);
  if (status < 200 || status >= 300) throw new Error(`${method} ${url} -> ${status}: ${text.slice(0, 300)}`);
  if (!text) return undefined as T;
  try { return JSON.parse(text) as T; } catch { return text as T; } // e.g. Mailpit answers DELETE with plain "ok"
}
