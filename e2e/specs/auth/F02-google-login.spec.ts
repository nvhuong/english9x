// F02 — Đăng nhập Google (spec §16.2): "Đăng nhập qua OAuth giả lập → tạo tài khoản → lần sau dùng lại tài khoản".
// The provider is the fake OpenID server of the E2E stack (infra/fakes/oidc): the browser really leaves for it, the
// person "chooses an account" on its page, and the app really validates state, nonce, PKCE and the ID token.
import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { env } from '../../helpers/env';
import { agreeToGoogleConsent, chooseGoogleAccount, type GoogleAccount } from '../../helpers/google';

/** Clicks "Đăng nhập bằng Google" on the login page and answers the fake provider's account chooser. */
async function signInWithGoogle(page: Page, account: GoogleAccount) {
  await page.goto('/login');
  await page.getByRole('link', { name: 'Đăng nhập bằng Google' }).click();
  await chooseGoogleAccount(page, account);
}

/** Runs a sign-in up to the moment the page would redeem the code, and stops there, as an attacker would. Returns the code. */
async function stopAtTheRedirect(page: Page, account: GoogleAccount): Promise<string> {
  let code = '';
  await page.route('**/api/auth/oauth/exchange', async (route) => {
    code = (route.request().postDataJSON() as { code: string }).code;
    await route.abort();
  });
  await signInWithGoogle(page, account);
  await expect.poll(() => code).not.toBe('');
  return code;
}

