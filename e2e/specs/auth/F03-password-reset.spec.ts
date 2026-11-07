// F03 — Quên mật khẩu (spec §16.2): "Yêu cầu → link → đặt mật khẩu mới → mật khẩu cũ bị từ chối; refresh token cũ bị thu hồi".
// Through the real screens and the real mail (Mailpit). The reset revokes the refresh tokens and moves the credentials version, which
// ends every access token issued before it (checked per request).
import { test, expect } from '../../fixtures';
import { env, request } from '../../helpers/env';

const NEW_PASSWORD = 'Brand-new-pass-2';

test.describe('F03 password reset', () => {
  test('F03 request, follow the link, set a new password: the old one and the old sessions are rejected', async ({ page, browser, seed, mailpit, uniqueEmail }) => {
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f03'), displayName: 'Võ Minh Đức' });

    // a session on one device (browser context 1)
    await page.goto('/login');
    await page.getByLabel(/^Email/).fill(learner.email);
    await page.getByLabel(/^Mật khẩu/).fill(learner.password);
    await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
    await expect(page.getByText('Đã đăng nhập: Võ Minh Đức')).toBeVisible();
    const oldRefresh = await page.evaluate(() => localStorage.getItem('e9x.auth.refresh'));
    expect(oldRefresh).toBeTruthy();

    // the person forgot the password on another device (context 2) and asks for a link
    const other = await browser.newContext({ ignoreHTTPSErrors: true });
    const phone = await other.newPage();
    await phone.goto('/forgot-password');
    await phone.getByLabel(/^Email/).fill(learner.email);
    await phone.getByRole('button', { name: 'Gửi liên kết' }).click();
    await expect(phone.getByRole('status').filter({ hasText: 'Nếu có tài khoản dùng email này' })).toBeVisible();

    const mail = await mailpit.lastEmailTo(learner.email, { subjectIncludes: 'Đặt lại mật khẩu' });
    const link = mailpit.linksIn(mail).find((l) => l.includes('/reset-password?token='))!;
    expect(link.startsWith('https://localhost/reset-password?token=')).toBe(true);

    // a mismatching confirmation is caught in the browser; then the real change
    await phone.goto(link);
    await phone.getByLabel(/^Mật khẩu mới/).fill(NEW_PASSWORD);
    await phone.getByLabel(/^Nhập lại mật khẩu mới/).fill('Something-else-3');
    await phone.getByRole('button', { name: 'Đặt mật khẩu' }).click();
    await expect(phone.getByText('Hai mật khẩu chưa giống nhau.')).toBeVisible();
    await phone.getByLabel(/^Nhập lại mật khẩu mới/).fill(NEW_PASSWORD);
    await phone.getByRole('button', { name: 'Đặt mật khẩu' }).click();
    await expect(phone.getByRole('heading', { level: 1, name: 'Đã đổi mật khẩu' })).toBeVisible();

    // the old password is rejected, the new one works
    await phone.locator('#main').getByRole('link', { name: 'Đăng nhập' }).click();
    await phone.getByLabel(/^Email/).fill(learner.email);
    await phone.getByLabel(/^Mật khẩu/).fill(learner.password);
    await phone.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
    await expect(phone.getByRole('alert')).toContainText('Email hoặc mật khẩu chưa đúng.');
    await phone.getByLabel(/^Mật khẩu/).fill(NEW_PASSWORD);
    await phone.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
    await expect(phone.getByText('Đã đăng nhập: Võ Minh Đức')).toBeVisible();

    // the first device's refresh token is dead...
    const dead = await request(`${env.baseURL}/api/auth/refresh`, {
      method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ refreshToken: oldRefresh }),
    });
    expect(dead.status).toBe(401);
    // ...so when that browser next restores its session (a reload drops the in-memory access token) it is signed out
    await page.reload();
    await expect(page.getByRole('link', { name: 'Đăng nhập' }).first()).toBeVisible();
    await expect(page.getByRole('button', { name: 'Đăng xuất' })).toHaveCount(0);
    expect(await page.evaluate(() => localStorage.getItem('e9x.auth.refresh'))).toBeNull();
    await other.close();
  });

  test('F03 the link works once', async ({ page, seed, mailpit, uniqueEmail }) => {
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f03-once') });
    await page.goto('/forgot-password');
    await page.getByLabel(/^Email/).fill(learner.email);
    await page.getByRole('button', { name: 'Gửi liên kết' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Hãy kiểm tra email của bạn' })).toBeVisible();
    const link = mailpit.linksIn(await mailpit.lastEmailTo(learner.email, { subjectIncludes: 'Đặt lại mật khẩu' }))
      .find((l) => l.includes('/reset-password?token='))!;
    for (const [round, password] of [[1, NEW_PASSWORD], [2, 'Yet-another-pass-3']] as const) {
      await page.goto(link);
      if (round === 2) {
        await page.getByLabel(/^Mật khẩu mới/).fill(password);
        await page.getByLabel(/^Nhập lại mật khẩu mới/).fill(password);
        await page.getByRole('button', { name: 'Đặt mật khẩu' }).click();
        await expect(page.getByRole('alert')).toContainText('Liên kết không hợp lệ, đã được dùng hoặc đã hết hạn.');
        await expect(page.getByRole('link', { name: 'Yêu cầu liên kết mới' })).toBeVisible();
      } else {
        await page.getByLabel(/^Mật khẩu mới/).fill(password);
        await page.getByLabel(/^Nhập lại mật khẩu mới/).fill(password);
        await page.getByRole('button', { name: 'Đặt mật khẩu' }).click();
        await expect(page.getByRole('heading', { level: 1, name: 'Đã đổi mật khẩu' })).toBeVisible();
      }
    }
  });

  test('F03 an unknown address gets the same screen and no mail', async ({ page, mailpit, seed, uniqueEmail }) => {
    const known = await seed.verifiedLearner({ email: uniqueEmail('f03-known') });
    const unknown = uniqueEmail('f03-unknown');
    const texts: string[] = [];
    for (const email of [known.email, unknown]) {
      await page.goto('/forgot-password');
      await page.getByLabel(/^Email/).fill(email);
      await page.getByRole('button', { name: 'Gửi liên kết' }).click();
      await expect(page.getByRole('heading', { level: 1, name: 'Hãy kiểm tra email của bạn' })).toBeVisible();
      texts.push(await page.locator('main').innerText());
    }
    expect(texts[1]).toBe(texts[0]);
    await mailpit.lastEmailTo(known.email, { subjectIncludes: 'Đặt lại mật khẩu' });
    await page.waitForTimeout(1500); // the app has had its chance to (not) send
    expect(await mailpit.hasNoEmailTo(unknown)).toBe(true);
  });

  // Final review I1 and M11: a reset ends the old access token at once and takes the Telegram destination with it; preferences stay.
  test('F03 a reset kills the old access token, the linked chat and an unused link token', async ({ org, seed, mailpit, telegram, uniqueEmail, page }) => {
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f03-reset-tg'), displayName: 'Trần Thu Trang' });
    const { token } = await org.apiLogin(learner);
    const tokenFrom = (deepLink: string) => deepLink.split('start=')[1]!;
    const chat = telegram.uniqueChatId();
    expect(await telegram.deliverUpdate(telegram.startUpdate(chat, tokenFrom((await org.api(token, 'POST', '/api/me/telegram/link')).body.deepLink)))).toBe(200);
    expect((await org.api(token, 'GET', '/api/me/telegram')).body.linked).toBe(true);
    const unused = tokenFrom((await org.api(token, 'POST', '/api/me/telegram/link')).body.deepLink);
    expect((await org.api(token, 'PUT', '/api/me/notification-prefs', { body: { eventType: 'REVIEW_REQUESTED', channel: 'EMAIL', enabled: false } })).status).toBe(204);

    await page.goto('/forgot-password');
    await page.getByLabel(/^Email/).fill(learner.email);
    await page.getByRole('button', { name: 'Gửi liên kết' }).click();
    const mail = await mailpit.lastEmailTo(learner.email, { subjectIncludes: 'Đặt lại mật khẩu' });
    await page.goto(mailpit.linksIn(mail).find((l) => l.includes('/reset-password?token='))!);
    await page.getByLabel(/^Mật khẩu mới/).fill('New-password-for-reset-2');
    await page.getByLabel(/^Nhập lại mật khẩu mới/).fill('New-password-for-reset-2');
    await page.getByRole('button', { name: 'Đặt mật khẩu' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Đã đổi mật khẩu' })).toBeVisible();

    expect((await org.api(token, 'GET', '/api/auth/me')).status, 'the old access token').toBe(401);
    const fresh = await org.apiLogin({ email: learner.email, password: 'New-password-for-reset-2' });
    expect((await org.api(fresh.token, 'GET', '/api/me/telegram')).body.linked, 'the chat is gone').toBe(false);
    const other = telegram.uniqueChatId();
    expect(await telegram.deliverUpdate(telegram.startUpdate(other, unused))).toBe(200);
    await telegram.waitForMessages(other);
    expect((await telegram.messagesFor(other))[0]!.text).toContain('không hợp lệ hoặc đã hết hạn');
    const prefs = (await org.api(fresh.token, 'GET', '/api/me/notification-prefs')).body.events as { type: string; email: boolean }[];
    expect(prefs.find((e) => e.type === 'REVIEW_REQUESTED')!.email, 'preferences stay').toBe(false);
  });
});
