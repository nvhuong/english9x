import { defineConfig, devices } from '@playwright/test';
import { env } from './helpers/env';

// Specs live in e2e/specs/<area>/F<nn>-*.spec.ts (one per feature, gate: scripts/check-e2e-coverage.mjs) plus
// harness specs named _*.spec.ts (not features). Run with ./scripts/e2e.sh: it starts the Compose stack of
// infra/docker-compose.e2e.yml with a clean database, runs this config and tears the stack down.
//
// Caddy serves HTTPS with its own internal CA, so the browser must accept it: ignoreHTTPSErrors is set HERE, in the
// E2E config only, never in application code.
const video = (process.env.E2E_VIDEO ?? 'on') as 'on' | 'off' | 'retain-on-failure';

export default defineConfig({
  testDir: './specs',
  outputDir: './test-results', // videos, traces, screenshots of every run land here
  globalSetup: './global-setup.ts',
  fullyParallel: true,
  workers: Number(process.env.E2E_WORKERS ?? 2), // conservative: the whole stack runs on one 8 GB machine
  forbidOnly: !!process.env.CI,
  retries: 0, // a flaky test is a bug to fix, not to retry
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [['github'], ['list'], ['html', { open: 'never' }]] : [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: env.baseURL,
    ignoreHTTPSErrors: true, // Caddy internal CA (E2E only)
    trace: 'retain-on-failure',
    video, // main flows are recorded; E2E_VIDEO=retain-on-failure to keep only failures
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
