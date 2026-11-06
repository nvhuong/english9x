import { env, json } from './helpers/env';

/** Fails fast, with the reason, when the stack is not what the specs assume (instead of 60 s timeouts per test). */
export default async function globalSetup(): Promise<void> {
  const problems: string[] = [];
  const check = async (name: string, fn: () => Promise<void>) => {
    try { await fn(); } catch (e) { problems.push(`${name}: ${e instanceof Error ? e.message : String(e)}`); }
  };
  await check('app (via Caddy)', async () => {
    await json(`${env.baseURL}/api/platform/ping`);
  });
  await check('seed API (Spring profile e2e)', async () => {
    const r = await json<{ status: string }>(`${env.baseURL}/api/test/seed/ping`, { method: 'POST', headers: { 'x-e2e-seed-secret': env.seedSecret } });
    if (r.status !== 'ok') throw new Error(`unexpected answer ${JSON.stringify(r)}`);
  });
  await check('mailpit', () => json(`${env.mailpitURL}/api/v1/info`).then(() => undefined));
  await check('fake-telegram', () => json(`${env.telegramURL}/__health`).then(() => undefined));
  await check('fake-gemini', () => json(`${env.geminiURL}/__health`).then(() => undefined));
  await check('fake-oidc', () => json(`${env.oidcURL}/__health`).then(() => undefined));
  if (problems.length > 0) {
    throw new Error(`The E2E stack is not ready. Start it with ./scripts/e2e.sh (or --keep).\n - ${problems.join('\n - ')}`);
  }
}
