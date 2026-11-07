// F55 — Tuỳ chọn kênh (spec §16.2): "Bật/tắt theo sự kiện × kênh có hiệu lực".
//
// !! PARTIAL for the same reason as F52 (the trigger is the seed API standing in for the CMS review screens, P2.9). The preference
// !! screen, the stored preferences, the jobs and the channels (Mailpit, fake Telegram) are real.
import { test, expect } from '../../fixtures';
import { reviewWorld } from './_world';

test.describe('F55 channel preferences', () => {
  test('F55 switching an event x channel off or on changes what is sent, per event and per channel', async ({ page, seed, auth, telegram, mailpit, uniqueEmail }) => {
    const w = await reviewWorld(seed, uniqueEmail, 'f55');
    // both of the person's events: REVIEW_REQUESTED (as reviewer) and, for the same person, CONTENT_PUBLISHED (as author of a review)
    await seed.member({ orgId: w.org.orgId, userId: w.reviewer.userId, roles: [{ role: 'AUTHOR', examCode: 'IELTS' }] });
    const chat = telegram.uniqueChatId();
    await auth.loginAs(page, w.reviewer);
    await page.goto('/notifications/preferences');
    await page.getByRole('button', { name: 'Tạo liên kết kết nối' }).click();
    const token = (await page.getByRole('link', { name: 'Mở trong Telegram' }).getAttribute('href'))!.split('start=')[1]!;
    expect(await telegram.deliverUpdate(telegram.startUpdate(chat, token))).toBe(200);
    await telegram.waitForMessages(chat, { timeoutMs: 20_000 });

    // everything is on by default; in-app is always on and has no control
    const row = (name: string) => page.getByRole('row', { name: new RegExp(name) });
    await expect(row('Có nội dung chờ duyệt').getByText('Luôn bật')).toBeVisible();
    await expect(page.getByRole('checkbox', { name: /^Email: Có nội dung chờ duyệt/ })).toBeChecked();
    await expect(page.getByRole('checkbox', { name: /^Telegram: Có nội dung chờ duyệt/ })).toBeChecked();

    // Telegram off, email on, for REVIEW_REQUESTED only
    await page.getByRole('checkbox', { name: /^Telegram: Có nội dung chờ duyệt/ }).click();
    await expect(page.getByRole('checkbox', { name: /^Telegram: Có nội dung chờ duyệt/ })).not.toBeChecked();
    await page.reload();
    await expect(page.getByRole('checkbox', { name: /^Telegram: Có nội dung chờ duyệt/ })).not.toBeChecked();
    await expect(page.getByRole('checkbox', { name: /^Email: Có nội dung chờ duyệt/ })).toBeChecked();
    await expect(page.getByRole('checkbox', { name: /^Telegram: Nội dung đã được xuất bản/ })).toBeChecked();

    await w.requested();
    await mailpit.lastEmailTo(w.reviewer.email, { subjectIncludes: 'Có nội dung chờ duyệt', timeoutMs: 40_000 });
    // another event, for which Telegram is still on, arrives in the chat. The "no Telegram for the switched-off event" check below is only as
    // strong as the order of arrival: the switched-off event's mail was awaited above, and this later event's message is awaited here,
    // so a wrongly planned Telegram delivery of the earlier event (created in the same transaction as that mail's) would have been sent by now.
    await seed.reviewEvent({ kind: 'approved', authorId: w.reviewer.userId, orgId: w.org.orgId });
    const messages = await telegram.waitForMessages(chat, { count: 2, timeoutMs: 40_000 });
    expect(messages).toHaveLength(2);
    expect(messages[1]!.text).toContain('Nội dung đã được xuất bản');
    expect(messages.some((m) => m.text.includes('Có nội dung chờ duyệt'))).toBe(false);

    // switch it back on: the next REVIEW_REQUESTED reaches Telegram too
    await page.getByRole('checkbox', { name: /^Telegram: Có nội dung chờ duyệt/ }).click();
    await expect(page.getByRole('checkbox', { name: /^Telegram: Có nội dung chờ duyệt/ })).toBeChecked();
    await page.reload();
    await expect(page.getByRole('checkbox', { name: /^Telegram: Có nội dung chờ duyệt/ })).toBeChecked();
    await w.requested();
    const after = await telegram.waitForMessages(chat, { count: 3, timeoutMs: 40_000 });
    expect(after[2]!.text).toContain('Có nội dung chờ duyệt');

    // email off for the other event: the in-app notification still arrives, the mail does not
    await page.getByRole('checkbox', { name: /^Email: Nội dung đã được xuất bản/ }).click();
    await expect(page.getByRole('checkbox', { name: /^Email: Nội dung đã được xuất bản/ })).not.toBeChecked();
    await seed.reviewEvent({ kind: 'approved', authorId: w.reviewer.userId, orgId: w.org.orgId });
    await telegram.waitForMessages(chat, { count: 4, timeoutMs: 40_000 });
    // Before counting the mails, wait for a mail known to come AFTER the one that must not be sent: a later 'requested' event (email on).
    // Its 'Có nội dung chờ duyệt' mail is the third one to this person; once it is there, a wrongly planned 'published' mail of the
    // earlier event has had every chance to arrive before it.
    await w.requested();
    await expect.poll(() => mailpit.countEmailsTo(w.reviewer.email, 'Có nội dung chờ duyệt'), { timeout: 40_000 }).toBe(3);
    // exactly one 'published' mail exists: the one for the approved event before email was switched off; the later approval sent none
    expect(await mailpit.countEmailsTo(w.reviewer.email, 'Nội dung đã được xuất bản')).toBe(1);
  });
});
