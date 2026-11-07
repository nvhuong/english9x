import { expect, type Page } from '@playwright/test';
import { mailpit } from '../helpers/mailpit';

/** Signs `page` in through the real login screen (email + password) and waits until the header shows the person. */
export async function loginAs(page: Page, user: { email: string; password: string }): Promise<void> {
  await page.goto('/login');
  await page.getByLabel(/^Email/).fill(user.email);
  await page.getByLabel(/^Mật khẩu/).fill(user.password);
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Đăng xuất' })).toBeVisible();
}

/**
 * Registers a learner through the real form (consent included) and returns the verification link from the mail once it
 * has arrived. The address stays UNVERIFIED until the link is opened. Vietnamese UI.
 */
export async function registerLearner(
  page: Page,
  user: { email: string; password: string; displayName?: string },
): Promise<{ verifyLink: string }> {
  await page.goto('/register');
  await page.getByLabel(/^Tên hiển thị/).fill(user.displayName ?? 'Học viên thử');
  await page.getByLabel(/^Email/).fill(user.email);
  await page.getByLabel(/^Mật khẩu/).fill(user.password);
  await page.getByRole('checkbox').check();
  await page.getByRole('button', { name: 'Đăng ký', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Hãy kiểm tra email của bạn' })).toBeVisible();
  const mail = await mailpit.lastEmailTo(user.email, { subjectIncludes: 'Xác thực' });
  const verifyLink = mailpit.linksIn(mail).find((l) => l.includes('/verify-email?token='));
  if (!verifyLink) throw new Error(`no verification link in the mail to ${user.email}: ${mail.Text}`);
  return { verifyLink };
}
