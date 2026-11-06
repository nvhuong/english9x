import { defineConfig, devices } from '@playwright/test';

// Specs live in e2e/specs/<area>/F<nn>-*.spec.ts (added feature by feature; fixtures and smoke in P1.12).
// Run against the Compose stack: docker compose -f infra/docker-compose.yml -f infra/docker-compose.e2e.yml up -d
export default defineConfig({
  testDir: './specs',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'https://localhost',
    ignoreHTTPSErrors: true, // Caddy internal CA
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
