// Smoke (not a feature): the stack is up and the home page renders. Name starts with "_": never counted by the gate.
import { test, expect } from '../fixtures';

test('the app is up behind Caddy and the learner home renders', async ({ page, request }) => {
  const ping = await request.get('/api/platform/ping');
  expect(ping.ok()).toBe(true);
  expect(await ping.json()).toEqual({ status: 'ok' });

  const health = await request.get('/actuator/health/readiness');
  expect(await health.json()).toMatchObject({ status: 'UP' });

  await page.goto('/');
  await expect(page).toHaveTitle(/English9x/);
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('lang', 'vi');
});
