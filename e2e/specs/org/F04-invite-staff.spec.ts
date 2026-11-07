// F04 — Mời tài khoản nội bộ (spec §16.2): "SYS_ADMIN mời AUTHOR qua email → nhận lời mời → có đúng quyền".
// Through the real screens: the SYS_ADMIN's staff-invitation form, the real mail (Mailpit), the invitation page, the real login.
// "Có đúng quyền" is checked where a person can see it (the Biên soạn area opens for an AUTHOR, the admin area stays closed) and
// in the grants the server reports for that person (exactly AUTHOR for IELTS, nothing for TOEIC).
import { test, expect } from '../../fixtures';
import { agreeToGoogleConsent, chooseGoogleAccount } from '../../helpers/google';

async function openStaffInvitations(page: import('@playwright/test').Page) {
  await page.getByRole('navigation', { name: 'Điều hướng chính' }).getByRole('link', { name: 'Quản trị' }).click();
  await page.getByRole('navigation', { name: 'Điều hướng khu vực' }).getByRole('link', { name: 'Tài khoản nội bộ' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Tài khoản nội bộ' })).toBeVisible();
}

test.describe('F04 invite internal staff', () => {
  test('F04 a SYS_ADMIN invites an AUTHOR by email; accepting grants exactly that role', async ({ page, browser, seed, auth, org, mailpit, uniqueEmail }) => {
    const admin = await seed.sysAdmin({ email: uniqueEmail('f04-admin') });
    const invitee = await seed.verifiedLearner({ email: uniqueEmail('f04-author'), displayName: 'Trần Biên Soạn' });

    // the SYS_ADMIN invites through the form
    await auth.loginAs(page, admin);
    await openStaffInvitations(page);
    await page.getByLabel(/^Email/).fill(invitee.email);
    await page.getByRole('checkbox', { name: 'Biên soạn · IELTS' }).check();
    await page.getByRole('button', { name: 'Gửi lời mời' }).click();
    await expect(page.getByText(`Đã gửi lời mời tới ${invitee.email}.`)).toBeVisible();
    const row = page.getByRole('row', { name: new RegExp(invitee.email) });
    await expect(row).toContainText('Đang chờ');
    await expect(row).toContainText('Biên soạn · IELTS');

    // the mail: Vietnamese, from the configured public URL, one-time link, no secret but the link itself
    const mail = await mailpit.lastEmailTo(invitee.email, { subjectIncludes: 'Lời mời tham gia English9x' });
    const link = mailpit.linksIn(mail).find((l) => l.includes('/accept-invitation?token='))!;
    expect(link.startsWith('https://localhost/accept-invitation?token=')).toBe(true);
    expect(mail.Text).toContain('Biên soạn IELTS');

    // before accepting, the person has no such role
    const before = await org.apiLogin(invitee);
    expect((await org.api(before.token, 'GET', '/api/me/context')).body.grants).toEqual([]);

    // the invited person follows the link in their own browser, signs in from it and accepts
    const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    const person = await ctx.newPage();
    await org.acceptInvitation(person, link, invitee);

    // exactly the invited role: AUTHOR for IELTS, nothing for TOEIC, no organisation
    const grants = (await org.api(before.token, 'GET', '/api/me/context')).body.grants;
    expect(grants).toEqual([{ role: 'AUTHOR', orgId: null, examCode: 'IELTS' }]);
    expect(await seed.authz({ userId: invitee.userId, role: 'AUTHOR', examCode: 'IELTS' })).toMatchObject({ allowed: true });
    expect(await seed.authz({ userId: invitee.userId, role: 'AUTHOR', examCode: 'TOEIC' })).toMatchObject({ allowed: false });

    // what the person sees: the content area is open to them, the admin area is not
    await person.getByRole('link', { name: 'Về trang chủ' }).click();
    const nav = person.getByRole('navigation', { name: 'Điều hướng chính' });
    await expect(nav.getByRole('link', { name: 'Biên soạn' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Quản trị' })).toHaveCount(0);
    await nav.getByRole('link', { name: 'Biên soạn' }).click();
    await expect(person.getByRole('heading', { level: 1, name: 'Chưa có việc cần làm' })).toBeVisible();
    await person.goto('/admin/staff');
    await expect(person.getByRole('heading', { level: 1, name: 'Bạn chưa có quyền xem trang này' })).toBeVisible();
    // and the server agrees with the screens: the staff endpoints are closed to them
    expect((await org.api(before.token, 'POST', '/api/admin/invitations', { body: { email: 'x@example.test', roles: [{ role: 'AUTHOR', examCode: 'IELTS' }] } })).status).toBe(403);
    await ctx.close();

    // the SYS_ADMIN sees it accepted
    await page.reload();
    await expect(page.getByRole('row', { name: new RegExp(invitee.email) })).toContainText('Đã chấp nhận');
  });

  async function inviteAuthor(page: import('@playwright/test').Page, email: string, seed: any, auth: any, mailpit: any, uniqueEmail: (l: string) => string, label: string): Promise<string> {
    const admin = await seed.sysAdmin({ email: uniqueEmail(label) });
    await auth.loginAs(page, admin);
    await openStaffInvitations(page);
    await page.getByLabel(/^Email/).fill(email);
    await page.getByRole('checkbox', { name: 'Biên soạn · IELTS' }).check();
    await page.getByRole('button', { name: 'Gửi lời mời' }).click();
    await expect(page.getByText(`Đã gửi lời mời tới ${email}.`)).toBeVisible();
    const mail = await mailpit.lastEmailTo(email, { subjectIncludes: 'Lời mời' });
    return mailpit.linksIn(mail).find((l: string) => l.includes('/accept-invitation?token='))!;
  }

  test('F04 a new person registers from the invitation, verifies the mailbox and comes back to it without reopening the mail', async ({ page, browser, seed, auth, mailpit, uniqueEmail }) => {
    const email = uniqueEmail('f04c-new');
    const password = 'Correct-horse-1';
    const link = await inviteAuthor(page, email, seed, auth, mailpit, uniqueEmail, 'f04c-admin');
    expect(await seed.userInfo(email)).toMatchObject({ count: 0 });

    // the invitation page is the only thing the person ever opens from the invitation mail
    const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    const tab = await ctx.newPage();
    await tab.goto(link);
    await tab.locator('#main').getByRole('link', { name: 'Tạo tài khoản' }).click();
    await tab.getByLabel(/^Tên hiển thị/).fill('Võ Người Mới');
    await tab.getByLabel(/^Email/).fill(email);
    await tab.getByLabel(/^Mật khẩu/).fill(password);
    await tab.getByRole('checkbox').check();
    await tab.getByRole('button', { name: 'Đăng ký', exact: true }).click();
    await expect(tab.getByRole('heading', { level: 1, name: 'Hãy kiểm tra email của bạn' })).toBeVisible();

    // the verification mail opens in a new tab, as a mail client would open it
    const verifyMail = await mailpit.lastEmailTo(email, { subjectIncludes: 'Xác thực' });
    const verifyLink = mailpit.linksIn(verifyMail).find((l: string) => l.includes('/verify-email?token='))!;
    const fromMail = await ctx.newPage();
    await fromMail.goto(verifyLink);
    await expect(fromMail.getByRole('heading', { level: 1, name: 'Email đã được xác thực' })).toBeVisible();
    await fromMail.locator('#main').getByRole('link', { name: 'Đăng nhập' }).click();
    await fromMail.getByLabel(/^Email/).fill(email);
    await fromMail.getByLabel(/^Mật khẩu/).fill(password);
    await fromMail.getByRole('button', { name: 'Đăng nhập', exact: true }).click();

    // back at the invitation, by itself
    await expect(fromMail).toHaveURL(/\/accept-invitation\?token=/);
    expect(fromMail.url()).toBe(link);
    await fromMail.getByRole('button', { name: 'Chấp nhận lời mời' }).click();
    await expect(fromMail.getByRole('heading', { level: 1, name: 'Đã tham gia' })).toBeVisible();
    const userId = (await seed.userInfo(email)).userId!;
    expect(await seed.authz({ userId, role: 'AUTHOR', examCode: 'IELTS' })).toMatchObject({ allowed: true });
    // nothing of the invitation stays behind in this browser
    expect(await fromMail.evaluate(() => localStorage.getItem('e9x.auth.returnTo'))).toBeNull();
    await ctx.close();
  });

  test('F04 a new person using Google from the invitation comes back to it', async ({ page, browser, seed, auth, mailpit, uniqueEmail }) => {
    const email = uniqueEmail('f04d-google');
    const link = await inviteAuthor(page, email, seed, auth, mailpit, uniqueEmail, 'f04d-admin');
    const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    const tab = await ctx.newPage();
    await tab.goto(link);
    await tab.locator('#main').getByRole('link', { name: 'Tạo tài khoản' }).click();
    await tab.getByRole('link', { name: 'Đăng nhập bằng Google' }).click();
    await chooseGoogleAccount(tab, { email, name: 'Đỗ Dùng Google' });
    await agreeToGoogleConsent(tab);
    await expect(tab).toHaveURL(/\/accept-invitation\?token=/);
    await tab.getByRole('button', { name: 'Chấp nhận lời mời' }).click();
    await expect(tab.getByRole('heading', { level: 1, name: 'Đã tham gia' })).toBeVisible();
    const userId = (await seed.userInfo(email)).userId!;
    expect(await seed.authz({ userId, role: 'AUTHOR', examCode: 'IELTS' })).toMatchObject({ allowed: true });
    await ctx.close();
  });

  test('F04 the link works once, and only for the invited address', async ({ page, browser, seed, auth, org, mailpit, uniqueEmail }) => {
    const admin = await seed.sysAdmin({ email: uniqueEmail('f04b-admin') });
    const invitee = await seed.verifiedLearner({ email: uniqueEmail('f04b-invitee') });
    const other = await seed.verifiedLearner({ email: uniqueEmail('f04b-other'), displayName: 'Người Khác' });
    await auth.loginAs(page, admin);
    await openStaffInvitations(page);
    await page.getByLabel(/^Email/).fill(invitee.email);
    await page.getByRole('checkbox', { name: 'Người duyệt · TOEIC' }).check();
    await page.getByRole('button', { name: 'Gửi lời mời' }).click();
    await expect(page.getByText(`Đã gửi lời mời tới ${invitee.email}.`)).toBeVisible();
    const link = mailpit.linksIn(await mailpit.lastEmailTo(invitee.email, { subjectIncludes: 'Lời mời' })).find((l) => l.includes('/accept-invitation?token='))!;

    // somebody else holding the link (it was forwarded) is told so, gets nothing, and does not spend it
    const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    const stranger = await ctx.newPage();
    await stranger.goto(link);
    await stranger.locator('#main').getByRole('link', { name: 'Đăng nhập' }).click();
    await stranger.getByLabel(/^Email/).fill(other.email);
    await stranger.getByLabel(/^Mật khẩu/).fill(other.password);
    await stranger.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
    await stranger.getByRole('button', { name: 'Chấp nhận lời mời' }).click();
    await expect(stranger.getByRole('alert')).toContainText('Lời mời này gửi cho một địa chỉ email khác');
    await expect(stranger.getByRole('alert')).not.toContainText(invitee.email);
    const strangerToken = (await org.apiLogin(other)).token;
    expect((await org.api(strangerToken, 'GET', '/api/me/context')).body.grants).toEqual([]);
    await ctx.close();

    // the invited person can still use it, once
    const ctx2 = await browser.newContext({ ignoreHTTPSErrors: true });
    const person = await ctx2.newPage();
    await org.acceptInvitation(person, link, invitee);
    await person.goto(link);
    await person.getByRole('button', { name: 'Chấp nhận lời mời' }).click();
    await expect(person.getByRole('alert')).toContainText('Lời mời không hợp lệ, đã được dùng, đã bị thu hồi hoặc đã hết hạn.');
    const token = (await org.apiLogin(invitee)).token;
    expect((await org.api(token, 'GET', '/api/me/context')).body.grants).toEqual([{ role: 'REVIEWER', orgId: null, examCode: 'TOEIC' }]);
    await ctx2.close();
  });

  test('F04 a revoked invitation no longer works and the form refuses a bad address', async ({ page, browser, seed, auth, org, mailpit, uniqueEmail }) => {
    const admin = await seed.sysAdmin({ email: uniqueEmail('f04c-admin') });
    const invitee = await seed.verifiedLearner({ email: uniqueEmail('f04c-invitee') });
    await auth.loginAs(page, admin);
    await openStaffInvitations(page);
    // the form checks in the browser
    await page.getByLabel(/^Email/).fill('khong-phai-email');
    await page.getByRole('button', { name: 'Gửi lời mời' }).click();
    await expect(page.getByText('Địa chỉ email chưa hợp lệ.')).toBeVisible();
    await expect(page.getByText('Hãy chọn ít nhất một vai trò.')).toBeVisible();
    // invite, then change your mind
    await page.getByLabel(/^Email/).fill(invitee.email);
    await page.getByRole('checkbox', { name: 'Giáo viên · IELTS' }).check();
    await page.getByRole('button', { name: 'Gửi lời mời' }).click();
    await expect(page.getByText(`Đã gửi lời mời tới ${invitee.email}.`)).toBeVisible();
    const link = mailpit.linksIn(await mailpit.lastEmailTo(invitee.email, { subjectIncludes: 'Lời mời' })).find((l) => l.includes('/accept-invitation?token='))!;
    await page.getByRole('button', { name: `Thu hồi lời mời gửi ${invitee.email}` }).click();
    await expect(page.getByRole('row', { name: new RegExp(invitee.email) })).toContainText('Đã thu hồi');

    const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    const person = await ctx.newPage();
    await person.goto(link);
    await person.locator('#main').getByRole('link', { name: 'Đăng nhập' }).click();
    await person.getByLabel(/^Email/).fill(invitee.email);
    await person.getByLabel(/^Mật khẩu/).fill(invitee.password);
    await person.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
    await person.getByRole('button', { name: 'Chấp nhận lời mời' }).click();
    await expect(person.getByRole('alert')).toContainText('Lời mời không hợp lệ');
    expect((await org.api((await org.apiLogin(invitee)).token, 'GET', '/api/me/context')).body.grants).toEqual([]);
    await ctx.close();
  });
});
