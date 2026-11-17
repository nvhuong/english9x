// F01 — Đăng ký email + xác thực (spec §16.2): "Đăng ký → email trong mailpit → bấm link → đăng nhập được; chưa xác thực
// thì không mua được gói". Everything below goes through the real UI (the register / verify / login screens) behind
// Caddy, with the real SMTP path into Mailpit.
//
// The "chưa xác thực thì không mua được gói" half runs on the real checkout screen: an unverified learner (registered on the
// register screen) opens a plan, tries to pay and is refused ON THE SCREEN with the email-not-verified message and no order;
// the Mailpit link verifies the address; the same action on the same screen then creates the order and the QR is shown.
import { test, expect } from '../../fixtures';
import { env, json } from '../../helpers/env';

const PASSWORD = 'Correct-horse-1';

async function userIdOf(email: string, password: string): Promise<string> {
  const post = (path: string, body: unknown, token?: string) =>
    json<Record<string, string>>(`${env.baseURL}${path}`, {
      method: 'POST', headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body),
    });
  const { accessToken } = await post('/api/auth/login', { email, password });
  const me = await json<{ id: string }>(`${env.baseURL}/api/auth/me`, { headers: { authorization: `Bearer ${accessToken}` } });
  return me.id;
}

test.describe('F01 register and verify', () => {
  test('F01 register, open the emailed link, sign in; before that the plan cannot be bought', async ({ page, auth, mailpit, seed, org, uniqueEmail }) => {
    const email = uniqueEmail('f01');
    const { verifyLink } = await auth.registerLearner(page, { email, password: PASSWORD, displayName: 'Nguyễn Thị An' });

    // the mail is in Vietnamese, from the configured public URL, and carries no password
    const mail = await mailpit.lastEmailTo(email, { subjectIncludes: 'Xác thực' });
    expect(verifyLink.startsWith(`${env.baseURL}/verify-email?token=`)).toBe(true);
    expect(mail.Text).not.toContain(PASSWORD);

    // the account exists but is unverified: signing in works, the banner says what is missing
    await auth.loginAs(page, { email, password: PASSWORD });
    const banner = page.getByTestId('verify-email-banner');
    await expect(banner).toContainText('chưa được xác thực');
    await expect(page.getByText('Đã đăng nhập: Nguyễn Thị An')).toBeVisible();
    const userId = await userIdOf(email, PASSWORD);
    expect(await seed.requireVerified(userId), 'checkout guard before verification').toBe(403);
    expect(await seed.userInfo(email)).toMatchObject({ count: 1, emailVerified: false });

    // the checkout SCREEN: choose a plan and a course, pay; refused on the screen, no order exists
    const suffix = Date.now().toString(36);
    const admin = await seed.sysAdmin({ email: `f01-admin-${suffix}@e2e.english9x.test`, displayName: 'Quản Trị F01' });
    const a = await org.apiLogin(admin);
    const course = await seed.billing.course();
    const planName = `Gói F01 ${suffix}`;
    const planRes = await org.api(a.token, 'POST', '/api/admin/plans', { body: { name: planName, priceVnd: 299000, months: 1, maxCourses: 1, active: true } });
    expect(planRes.status).toBe(201);
    const planId: string = planRes.body.id;
    expect((await org.api(a.token, 'PUT', '/api/admin/payment-account', { body: { bankBin: '970436', accountNo: `8${Date.now().toString().slice(-9)}`, accountName: 'CONG TY ENGLISH9X' } })).status, 'activate a receiving account').toBe(200);
    await page.goto('/checkout');
    await page.getByRole('radio', { name: new RegExp(planName) }).check();
    await page.getByRole('checkbox', { name: new RegExp(course.code) }).check();
    const pay = page.getByRole('button', { name: 'Đặt đơn và thanh toán' });
    const refused = page.waitForResponse((r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/orders');
    await pay.click();
    const refusal = await refused;
    expect(refusal.status()).toBe(403);
    expect((await refusal.json()).type).toBe('urn:e9x:problem:email-not-verified');
    await expect(page.getByRole('alert').filter({ hasText: 'Hãy xác thực email trước khi mua gói.' })).toBeVisible();
    await expect(page).toHaveURL(/\/checkout$/);
    await expect(page.getByRole('checkbox', { name: new RegExp(course.code) })).toBeChecked();
    const learnerApi = await org.apiLogin({ email, password: PASSWORD });
    const none = await org.api(learnerApi.token, 'GET', '/api/orders');
    expect(none.status).toBe(200);
    expect(none.body, 'no order exists after the refusal').toHaveLength(0);

    // open the link (in the same browser): verified, and the banner goes away without signing in again
    const second = await page.context().newPage();
    await second.goto(verifyLink);
    await expect(second.getByRole('heading', { level: 1, name: 'Email đã được xác thực' })).toBeVisible();
    expect(second.url(), 'the token is taken out of the address bar').not.toContain('token=');
    await second.close();
    expect(await seed.requireVerified(userId), 'checkout guard after verification').toBe(200);
    expect(await seed.userInfo(email)).toMatchObject({ count: 1, emailVerified: true });

    // the same action on the same screen now creates the order, and the QR is shown
    await expect(page.getByRole('checkbox', { name: new RegExp(course.code) }), 'the kept choice is still there').toBeChecked();
    const placed = page.waitForResponse((r) => r.request().method() === 'POST' && new URL(r.url()).pathname === '/api/orders');
    await pay.click();
    const created = await placed;
    expect(created.status()).toBe(201);
    const createdBody = await created.json();
    expect(createdBody.amountVnd).toBe(299000);
    await expect(page).toHaveURL(/\/orders\/[A-Z0-9]{6}$/);
    const code = page.url().split('/').pop()!;
    expect(createdBody.orderCode).toBe(code);
    const order = await org.api(learnerApi.token, 'GET', `/api/orders/${code}`);
    expect(order.status).toBe(200);
    expect(order.body.planId).toBe(planId);
    expect(order.body.courses.map((c: { code: string }) => c.code)).toEqual([course.code]);
    expect(order.body.status).toBe('PENDING_PAYMENT');
    await expect(page.getByRole('img', { name: `Mã QR thanh toán đơn ${code}` })).toBeVisible();

    // the link works once
    await page.goto(verifyLink);
    await expect(page.getByRole('heading', { level: 1, name: 'Không xác thực được' })).toBeVisible();
  });

  test('F01 a verified person signs in after a fresh start and signs out', async ({ page, auth, seed, uniqueEmail }) => {
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f01-login'), displayName: 'Trần Văn Bình' });
    await auth.loginAs(page, learner);
    await expect(page.getByTestId('verify-email-banner')).toHaveCount(0);
    await page.reload(); // the access token lived in memory only; the session is restored from the refresh token
    await expect(page.getByText('Đã đăng nhập: Trần Văn Bình')).toBeVisible();
    await page.getByRole('button', { name: 'Đăng xuất' }).click();
    await expect(page.getByRole('link', { name: 'Đăng nhập' }).first()).toBeVisible();
    await page.reload();
    await expect(page.getByRole('link', { name: 'Đăng nhập' }).first()).toBeVisible();
    expect(await page.evaluate(() => localStorage.getItem('e9x.auth.refresh'))).toBeNull();
  });

  test('F01 wrong password and unknown address look exactly alike', async ({ page, seed, uniqueEmail }) => {
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f01-wrong') });
    const messages: string[] = [];
    for (const email of [learner.email, uniqueEmail('f01-nobody')]) {
      await page.goto('/login');
      await page.getByLabel(/^Email/).fill(email);
      await page.getByLabel(/^Mật khẩu/).fill('Wrong-password-9');
      await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
      messages.push(await page.getByRole('alert').innerText());
    }
    expect(messages[0]).toBe('Email hoặc mật khẩu chưa đúng.');
    expect(messages[1]).toBe(messages[0]);
  });

  test('F01 consent is required and the password rules are explained', async ({ page, uniqueEmail }) => {
    await page.goto('/register');
    await page.getByLabel(/^Tên hiển thị/).fill('An');
    await page.getByLabel(/^Email/).fill(uniqueEmail('f01-consent'));
    await page.getByLabel(/^Mật khẩu/).fill('short');
    await page.getByRole('button', { name: 'Đăng ký', exact: true }).click();
    await expect(page.getByText('Mật khẩu cần ít nhất 8 ký tự.')).toBeVisible();
    await expect(page.getByText('Bạn cần đồng ý để tạo tài khoản.')).toBeVisible();
    await expect(page.getByRole('heading', { level: 1, name: 'Tạo tài khoản' })).toBeVisible();
  });

  test('F01 registering an address that already has an account looks the same and tells the owner, with no link for the sender', async ({ page, auth, mailpit, uniqueEmail }) => {
    const email = uniqueEmail('f01-dup');
    const { verifyLink } = await auth.registerLearner(page, { email, password: PASSWORD });
    // someone else tries the same address with their own password: the same "check your email" screen
    await page.goto('/register');
    await page.getByLabel(/^Tên hiển thị/).fill('Kẻ giả mạo');
    await page.getByLabel(/^Email/).fill(email);
    await page.getByLabel(/^Mật khẩu/).fill('Attacker-pass-9');
    await page.getByRole('checkbox').check();
    await page.getByRole('button', { name: 'Đăng ký', exact: true }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Hãy kiểm tra email của bạn' })).toBeVisible();
    // the owner is informed; that mail has no verification token
    const info = await mailpit.lastEmailTo(email, { subjectIncludes: 'Có người vừa đăng ký' });
    expect(info.Text).not.toContain('token=');
    // the owner's original link and password are untouched
    await page.goto(verifyLink);
    await expect(page.getByRole('heading', { level: 1, name: 'Email đã được xác thực' })).toBeVisible();
    await page.goto('/login');
    await page.getByLabel(/^Email/).fill(email);
    await page.getByLabel(/^Mật khẩu/).fill('Attacker-pass-9');
    await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('Email hoặc mật khẩu chưa đúng.');
  });

  test('F01 a broken link offers a new one; the new link verifies and the older one is retired', async ({ page, auth, uniqueEmail }) => {
    const email = uniqueEmail('f01-resend');
    const { verifyLink: oldLink } = await auth.registerLearner(page, { email, password: PASSWORD });
    await page.goto('/verify-email?token=khong-hop-le');
    await expect(page.getByRole('heading', { level: 1, name: 'Không xác thực được' })).toBeVisible();
    await page.getByLabel(/^Email/).fill(email);
    await page.getByRole('button', { name: 'Gửi liên kết mới' }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Nếu tài khoản này còn chờ xác thực' })).toBeVisible();
    // two verification mails now (Mailpit lists the newest first); the newest carries the only live link
    const links = async () => {
      const found = await json<{ messages: Array<{ ID: string }> }>(
        `${env.mailpitURL}/api/v1/search?query=${encodeURIComponent(`to:"${email}" subject:"Xác thực"`)}`);
      const out: string[] = [];
      for (const m of found.messages) {
        const full = await json<{ Text: string }>(`${env.mailpitURL}/api/v1/message/${m.ID}`);
        out.push(new RegExp(`${env.baseURL.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}/verify-email\\?token=[\\w-]+`).exec(full.Text)![0]);
      }
      return out;
    };
    await expect.poll(async () => (await links()).length).toBe(2);
    const [newLink] = await links();
    expect(newLink).not.toBe(oldLink);
    await page.goto(oldLink!);
    await expect(page.getByRole('heading', { level: 1, name: 'Không xác thực được' })).toBeVisible();
    await page.goto(newLink!);
    await expect(page.getByRole('heading', { level: 1, name: 'Email đã được xác thực' })).toBeVisible();
  });
});
