// F55 — Tuỳ chọn kênh (spec §16.2): "Bật/tắt theo sự kiện × kênh có hiệu lực".
// Since P2.9 the review events are real: a question submitted over the CMS API and decided on the real review screen (see _world.ts).
import { test, expect } from '../../fixtures';
import { poll } from '../../helpers/env';
import { reviewWorld } from './_world';
import { EVENTS, classroomWorld, drive, gradingWorld, linkTelegram, ordersWorld, planWorld, telegramFor, type EventId, type Fx, type Person, type World } from './_events';

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

// The other §13 events: the preference screen has a row for each, and it takes effect. Before anything is raised, every event of a world gets ONE channel switched off on the real
// preference screen, alternating (Email off / Telegram off) along the events; then the event is raised by its real trigger (see _events.ts). The channel left on delivers it,
// the channel switched off does not (checked once the on channel has delivered the same notification, plus a settle time), and the in-app notice arrives whatever the choice.
// For the orders the choice is then reversed (the off channel switched on): the next rejection reaches it.
const worlds: Array<{ name: string; build: (fx: Fx, label: string) => Promise<World>; gemini?: boolean }> = [
  { name: 'orders', build: ordersWorld },
  { name: 'plan', build: planWorld },
  { name: 'classroom', build: classroomWorld },
  { name: 'grading', build: gradingWorld, gemini: true },
];

type Off = 'Email' | 'Telegram';
const SETTLE_MS = 5_000;

async function run(fx: Fx, world: World, page: import('@playwright/test').Page, auth: any, telegram: typeof import('../../helpers/telegram').telegram, mailpit: typeof import('../../helpers/mailpit').mailpit, browser: import('@playwright/test').Browser) {
  const chatOf = new Map<string, string>();
  for (const who of world.people) chatOf.set(who.userId, await linkTelegram(fx.org, telegram, who));
  const off = new Map<string, Off>(); // `${userId}:${event}`
  const key = (who: Person, event: EventId) => `${who.userId}:${event}`;
  let i = 0;
  for (const st of world.steps) for (const e of st.expects) off.set(key(e.who, e.event), i++ % 2 === 0 ? 'Email' : 'Telegram');

  const setBox = async (who: Person, event: EventId, channel: Off, on: boolean) => {
    const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    const p = await ctx.newPage();
    await auth.loginAs(p, who);
    await p.goto('/notifications/preferences');
    const box = p.getByRole('checkbox', { name: new RegExp(`^${channel}: ${EVENTS[event].title}`) });
    await expect(box).toBeChecked({ checked: !on });
    await box.click();
    await expect(box).toBeChecked({ checked: on });
    await p.reload();
    await expect(p.getByRole('checkbox', { name: new RegExp(`^${channel}: ${EVENTS[event].title}`) })).toBeChecked({ checked: on });
    await ctx.close();
  };
  for (const [k, channel] of off) {
    const [userId, event] = k.split(':') as [string, EventId];
    await setBox(world.people.find((p) => p.userId === userId)!, event, channel, false);
  }

  const delivered = async (who: Person, event: EventId, channel: Off) => {
    if (channel === 'Email') return mailpit.countEmailsTo(who.email, EVENTS[event].title);
    return (await telegramFor(telegram, chatOf.get(who.userId)!, event)).length;
  };
  await drive(fx.org, world, async (e) => {
    const channelOff = off.get(key(e.who, e.event))!;
    const channelOn: Off = channelOff === 'Email' ? 'Telegram' : 'Email';
    // the channel left on delivers it
    await poll(`${e.event} by ${channelOn} to ${e.who.email}`, async () => ((await delivered(e.who, e.event, channelOn)) > 0 ? true : null), { timeoutMs: 60_000, intervalMs: 1_000 });
    // the one switched off does not
    await new Promise((r) => setTimeout(r, SETTLE_MS));
    expect(await delivered(e.who, e.event, channelOff), `${e.event} by ${channelOff} is switched off`).toBe(0);
  });
  return { off, setBox, delivered, key };
}

test.describe('F55 channel preferences: the other spec 13 events', () => {
  for (const w of worlds.filter((x) => !x.gemini)) {
    test(`F55 ${w.name} events: the channel switched off gets nothing, the other delivers`, async ({ page, browser, seed, auth, org, telegram, mailpit, uniqueEmail, request }) => {
      const fx: Fx = { seed, org, uniqueEmail, request };
      const world = await w.build(fx, `f55${w.name}`);
      test.setTimeout(world.timeoutMs);
      const r = await run(fx, world, page, auth, telegram, mailpit, browser);
      if (w.name === 'orders') {
        // reversed: the last event's channel is switched on again and the next rejection reaches it
        const last = world.steps[world.steps.length - 1]!;
        const e = last.expects[0]!;
        const channel = r.off.get(r.key(e.who, e.event))!;
        await r.setBox(e.who, e.event, channel, true);
        await last.fire();
        await poll(`${e.event} by ${channel} once it is switched on`, async () => ((await r.delivered(e.who, e.event, channel)) > 0 ? true : null), { timeoutMs: 60_000, intervalMs: 1_000 });
      }
    });
  }

  test('F55 grading events: the channel switched off gets nothing, the other delivers', async ({ page, browser, seed, auth, org, telegram, mailpit, uniqueEmail, request, gemini }) => {
    const fx: Fx = { seed, org, uniqueEmail, request, gemini };
    const world = await gradingWorld(fx, 'f55grading');
    test.setTimeout(world.timeoutMs);
    await run(fx, world, page, auth, telegram, mailpit, browser);
  });
});
