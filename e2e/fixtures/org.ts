import { expect, type Page } from '@playwright/test';
import { env, json } from '../helpers/env';

/** Switches the header context switcher to an organisation by name, or to "Cá nhân" when null. Drives the real control. */
export async function switchContext(page: Page, orgName: string | null): Promise<void> {
  const switcher = page.getByRole('combobox', { name: 'Ngữ cảnh' });
  await switcher.click();
  await page.getByRole('option', { name: orgName ?? 'Cá nhân', exact: true }).click();
  await expect(switcher).toContainText(orgName ?? 'Cá nhân');
}

/** Follows the link of an invitation mail as `person`: sign in from the invitation page, come back to it, press accept. */
export async function acceptInvitation(page: Page, link: string, person: { email: string; password: string }): Promise<void> {
  await page.goto(link);
  await expect(page.getByRole('heading', { level: 1, name: 'Lời mời tham gia English9x' })).toBeVisible();
  await page.locator('#main').getByRole('link', { name: 'Đăng nhập' }).click();
  await page.getByLabel(/^Email/).fill(person.email);
  await page.getByLabel(/^Mật khẩu/).fill(person.password);
  await page.getByRole('button', { name: 'Đăng nhập', exact: true }).click();
  // the sign-in sent us back to the invitation (?next=)
  await page.getByRole('button', { name: 'Chấp nhận lời mời' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Đã tham gia' })).toBeVisible();
}

/** Signs in through the API only (for HTTP assertions next to the UI steps): the access token and the person's id. */
export async function apiLogin(user: { email: string; password: string }): Promise<{ token: string; userId: string }> {
  const { accessToken } = await json<{ accessToken: string }>(`${env.baseURL}/api/auth/login`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(user),
  });
  const me = await json<{ id: string }>(`${env.baseURL}/api/auth/me`, { headers: { authorization: `Bearer ${accessToken}` } });
  return { token: accessToken, userId: me.id };
}

/** A request to the API as a signed-in person, optionally in an organisation context. Resolves to status and parsed body. */
export async function api(
  token: string,
  method: string,
  path: string,
  opts: { org?: string | null; body?: unknown } = {},
): Promise<{ status: number; text: string; body: any }> {
  const { request } = await import('../helpers/env');
  const res = await request(`${env.baseURL}${path}`, {
    method,
    headers: {
      authorization: `Bearer ${token}`,
      'content-type': 'application/json',
      ...(opts.org ? { 'x-org-context': opts.org } : {}),
    },
    body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
  });
  let body: unknown = undefined;
  try { body = res.text ? JSON.parse(res.text) : undefined; } catch { /* not JSON */ }
  return { status: res.status, text: res.text, body };
}