test.describe('F02 Google sign-in', () => {
  test('F02 the first sign-in creates the account, the next one reuses it', async ({ page, seed, uniqueEmail }) => {
    const email = uniqueEmail('f02-new');
    await signInWithGoogle(page, { email, name: 'Lê Thu Hà' });
    // nobody has agreed to anything yet: the server asks, and nothing is issued before the answer
    await expect(page.getByRole('heading', { level: 1, name: 'Còn một bước nữa' })).toBeVisible();
    expect(await seed.userInfo(email)).toMatchObject({ count: 1, hasConsent: false });
    await page.getByRole('button', { name: 'Đồng ý và tiếp tục' }).click();
    await expect(page.getByRole('alert')).toContainText('Bạn cần đồng ý để tiếp tục');
    expect(await page.evaluate(() => localStorage.getItem('e9x.auth.refresh')), 'no token before consent').toBeNull();
    await agreeToGoogleConsent(page);
    await expect(page.getByText('Đã đăng nhập: Lê Thu Hà')).toBeVisible();
    await expect(page).toHaveURL('https://localhost/');
    // no token, code or session id was left in the address bar or the history
    expect(page.url()).not.toMatch(/code=|token=|eyJ/);
    expect(await seed.userInfo(email)).toMatchObject({ count: 1, hasPassword: false, emailVerified: true, hasConsent: true, identities: 1 });
    const firstId = (await seed.userInfo(email)).userId;
    await expect(page.getByTestId('verify-email-banner'), 'Google vouched for the mailbox').toHaveCount(0);

    await page.getByRole('button', { name: 'Đăng xuất' }).click();
    await expect(page.getByRole('link', { name: 'Đăng nhập' }).first()).toBeVisible();

    await signInWithGoogle(page, { email, name: 'Lê Thu Hà' }); // the consent is on record: no second question
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
    const victim = await seed.verifiedLearner({ email: uniqueEmail('f02-prehijack'), password: 'Attacker-pass-9', verified: false, displayName: 'Kẻ tấn công' });
    await signInWithGoogle(page, { email: victim.email, name: 'Chủ thật' });
    // what the attacker chose is gone (password, name, their consent), and the owner gives their own consent
    await expect(page.getByRole('heading', { level: 1, name: 'Còn một bước nữa' })).toBeVisible();
    expect(await seed.userInfo(victim.email)).toMatchObject({ hasConsent: false, hasPassword: false, displayName: 'Chủ thật' });
    await agreeToGoogleConsent(page);
    await expect(page.getByText('Đã đăng nhập: Chủ thật')).toBeVisible();
    expect(await seed.userInfo(victim.email)).toMatchObject({ count: 1, userId: victim.userId, hasPassword: false, emailVerified: true, hasConsent: true, identities: 1 });
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

  test('F02 the hand-over code is bound by a secure host-only cookie, through Caddy over HTTPS', async ({ page, context, uniqueEmail }) => {
    await signInWithGoogle(page, { email: uniqueEmail('f02-cookie') });
    await expect(page.getByRole('heading', { level: 1, name: 'Còn một bước nữa' })).toBeVisible();
    const cookie = (await context.cookies(env.baseURL)).find((c) => c.name === '__Host-e9x-login');
    expect(cookie, 'the callback set it and the browser accepted it').toBeDefined();
    expect(cookie).toMatchObject({ secure: true, httpOnly: true, sameSite: 'Strict', path: '/', domain: 'localhost' });
    await agreeToGoogleConsent(page);
    await expect(page.getByRole('button', { name: 'Đăng xuất' })).toBeVisible();
    await expect.poll(async () => (await context.cookies(env.baseURL)).some((c) => c.name === '__Host-e9x-login'), 'cleared once spent').toBe(false);
  });

  test('F02 login CSRF: a code an attacker stopped at does nothing in the victim browser', async ({ page, browser, seed, uniqueEmail }) => {
    const attacker = await seed.verifiedLearner({ email: uniqueEmail('f02-csrf-attacker'), displayName: 'Kẻ tấn công' });
    const code = await stopAtTheRedirect(page, { email: attacker.email });

    // the victim, signed out, opens the link
    const anonymous = await browser.newContext({ baseURL: env.baseURL, ignoreHTTPSErrors: true });
    const fresh = await anonymous.newPage();
    await fresh.goto(`/login/google?code=${encodeURIComponent(code)}`);
    await expect(fresh.getByRole('heading', { level: 1, name: 'Đăng nhập bằng Google không thành công' })).toBeVisible();
    expect(await fresh.evaluate(() => localStorage.getItem('e9x.auth.refresh'))).toBeNull();
    await anonymous.close();

    // the victim, signed in, opens the link: nothing is spent or replaced unasked, and "continue" fails on the server
    const victim = await seed.verifiedLearner({ email: uniqueEmail('f02-csrf-victim'), displayName: 'Nạn Nhân' });
    const signedIn = await browser.newContext({ baseURL: env.baseURL, ignoreHTTPSErrors: true });
    const tab = await signedIn.newPage();
    await tab.goto('/login');
    await tab.getByLabel(/^Email/).fill(victim.email);
    await tab.getByLabel(/^Mật khẩu/).fill(victim.password);
    await tab.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
    await expect(tab.getByText('Đã đăng nhập: Nạn Nhân')).toBeVisible();
    await tab.goto(`/login/google?code=${encodeURIComponent(code)}`);
    await expect(tab.getByRole('heading', { level: 1, name: 'Bạn đang đăng nhập' })).toBeVisible();
    await tab.getByRole('button', { name: 'Tiếp tục với tài khoản Google' }).click();
    await expect(tab.getByRole('alert')).toBeVisible();
    await tab.goto('/');
    await expect(tab.getByText('Đã đăng nhập: Nạn Nhân'), 'still the victim').toBeVisible();
    await signedIn.close();
  });

  test('F02 signing in with Google while already signed in asks first, and either answer does what it says', async ({ page, auth, seed, uniqueEmail }) => {
    const current = await seed.verifiedLearner({ email: uniqueEmail('f02-live-a'), displayName: 'Người Hiện Tại' });
    const other = await seed.verifiedLearner({ email: uniqueEmail('f02-live-b'), displayName: 'Người Khác' });
    await auth.loginAs(page, current);

    await signInWithGoogle(page, { email: other.email });
    await expect(page.getByRole('heading', { level: 1, name: 'Bạn đang đăng nhập' })).toBeVisible();
    await expect(page.getByText(/Người Hiện Tại/).first()).toBeVisible();
    await page.getByRole('link', { name: 'Giữ phiên hiện tại' }).click();
    await expect(page.getByText('Đã đăng nhập: Người Hiện Tại')).toBeVisible();

    await signInWithGoogle(page, { email: other.email });
    await page.getByRole('button', { name: 'Tiếp tục với tài khoản Google' }).click();
    await expect(page.getByText('Đã đăng nhập: Người Khác')).toBeVisible();
  });
});
