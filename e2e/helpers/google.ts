import { expect, type Page } from '@playwright/test';
import { env } from './env';

export interface GoogleAccount { email: string; name?: string; verified?: boolean; sub?: string }

/** Answers the fake provider's account chooser (infra/fakes/oidc): the browser really is on another origin here. */
export async function chooseGoogleAccount(page: Page, account: GoogleAccount): Promise<void> {
  await expect(page).toHaveURL(new RegExp(`^${env.oidcURL.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}/authorize`));
  await page.getByLabel('Google account email').fill(account.email);
  if (account.name) await page.getByLabel('Name').fill(account.name);
  if (account.sub) await page.getByLabel(/Subject/).fill(account.sub);
  if (account.verified === false) await page.getByLabel(/Google has verified this email/).uncheck();
  await page.getByRole('button', { name: 'Continue' }).click();
}

/** Ticks the consent box of a first Google sign-in (Vietnamese UI) and agrees. */
export async function agreeToGoogleConsent(page: Page): Promise<void> {
  await expect(page.getByRole('heading', { level: 1, name: 'Còn một bước nữa' })).toBeVisible();
  await page.getByRole('checkbox', { name: /Nghị định 13\/2023/ }).check();
  await page.getByRole('button', { name: 'Đồng ý và tiếp tục' }).click();
}
