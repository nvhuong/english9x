import http from 'node:http';
import https from 'node:https';

/** Where the E2E stack's services are reachable from the test runner (the host). scripts/e2e.sh exports the ports it chose. */
export const env = {
  baseURL: process.env.E2E_BASE_URL ?? 'https://localhost',
  mailpitURL: process.env.E2E_MAILPIT_URL ?? `http://localhost:${process.env.E9X_MAILPIT_PORT ?? '8025'}`,
  telegramURL: process.env.E2E_TELEGRAM_URL ?? `http://localhost:${process.env.E9X_FAKE_TELEGRAM_PORT ?? '8081'}`,
  geminiURL: process.env.E2E_GEMINI_URL ?? `http://localhost:${process.env.E9X_FAKE_GEMINI_PORT ?? '8082'}`,
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

/**
 * HTTP + JSON (plain-text answers come back as the string) with a clear error on a non-2xx answer. Uses node:http(s) rather than fetch so that the stack's HTTPS
 * (Caddy's internal CA) can be accepted for these harness calls only; the browser side does it in playwright.config.ts.
 */
export async function json<T>(url: string, init: { method?: string; headers?: Record<string, string>; body?: string } = {}): Promise<T> {
  const target = new URL(url);
  const client = target.protocol === 'https:' ? https : http;
  const method = init.method ?? 'GET';
  const { status, text } = await new Promise<{ status: number; text: string }>((resolve, reject) => {
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
  if (status < 200 || status >= 300) throw new Error(`${method} ${url} -> ${status}: ${text.slice(0, 300)}`);
  if (!text) return undefined as T;
  try { return JSON.parse(text) as T; } catch { return text as T; } // e.g. Mailpit answers DELETE with plain "ok"
}
