// F55 — Tuỳ chọn kênh (spec §16.2): "Bật/tắt theo sự kiện × kênh có hiệu lực".
// Since P2.9 the review events are real: a question submitted over the CMS API and decided on the real review screen (see _world.ts).
import { test, expect } from '../../fixtures';
import { poll } from '../../helpers/env';
import { reviewWorld } from './_world';
import { classroomWorld, gradingWorld, ordersWorld, pickOff, planWorld, runChannels, type Fx, type World } from './_events';

test.describe('F55 channel preferences', () => {
  test('F55 switching an event x channel off or on changes what is sent, per event and per channel', async ({ page, browser, seed, auth, telegram, mailpit, uniqueEmail }) => {
    const w = await reviewWorld(seed, uniqueEmail, 'f55', browser);
    // both of the person's events: REVIEW_REQUESTED (as reviewer) and, for the same person, CONTENT_PUBLISHED (as author of a review)
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
    await w.reviewerPublished();
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
    await w.reviewerPublished();
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

const worlds: Array<{ name: string; build: (fx: Fx, label: string) => Promise<World> }> = [
  { name: 'orders', build: ordersWorld },
  { name: 'plan', build: planWorld },
  { name: 'classroom', build: classroomWorld },
];

// The other §13 events: the preference screen has a row for each and it takes effect. Before the world runs, one event is switched off on the real preference
// screen per channel (Email for the first event of a person who has a later one) and ONE for the world's last event (Telegram): the in-app notice arrives, the channel left on delivers it, the channel switched off does not (asserted after a LATER event of that person has
// arrived on that channel). For the orders the choice is then reversed: switched on again, the next rejection reaches that channel.
test.describe('F55 channel preferences: the other spec 13 events', () => {
  for (const w of worlds) {
    test(`F55 ${w.name} events: a channel switched off gets nothing, switched on delivers`, async ({ browser, seed, auth, org, mailpit, telegram, uniqueEmail, request }) => {
      const fx: Fx = { seed, org, uniqueEmail, request };
      const world = await w.build(fx, `f55${w.name}`);
      test.setTimeout(world.timeoutMs);
      const { first } = pickOff(world);
      const all = world.steps.flatMap((st) => st.expects);
      // Telegram off for the LAST event of the world (nothing follows it: settle time only), Email off for the first one that a later event follows
      const lastE = all[all.length - 1]!;
      const off = [{ who: first.who, event: first.event, channel: 'Email' as const }, { who: lastE.who, event: lastE.event, channel: 'Telegram' as const }];
      const r = await runChannels({ fx, world, browser, auth, telegram, mailpit, use: { email: true, telegram: true }, off });
      if (w.name === 'orders') {
        // reversed: ORDER_REJECTED (Telegram was off, nothing arrived) is switched on and raised again with another order
        const step = world.steps[world.steps.length - 1]!;
        const before = await r.count(lastE.who, lastE.event, 'Telegram');
        expect(before).toBe(0);
        await r.setBox(lastE.who, lastE.event, 'Telegram', true);
        await step.fire();
        await poll('ORDER_REJECTED by Telegram once it is switched on', async () => ((await r.count(lastE.who, lastE.event, 'Telegram')) > 0 ? true : null), { timeoutMs: 60_000, intervalMs: 1_000 });
      }
    });
  }

  // The ONE run of the grading events. The fake Gemini lock is taken once for it, so it carries every channel of the four specs: F52 (the screen), F53 (mail),
  // F54 (the chat) and F55 (the preferences): every recipient is linked, every event is asserted on the screen, by mail and in the chat, except the two switched off
  // on the preference screen: Email off for SCORE_PRELIMINARY (SCORE_OFFICIAL follows to the same learner) and Telegram off for GRADING_QUEUE_NEW (GRADING_CLAIM_DUE follows
  // to the same teacher). Their absence is asserted after the later event arrived on that channel.
  test('F55 F52 F53 F54 the grading events reach the screen, the mailbox and the chat, and the channel switched off for an event gets nothing', async ({ browser, seed, auth, org, mailpit, telegram, uniqueEmail, request, gemini }) => {
    const fx: Fx = { seed, org, uniqueEmail, request, gemini };
    const world = await gradingWorld(fx, 'f55grading');
    test.setTimeout(world.timeoutMs);
    const all = world.steps.flatMap((st) => st.expects);
    const find = (event: string) => all.find((e) => e.event === event)!;
    const off = [
      { who: find('SCORE_PRELIMINARY').who, event: 'SCORE_PRELIMINARY' as const, channel: 'Email' as const },
      { who: find('GRADING_QUEUE_NEW').who, event: 'GRADING_QUEUE_NEW' as const, channel: 'Telegram' as const },
    ];
    await runChannels({ fx, world, browser, auth, telegram, mailpit, use: { screen: true, email: true, telegram: true }, off });
  });
});
