// F02 — Đăng nhập Google (spec §16.2): "Đăng nhập qua OAuth giả lập → tạo tài khoản → lần sau dùng lại tài khoản".
// The provider is the fake OpenID server of the E2E stack (infra/fakes/oidc): the browser really leaves for it, the
// person "chooses an account" on its page, and the app really validates state, nonce, PKCE and the ID token.
import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { env } from '../../helpers/env';

/** Clicks "Đăng nhập bằng Google" on the login page and answers the fake provider's account chooser. */
async function signInWithGoogle(page: Page, opts: { email: string; name?: string; verified?: boolean; sub?: string }) {
  await page.goto('/login');
  await page.getByRole('link', { name: 'Đăng nhập bằng Google' }).click();
  await expect(page).toHaveURL(new RegExp(`^${env.oidcURL.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}/authorize`));
  await page.getByLabel('Google account email').fill(opts.email);
  if (opts.name) await page.getByLabel('Name').fill(opts.name);
  if (opts.sub) await page.getByLabel(/Subject/).fill(opts.sub);
  if (opts.verified === false) await page.getByLabel(/Google has verified this email/).uncheck();
  await page.getByRole('button', { name: 'Continue' }).click();
}

test.describe('F02 Google sign-in', () => {
  test('F02 the first sign-in creates the account, the next one reuses it', async ({ page, seed, uniqueEmail }) => {
    const email = uniqueEmail('f02-new');
    await signInWithGoogle(page, { email, name: 'Lê Thu Hà' });
    await expect(page.getByText('Đã đăng nhập: Lê Thu Hà')).toBeVisible();
    await expect(page).toHaveURL('https://localhost/');
    // no token, code or session id was left in the address bar or the history
    expect(page.url()).not.toMatch(/code=|token=|eyJ/);
    expect(await seed.userInfo(email)).toMatchObject({ count: 1, hasPassword: false, emailVerified: true, identities: 1 });
    const firstId = (await seed.userInfo(email)).userId;
    await expect(page.getByTestId('verify-email-banner'), 'Google vouched for the mailbox').toHaveCount(0);

    await page.getByRole('button', { name: 'Đăng xuất' }).click();
    await expect(page.getByRole('link', { name: 'Đăng nhập' }).first()).toBeVisible();

    await signInWithGoogle(page, { email, name: 'Lê Thu Hà' });
    await expect(page.getByText('Đã đăng nhập: Lê Thu Hà')).toBeVisible();
    expect(await seed.userInfo(email)).toMatchObject({ count: 1, identities: 1, userId: firstId });
  });

  test('F02 Google joins an existing verified account with the same email, which keeps its password', async ({ page, seed, uniqueEmail }) => {
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f02-link'), displayName: 'Phạm Quốc Cường' });
    await signInWithGoogle(page, { email: learner.email, name: 'Someone on Google' });
    await expect(page.getByText('Đã đăng nhập: Phạm Quốc Cường')).toBeVisible(); // the existing account, not a new one
    expect(await seed.userInfo(learner.email)).toMatchObject({ count: 1, userId: learner.userId, hasPassword: true, identities: 1 });
    await page.getByRole('button', { name: 'Đăng xuất' }).click();
    await page.getByRole('link', { name: 'Đăng nhập' }).first().click();
    await page.getByLabel(/^Email/).fill(learner.email);
    await page.getByLabel(/^Mật khẩu/).fill(learner.password);
    await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
    await expect(page.getByText('Đã đăng nhập: Phạm Quốc Cường')).toBeVisible();
  });

  test('F02 an email Google does not vouch for neither links nor creates anything', async ({ page, seed, uniqueEmail }) => {
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f02-unverified') });
    await signInWithGoogle(page, { email: learner.email, verified: false });
    await expect(page).toHaveURL(/\/login\?error=google_email_unverified/);
    await expect(page.getByRole('alert')).toContainText('Google chưa xác nhận');
    await expect(page.getByRole('button', { name: 'Đăng xuất' })).toHaveCount(0);
    expect(await seed.userInfo(learner.email)).toMatchObject({ count: 1, identities: 0, hasPassword: true });

    const stranger = uniqueEmail('f02-stranger');
    await signInWithGoogle(page, { email: stranger, verified: false });
    await expect(page.getByRole('alert')).toContainText('Google chưa xác nhận');
    expect(await seed.userInfo(stranger)).toMatchObject({ count: 0 });
  });

  test('F02 an unverified local account loses its password when the real owner signs in with Google (pre-hijack)', async ({ page, seed, uniqueEmail }) => {
    // an attacker registered the victim's address with a password they know; nobody verified it
    const victim = await seed.verifiedLearner({ email: uniqueEmail('f02-prehijack'), password: 'Attacker-pass-9', verified: false, displayName: 'Chủ thật' });
    await signInWithGoogle(page, { email: victim.email, name: 'Chủ thật' });
    await expect(page.getByText('Đã đăng nhập: Chủ thật')).toBeVisible();
    expect(await seed.userInfo(victim.email)).toMatchObject({ count: 1, userId: victim.userId, hasPassword: false, emailVerified: true, identities: 1 });
    await page.getByRole('button', { name: 'Đăng xuất' }).click();
    await page.getByRole('link', { name: 'Đăng nhập' }).first().click();
    await page.getByLabel(/^Email/).fill(victim.email);
    await page.getByLabel(/^Mật khẩu/).fill('Attacker-pass-9');
    await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('Email hoặc mật khẩu chưa đúng.');
  });

  test('F02 a sign-in code in the address bar cannot be replayed and a tampered callback is refused', async ({ page, request }) => {
    // a made-up code: the page says so and offers the way back
    await page.goto('/login/google?code=' + 'A'.repeat(43));
    await expect(page.getByRole('heading', { level: 1, name: 'Đăng nhập bằng Google không thành công' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Quay lại đăng nhập' })).toBeVisible();
    // the callback without the browser session that started the flow (here: a bare API request) is sent back to /login
    const res = await request.get('/api/auth/oauth2/callback/google?code=x&state=y', { maxRedirects: 0 });
    expect(res.status()).toBe(302);
    expect(res.headers()['location']).toContain('/login?error=google');
  });
});
