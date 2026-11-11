// F54 — Telegram (spec §16.2): "Liên kết bot bằng token → fake-telegram nhận tin đúng sự kiện; token dùng lại bị từ chối".
// Since P2.9 the review events are real: a question submitted over the CMS API and decided on the real review screen (see _world.ts).
import { test, expect } from '../../fixtures';
import { reviewWorld } from './_world';

test.describe('F54 Telegram notifications', () => {
  test('F54 link with a one-time token, get the event in the chat, a reused token is refused', async ({ page, browser, seed, auth, telegram, uniqueEmail }) => {
    const w = await reviewWorld(seed, uniqueEmail, 'f54', browser);
    await auth.loginAs(page, w.reviewer);
    await page.goto('/notifications/preferences');
    await expect(page.getByText('Chưa kết nối Telegram')).toBeVisible();

    await page.getByRole('button', { name: 'Tạo liên kết kết nối' }).click();
    const open = page.getByRole('link', { name: 'Mở trong Telegram' });
    await expect(open).toBeVisible();
    const href = (await open.getAttribute('href'))!;
    expect(href).toMatch(/^https:\/\/t\.me\/e9x_e2e_bot\?start=[A-Za-z0-9_-]{43}$/);
    const token = href.split('start=')[1]!;

    // Telegram's side: nothing links without the secret header, or with a wrong one
    const chat = telegram.uniqueChatId();
    expect(await telegram.deliverUpdate(telegram.startUpdate(chat, token), null)).toBe(401);
    expect(await telegram.deliverUpdate(telegram.startUpdate(chat, token), 'not-the-secret')).toBe(401);
    await page.getByRole('button', { name: 'Kiểm tra' }).click();
    await expect(page.getByText('Chưa kết nối Telegram')).toBeVisible();

    // the real update links the chat; the bot confirms in the chat; the screen says so
    expect(await telegram.deliverUpdate(telegram.startUpdate(chat, token))).toBe(200);
    const [hello] = await telegram.waitForMessages(chat, { timeoutMs: 20_000 });
    expect(hello!.text).toContain('Đã liên kết Telegram');
    await page.getByRole('button', { name: 'Kiểm tra' }).click();
    await expect(page.getByText('Đã kết nối Telegram')).toBeVisible();
    await page.reload();
    await expect(page.getByText('Đã kết nối Telegram')).toBeVisible();

    // an event reaches the chat, with the right text, escaped for Telegram
    await w.requested();
    const messages = await telegram.waitForMessages(chat, { count: 2, timeoutMs: 40_000 });
    expect(messages[1]!.text).toContain('<b>Có nội dung chờ duyệt</b>');
    expect(messages[1]!.text).toContain('Một nội dung (question) của kỳ thi IELTS đang chờ bạn duyệt.');
    expect(messages[1]!.text).toContain('href="https://localhost/notifications"');

    // the token is single-use: replayed from another chat it links nothing and answers like any bad token
    const intruder = telegram.uniqueChatId();
    expect(await telegram.deliverUpdate(telegram.startUpdate(intruder, token))).toBe(200);
    const [refusal] = await telegram.waitForMessages(intruder, { timeoutMs: 20_000 });
    expect(refusal!.text).toContain('không hợp lệ hoặc đã hết hạn');
    const stranger = telegram.uniqueChatId();
    await telegram.deliverUpdate(telegram.startUpdate(stranger, 'A'.repeat(43)));
    const [alike] = await telegram.waitForMessages(stranger, { timeoutMs: 20_000 });
    expect(alike!.text).toBe(refusal!.text);

    // the account is still linked to the first chat only: the next event goes there, not to the intruder
    await w.requested();
    await telegram.waitForMessages(chat, { count: 3, timeoutMs: 40_000 });
    expect(await telegram.messagesFor(intruder)).toHaveLength(1);

    // disconnecting stops it
    await page.getByRole('button', { name: 'Huỷ kết nối' }).click();
    await expect(page.getByText('Chưa kết nối Telegram')).toBeVisible();
  });
});
