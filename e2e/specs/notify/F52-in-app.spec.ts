// F52 — Thông báo trong app (spec §16.2): "Sự kiện tạo thông báo, đánh dấu đã đọc".
// The other §13 events (orders, plans, scores, grading queue, assignments, sessions, failed AI grading) are raised by real triggers in _events.ts and asserted on the
// recipient's real screen below. Since P2.9 the review events are real: a question submitted over the CMS API and decided on the real review screen (see _world.ts).
import { test, expect } from '../../fixtures';
import { reviewWorld } from './_world';
import { EVENTS, classroomWorld, drive, gradingWorld, ordersWorld, planWorld, type Fx, type World } from './_events';

test.describe('F52 in-app notifications', () => {
  test('F52 an event creates a notification, the bell counts it, it can be marked read, it is the same in every context and private', async ({ page, browser, seed, auth, org, uniqueEmail }) => {
    const w = await reviewWorld(seed, uniqueEmail, 'f52', browser);
    await w.requested();

    await auth.loginAs(page, w.reviewer);
    // the notification is stored after the event's transaction committed: reload until the bell shows it
    const bell = page.getByRole('link', { name: 'Thông báo, 1 chưa đọc' });
    await expect(async () => {
      await page.reload();
      await expect(bell).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 40_000 });

    await bell.click();
    await expect(page).toHaveURL(/\/notifications$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Trung tâm thông báo' })).toBeVisible();
    const item = page.getByRole('listitem').filter({ hasText: 'Có nội dung chờ duyệt' });
    await expect(item).toHaveCount(1);
    await expect(item).toContainText('Một nội dung (question) của kỳ thi IELTS đang chờ bạn duyệt.');
    await expect(item).toContainText('Chưa đọc');
    await expect(page.getByRole('status').filter({ hasText: '1 chưa đọc' })).toBeVisible();

    // the person has one inbox, whatever organisation they are looking at
    await org.switchContext(page, w.org.name);
    await expect(page.getByRole('listitem').filter({ hasText: 'Có nội dung chờ duyệt' })).toHaveCount(1);
    await org.switchContext(page, null);
    await expect(page.getByRole('listitem').filter({ hasText: 'Có nội dung chờ duyệt' })).toHaveCount(1);

    // the same notification in English when the viewer switches language
    await page.getByRole('group', { name: 'Ngôn ngữ' }).first().getByRole('button', { name: /EN/ }).click();
    await expect(page.getByRole('heading', { level: 2, name: 'Content is waiting for review' })).toBeVisible();
    await page.getByRole('group', { name: 'Language' }).first().getByRole('button', { name: /VI/ }).click();

    // mark as read: the badge, the count and the bell follow, and it stays read after a reload
    await page.getByRole('button', { name: /Đánh dấu đã đọc: Có nội dung chờ duyệt/ }).click();
    await expect(page.getByRole('status').filter({ hasText: 'Bạn đã đọc hết' })).toBeVisible();
    await expect(item).toContainText('Đã đọc');
    await expect(page.getByRole('link', { name: 'Thông báo, không có thông báo chưa đọc' })).toBeVisible();
    await page.reload();
    await expect(page.getByRole('listitem').filter({ hasText: 'Có nội dung chờ duyệt' })).toContainText('Đã đọc');

    // privacy: the author sees nothing of the reviewer's, and cannot mark it either
    const mine = await org.apiLogin(w.reviewer);
    const list = await org.api(mine.token, 'GET', '/api/me/notifications');
    const notificationId = list.body.items[0].id as string;
    const author = await org.apiLogin(w.author);
    expect((await org.api(author.token, 'GET', '/api/me/notifications')).body.items).toHaveLength(0);
    expect((await org.api(author.token, 'POST', `/api/me/notifications/${notificationId}/read`)).status).toBe(404);
    expect((await org.api(author.token, 'GET', '/api/me/notifications', { org: w.org.orgId })).body.items).toHaveLength(0);

    // the decision events reach the author
    await w.approved();
    const authorPage = await (await browser.newContext({ ignoreHTTPSErrors: true })).newPage();
    await auth.loginAs(authorPage, w.author);
    await authorPage.goto('/notifications');
    await expect(async () => {
      await authorPage.reload();
      await expect(authorPage.getByRole('heading', { level: 2, name: 'Nội dung đã được xuất bản' })).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 40_000 });
    await expect(authorPage.getByRole('listitem').filter({ hasText: 'Có nội dung chờ duyệt' })).toHaveCount(0);

    // mark all as read
    await w.changes();
    await expect(async () => {
      await authorPage.reload();
      await expect(authorPage.getByRole('heading', { level: 2, name: 'Nội dung bị yêu cầu sửa' })).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 40_000 });
    await authorPage.getByRole('button', { name: 'Đánh dấu tất cả đã đọc' }).click();
    await expect(authorPage.getByRole('status').filter({ hasText: 'Bạn đã đọc hết' })).toBeVisible();
  });

  test('F52 a signed-out visitor is asked to sign in', async ({ page }) => {
    await page.goto('/notifications');
    await expect(page.getByRole('heading', { level: 1, name: 'Trung tâm thông báo' })).toBeVisible();
    await expect(page.getByText('Vui lòng đăng nhập để xem thông báo.')).toBeVisible();
    await expect(page.getByRole('link', { name: /^Thông báo, / })).toHaveCount(0);
  });
});

const worlds: Array<{ name: string; build: (fx: Fx, label: string) => Promise<World>; gemini?: boolean }> = [
  { name: 'orders', build: ordersWorld },
  { name: 'plan', build: planWorld },
  { name: 'classroom', build: classroomWorld },
  { name: 'grading', build: gradingWorld, gemini: true },
];

test.describe('F52 every other spec 13 event reaches the recipient\'s notification screen', () => {
  for (const w of worlds.filter((x) => !x.gemini)) {
    test(`F52 ${w.name} events are listed on the recipient's screen`, async ({ browser, seed, auth, org, uniqueEmail, request }) => {
      const world = await w.build({ seed, org, uniqueEmail, request }, `f52${w.name}`);
      test.setTimeout(world.timeoutMs);
      await screens(browser, auth, org, world);
    });
  }

  test('F52 grading events (queue, preliminary and official score, claim due, failed AI grading) are listed on the recipient\'s screen', async ({ browser, seed, auth, org, uniqueEmail, request, gemini }) => {
    const world = await gradingWorld({ seed, org, uniqueEmail, request, gemini }, 'f52grading');
    test.setTimeout(world.timeoutMs);
    await screens(browser, auth, org, world);
  });
});

async function screens(browser: import('@playwright/test').Browser, auth: any, org: Fx['org'], world: World): Promise<void> {
  const pages = new Map<string, import('@playwright/test').Page>();
  const pageOf = async (who: World['people'][number]) => {
    let page = pages.get(who.userId);
    if (!page) {
      page = await (await browser.newContext({ ignoreHTTPSErrors: true })).newPage();
      await auth.loginAs(page, who);
      pages.set(who.userId, page);
    }
    return page;
  };
  await drive(org, world, async (e) => {
    const page = await pageOf(e.who);
    await page.goto('/notifications');
    const heading = page.getByRole('heading', { level: 2, name: EVENTS[e.event].title }).first();
    await expect(async () => {
      await page.reload();
      await expect(heading).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 40_000 });
    const item = page.getByRole('listitem').filter({ has: heading }).first();
    await expect(item).toContainText(EVENTS[e.event].body);
    await expect(item).toContainText('Chưa đọc');
    await expect(page.getByRole('link', { name: /^Thông báo, \d+ chưa đọc$/ })).toBeVisible();
    await item.getByRole('button', { name: new RegExp(`Đánh dấu đã đọc: ${EVENTS[e.event].title}`) }).click();
    await expect(item).toContainText('Đã đọc');
  });
}
