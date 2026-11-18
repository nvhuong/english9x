import type { APIRequestContext, Browser, Page } from '@playwright/test';
import { expect } from '../../fixtures';
import { staffed } from '../../fixtures/content';
import { ensureGradingSkill, publishedIeltsTest, sitWritingAndSpeaking } from '../../fixtures/ai-grading';
import type { loginAs as LoginAs } from '../../fixtures/auth';
import type { mailpit as Mailpit } from '../../helpers/mailpit';
import type { api as Api, apiLogin as ApiLogin } from '../../fixtures/org';
import type { seed as Seed } from '../../fixtures/seed';
import { env, poll } from '../../helpers/env';
import type { gemini as Gemini } from '../../helpers/gemini';
import type { telegram as Telegram } from '../../helpers/telegram';

/**
 * Spec §13 beyond the review events (which `_world.ts` raises): every other event of the table, each raised the way production raises it, so that F52 (the in-app
 * screen), F53 (email), F54 (Telegram) and F55 (the preferences) can assert each of them on their channel.
 *
 * Event -> source (all real; the three review events are `_world.ts`):
 *   ORDER_PENDING            SYS_ADMIN  a learner places an order (billing OrderPlaced)
 *   ORDER_CONFIRMED          learner    the administrator confirms it (OrderConfirmed)
 *   ORDER_REJECTED           learner    the administrator rejects another (OrderRejected)
 *   PLAN_EXPIRING/EXPIRED    learner    the real jobs plan_expiry_reminder / expire_entitlements
 *   SCORE_PRELIMINARY        learner    the AI scores an attempt of a BOTH-mode test (fake Gemini answers)
 *   GRADING_QUEUE_NEW        TEACHER    the same attempt reaches the teachers' queue after the late-sync grace
 *   GRADING_CLAIM_DUE        TEACHER    a claimed item falls due within the warning window (release_expired_claim job)
 *   SCORE_OFFICIAL           learner    the teacher saves both skills (POST /api/teacher/queue/{id}/review)
 *   AI_GRADING_FAILED        SYS_ADMIN  the fake Gemini fails every call of a second attempt
 *   ASSIGNMENT_NEW           learner    the teacher gives an assignment (classroom AssignmentGiven)
 *   ASSIGNMENT_DUE_SOON      learner    the same assignment falls due within 24 hours (assignment_due_soon job)
 *   SESSION_REMINDER         learner + teacher  a session starts within the hour (session_reminder job)
 */
export type EventId =
  | 'ORDER_PENDING' | 'ORDER_CONFIRMED' | 'ORDER_REJECTED' | 'PLAN_EXPIRING' | 'PLAN_EXPIRED'
  | 'SCORE_PRELIMINARY' | 'SCORE_OFFICIAL' | 'GRADING_QUEUE_NEW' | 'GRADING_CLAIM_DUE' | 'AI_GRADING_FAILED'
  | 'ASSIGNMENT_NEW' | 'ASSIGNMENT_DUE_SOON' | 'SESSION_REMINDER';

/** The Vietnamese title (subject of the mail, heading in the app, bold line in Telegram) and the start of the body, as in templates.vi.properties. */
export const EVENTS: Record<EventId, { title: string; body: string }> = {
  ORDER_PENDING: { title: 'Có đơn hàng mới chờ xác nhận', body: 'Có một đơn hàng đang chờ bạn xác nhận thanh toán.' },
  ORDER_CONFIRMED: { title: 'Đơn hàng đã được xác nhận', body: 'Đơn hàng của bạn đã được xác nhận. Bạn có thể bắt đầu học ngay.' },
  ORDER_REJECTED: { title: 'Đơn hàng không được xác nhận', body: 'Đơn hàng của bạn không được xác nhận. Lý do: Sai số tiền' },
  PLAN_EXPIRING: { title: 'Gói học sắp hết hạn', body: 'Gói học của bạn còn khoảng 7 ngày nữa là hết hạn.' },
  PLAN_EXPIRED: { title: 'Gói học đã hết hạn', body: 'Gói học của bạn đã hết hạn. Gia hạn để tiếp tục học.' },
  SCORE_PRELIMINARY: { title: 'Bài làm của bạn đã có điểm sơ bộ', body: 'Điểm sơ bộ (do AI chấm) đã có.' },
  SCORE_OFFICIAL: { title: 'Bài làm của bạn đã có điểm chính thức', body: 'Điểm chính thức của bài làm đã có.' },
  GRADING_QUEUE_NEW: { title: 'Có bài mới trong hàng chờ chấm', body: 'Có bài mới đang chờ giáo viên chấm.' },
  GRADING_CLAIM_DUE: { title: 'Bài bạn nhận sắp quá hạn chấm', body: 'Một bài bạn đã nhận sắp quá hạn chấm.' },
  AI_GRADING_FAILED: { title: 'Chấm AI bị lỗi', body: 'Chấm AI cho một bài làm đã lỗi sau mọi lần thử lại.' },
  ASSIGNMENT_NEW: { title: 'Bạn có bài được giao mới', body: 'Giáo viên vừa giao cho bạn một bài mới.' },
  ASSIGNMENT_DUE_SOON: { title: 'Bài được giao sắp đến hạn', body: 'Một bài được giao của bạn sắp đến hạn nộp.' },
  SESSION_REMINDER: { title: 'Sắp đến buổi học', body: 'Buổi học của bạn bắt đầu sau khoảng 1 giờ.' },
};

export interface Person { email: string; password: string; userId: string; displayName?: string }
/** `who` receives `event` once the step's `fire` has run. */
export interface Expect { event: EventId; who: Person }
export interface Step { label: string; fire: () => Promise<void>; expects: Expect[] }
export interface World {
  name: string;
  /** Everybody who receives something: the preferences and the Telegram chat of each are set before the first step. */
  people: Person[];
  steps: Step[];
  /** Test timeout the world needs, in ms. */
  timeoutMs: number;
}

/** What a world needs from the test's fixtures. `gemini` is asked for (and so serialised against other users of the fake) only by worlds that need it. */
export interface Fx {
  seed: typeof Seed;
  org: { apiLogin: typeof ApiLogin; api: typeof Api };
  uniqueEmail: (label?: string) => string;
  request: APIRequestContext;
  gemini?: typeof Gemini;
}

const sfx = () => Date.now().toString(36);

// ---------------------------------------------------------------------------------------------------------------------------------------------------------------
// orders: ORDER_PENDING, ORDER_CONFIRMED, ORDER_REJECTED

export async function ordersWorld({ seed, org, uniqueEmail }: Fx, label: string): Promise<World> {
  const admin = await seed.sysAdmin({ email: uniqueEmail(`${label}-admin`), displayName: `Quản trị ${label}` });
  const buyer = await seed.verifiedLearner({ email: uniqueEmail(`${label}-buyer`), displayName: `Người mua ${label}` });
  const a = await org.apiLogin(admin);
  const l = await org.apiLogin(buyer);
  const course = await seed.billing.course();
  const plan = await org.api(a.token, 'POST', '/api/admin/plans', { body: { name: `Gói ${label} ${sfx()}`, priceVnd: 499000, months: 3, maxCourses: 1, active: true } });
  expect(plan.status, plan.text).toBe(201);
  const account = await org.api(a.token, 'PUT', '/api/admin/payment-account', { body: { bankBin: '970436', accountNo: `9${Date.now().toString().slice(-9)}`, accountName: 'CONG TY ENGLISH9X' } });
  expect(account.status, account.text).toBeLessThan(300);
  const place = async () => {
    const placed = await org.api(l.token, 'POST', '/api/orders', { body: { planId: plan.body.id, courseIds: [course.courseId] } });
    expect(placed.status, placed.text).toBe(201);
    return placed.body.orderCode as string;
  };
  let first = '';
  return {
    name: 'orders',
    people: [admin, buyer],
    timeoutMs: 240_000,
    steps: [
      { label: 'an order is placed', fire: async () => { first = await place(); }, expects: [{ event: 'ORDER_PENDING', who: admin }] },
      {
        label: 'the administrator confirms it',
        fire: async () => { expect((await org.api(a.token, 'POST', `/api/admin/orders/${first}/confirm`, { body: { bankRef: 'FT26111012345' } })).status).toBe(200); },
        expects: [{ event: 'ORDER_CONFIRMED', who: buyer }],
      },
      {
        label: 'the administrator rejects another',
        fire: async () => {
          const code = await place();
          expect((await org.api(a.token, 'POST', `/api/admin/orders/${code}/reject`, { body: { reason: 'Sai số tiền' } })).status).toBe(200);
        },
        expects: [{ event: 'ORDER_REJECTED', who: buyer }],
      },
    ],
  };
}

// ---------------------------------------------------------------------------------------------------------------------------------------------------------------
// plan: PLAN_EXPIRING, PLAN_EXPIRED

export async function planWorld({ seed, uniqueEmail }: Fx, label: string): Promise<World> {
  const learner = await seed.verifiedLearner({ email: uniqueEmail(`${label}-learner`), displayName: `Học viên ${label}` });
  const soon = await seed.billing.course();
  const gone = await seed.billing.course();
  await seed.billing.entitlement({ userId: learner.userId, courseId: soon.courseId, startsInDays: -27, endsInDays: 3 });
  await seed.billing.entitlement({ userId: learner.userId, courseId: gone.courseId, startsInDays: -40, endsInDays: -1 });
  return {
    name: 'plan',
    people: [learner],
    timeoutMs: 240_000,
    steps: [{
      label: 'the reminder and the expiry jobs run',
      fire: async () => {
        await seed.billing.runJob('plan_expiry_reminder');
        await seed.billing.runJob('expire_entitlements');
      },
      expects: [{ event: 'PLAN_EXPIRING', who: learner }, { event: 'PLAN_EXPIRED', who: learner }],
    }],
  };
}

// ---------------------------------------------------------------------------------------------------------------------------------------------------------------
// classroom: ASSIGNMENT_NEW, ASSIGNMENT_DUE_SOON, SESSION_REMINDER (learner and teacher)

export async function classroomWorld({ seed, org, uniqueEmail }: Fx, label: string): Promise<World> {
  const center = await seed.organization({ name: `Trung tâm ${label} ${sfx()}`, type: 'CENTER' });
  const boss = await seed.verifiedLearner({ email: uniqueEmail(`${label}-boss`), displayName: `Quản trị ${label}` });
  const teacher = await seed.verifiedLearner({ email: uniqueEmail(`${label}-teacher`), displayName: `Giáo viên ${label}` });
  const ann = await seed.verifiedLearner({ email: uniqueEmail(`${label}-ann`), displayName: `Học viên ${label}` });
  await seed.member({ orgId: center.orgId, userId: boss.userId, roles: [{ role: 'ORG_ADMIN' }] });
  await seed.member({ orgId: center.orgId, userId: teacher.userId, roles: [{ role: 'TEACHER', examCode: 'IELTS' }] });
  await seed.member({ orgId: center.orgId, userId: ann.userId, roles: [{ role: 'LEARNER' }] });
  const published = await seed.publishedIeltsTest({ variant: 'AC' });
  const bossT = await org.apiLogin(boss);
  const teacherT = await org.apiLogin(teacher);
  const made = await org.api(bossT.token, 'POST', `/api/orgs/${center.orgId}/classes`, { org: center.orgId, body: { name: `Lớp ${label} ${sfx()}`, teacherId: teacher.userId } });
  expect(made.status, made.text).toBe(201);
  const classId = made.body.id as string;
  expect((await org.api(teacherT.token, 'POST', `/api/orgs/${center.orgId}/classes/${classId}/members`, { org: center.orgId, body: { userIds: [ann.userId] } })).status).toBe(200);
  return {
    name: 'classroom',
    people: [ann, teacher],
    timeoutMs: 240_000,
    steps: [
      {
        // due in 12 hours: new, and already inside the 24-hour "due soon" window
        label: 'the teacher gives an assignment that falls due within a day',
        fire: async () => {
          const given = await org.api(teacherT.token, 'POST', `/api/orgs/${center.orgId}/classes/${classId}/assignments`, {
            org: center.orgId, body: { testVersionId: published.testVersionId, dueAt: new Date(Date.now() + 12 * 3_600_000).toISOString() },
          });
          expect(given.status, given.text).toBe(201);
        },
        expects: [{ event: 'ASSIGNMENT_NEW', who: ann }, { event: 'ASSIGNMENT_DUE_SOON', who: ann }],
      },
      {
        label: 'a session starts in 40 minutes',
        fire: async () => {
          const soon = await org.api(teacherT.token, 'POST', `/api/orgs/${center.orgId}/classes/${classId}/sessions`, {
            org: center.orgId, body: { mode: 'OFFLINE', startsAt: new Date(Date.now() + 40 * 60_000).toISOString(), endsAt: new Date(Date.now() + 100 * 60_000).toISOString(), room: `Phòng ${label}` },
          });
          expect(soon.status, soon.text).toBe(201);
        },
        expects: [{ event: 'SESSION_REMINDER', who: ann }, { event: 'SESSION_REMINDER', who: teacher }],
      },
    ],
  };
}

// ---------------------------------------------------------------------------------------------------------------------------------------------------------------
// grading: GRADING_QUEUE_NEW, SCORE_PRELIMINARY, AI_GRADING_FAILED, GRADING_CLAIM_DUE, SCORE_OFFICIAL (needs the fake Gemini: the `gemini` fixture)

export async function gradingWorld({ seed, org, uniqueEmail, request, gemini }: Fx, label: string): Promise<World> {
  if (!gemini) throw new Error('gradingWorld needs the gemini fixture');
  const s = await staffed(['IELTS'], label, { request, uniqueEmail, apiLogin: org.apiLogin });
  await ensureGradingSkill(s, 'IELTS_W_TASK2');
  await ensureGradingSkill(s, 'IELTS_S_PART1');
  const testVersionId = await publishedIeltsTest(s, `${label} ${Math.random().toString(36).slice(2, 8)}`);
  await seed.grading.policy({ testVersionId, mode: 'BOTH' });
  // Listening and Reading are converted by tables of this test, so that every skill of the attempt has a score and the announcements can be made
  await seed.grading.scoreTable({ testVersionId, skill: 'LISTENING', scaled: [0, 5.5] });
  await seed.grading.scoreTable({ testVersionId, skill: 'READING_AC', scaled: [0, 6.0] });
  const teacher = await seed.verifiedLearner({ email: uniqueEmail(`${label}-teacher`), displayName: `Giáo viên ${label}` });
  await seed.staffGrant({ userId: teacher.userId, role: 'TEACHER', examCode: 'IELTS' });
  const admin = await seed.sysAdmin({ email: uniqueEmail(`${label}-admin`), displayName: `Quản trị ${label}` });
  const one = await seed.learnerWithPlan({ courses: 1 });
  const two = await seed.learnerWithPlan({ courses: 1 });
  const learner: Person = { email: one.email, password: one.password, userId: one.userId };
  const teacherT = await org.apiLogin(teacher);
  let attempt = '';
  let item = '';
  return {
    name: 'grading',
    people: [learner, teacher, admin],
    timeoutMs: 900_000,
    steps: [
      {
        // The first attempt is graded by the fake Gemini (answers band 6.5) once the late-sync grace is over. The second is submitted a minute later, so the first has been
        // graded (and this spec has switched the fake to failing) before the second is asked: the same lock, one wait of the grace.
        label: 'an attempt is submitted and, after the grace, graded by the AI and queued for the teachers',
        fire: async () => {
          await gemini.reset();
          attempt = await sitWritingAndSpeaking(request, (await org.apiLogin(one)).token, testVersionId, `${label} essay ${Math.random().toString(36).slice(2, 8)}: cities or villages.`);
          await new Promise((r) => setTimeout(r, 60_000));
          await sitWritingAndSpeaking(request, (await org.apiLogin(two)).token, testVersionId, `${label} essay two ${Math.random().toString(36).slice(2, 8)}: this will never be graded.`);
        },
        expects: [{ event: 'GRADING_QUEUE_NEW', who: teacher }, { event: 'SCORE_PRELIMINARY', who: learner }],
      },
      {
        label: 'Gemini fails every call of the second attempt',
        fire: async () => { await gemini.setScenario('error500'); },
        expects: [{ event: 'AI_GRADING_FAILED', who: admin }],
      },
      {
        label: 'a teacher claims the first attempt and its deadline comes within the warning window',
        fire: async () => {
          const queued = await poll('the first attempt in the teachers\' queue', async () => (await seed.grading.queue(attempt)).item, { timeoutMs: 60_000, intervalMs: 1_000 });
          item = queued.id;
          const claimed = await org.api(teacherT.token, 'POST', `/api/teacher/queue/${item}/claim`);
          expect(claimed.status, claimed.text).toBe(200);
          await seed.grading.expireClaim(attempt, { dueInMinutes: 30 });
        },
        expects: [{ event: 'GRADING_CLAIM_DUE', who: teacher }],
      },
      {
        label: 'the teacher saves both skills: the official score',
        fire: async () => {
          for (const skill of ['WRITING', 'SPEAKING']) {
            const r = await org.api(teacherT.token, 'POST', `/api/teacher/queue/${item}/review`, { body: { skill, criteria: { task_response: 7, coherence: 7 }, comment: 'Clear position.' } });
            expect(r.status, r.text).toBe(200);
          }
        },
        expects: [{ event: 'SCORE_OFFICIAL', who: learner }],
      },
    ],
  };
}

// ---------------------------------------------------------------------------------------------------------------------------------------------------------------
// shared steps of the four specs

/** Reads the person's inbox through the API (the same list the screen shows). */
export async function inboxTypes(org: Fx['org'], who: Person): Promise<string[]> {
  const r = await org.api((await org.apiLogin(who)).token, 'GET', '/api/me/notifications?limit=50');
  expect(r.status, r.text).toBe(200);
  return (r.body.items as Array<{ type: string }>).map((n) => n.type);
}

/** Waits until the person has a notification of the type in the inbox (it is stored after the event's transaction committed): the barrier before any channel is looked at. */
export async function arrived(org: Fx['org'], e: Expect, timeoutMs = 120_000): Promise<void> {
  await poll(`${e.event} in the inbox of ${e.who.email}`, async () => ((await inboxTypes(org, e.who)).includes(e.event) ? true : null), { timeoutMs, intervalMs: 1_000 });
}

/**
 * Plays Telegram: links the person's account to a fresh chat through the one-time link and the real webhook, and returns the chat id.
 * (The screen that creates the link is F54's own first test.)
 */
export async function linkTelegram(org: Fx['org'], telegram: typeof Telegram, who: Person): Promise<string> {
  const token = (await org.apiLogin(who)).token;
  const link = await org.api(token, 'POST', '/api/me/telegram/link');
  expect(link.status, link.text).toBe(200);
  const secret = new URL(link.body.deepLink as string).searchParams.get('start')!;
  const chat = telegram.uniqueChatId();
  expect(await telegram.deliverUpdate(telegram.startUpdate(chat, secret))).toBe(200);
  await telegram.waitForMessages(chat, { timeoutMs: 20_000 });
  return chat;
}

/** The Telegram messages for the chat whose bold first line is the event's title. */
export async function telegramFor(telegram: typeof Telegram, chat: string, event: EventId): Promise<Array<{ text: string }>> {
  return (await telegram.messagesFor(chat)).filter((m) => m.text.includes(`<b>${EVENTS[event].title}</b>`));
}

// ---------------------------------------------------------------------------------------------------------------------------------------------------------------
// the channels

export type Off = 'Email' | 'Telegram';
export interface OffPref { who: Person; event: EventId; channel: Off }
export interface Channels {
  /** F52: the recipient's real notification screen lists it (and it can be marked read). */
  screen?: boolean;
  /** F53: the mail is in Mailpit. */
  email?: boolean;
  /** F54: the message is in the person's linked chat. */
  telegram?: boolean;
}
const SETTLE_MS = 3_000;

/** The first expectation that a LATER expectation of the same person (another event) follows: its arrival is the barrier for the absence of the first. */
export function pickOff(world: World): { first: Expect; later: Expect } {
  const all = world.steps.flatMap((st) => st.expects);
  for (let i = 0; i < all.length; i++) {
    const later = all.slice(i + 1).find((x) => x.who.userId === all[i]!.who.userId && x.event !== all[i]!.event);
    if (later) return { first: all[i]!, later };
  }
  throw new Error(`world ${world.name} has no person with two events`);
}

/**
 * Raises the world's events in order and asserts each on the channels asked for. Counts are taken BEFORE a step fires and an arrival means "one more than before"
 * (parallel specs raise the same event types for other people; the recipients are this world's own people, but a count is still what proves THIS step's event).
 * `off` entries are switched off on the real preference screen first; their absence is asserted only after a LATER event of the same person has arrived on the same
 * channel (the delivery jobs of one person's notices are queued in order), plus a short settle time; with no later event it is the settle time alone.
 */
export async function runChannels(o: {
  fx: Fx; world: World; browser: Browser; auth: { loginAs: typeof LoginAs }; telegram: typeof Telegram; mailpit: typeof Mailpit;
  use: Channels; off?: OffPref[];
}) {
  const { fx, world, browser, auth, telegram, mailpit, use } = o;
  const off = o.off ?? [];
  const isOff = (who: Person, event: EventId, channel: Off) => off.some((x) => x.who.userId === who.userId && x.event === event && x.channel === channel);
  const chatOf = new Map<string, string>();
  if (use.telegram) for (const who of world.people) chatOf.set(who.userId, await linkTelegram(fx.org, telegram, who));

  const setBox = async (who: Person, event: EventId, channel: Off, on: boolean) => {
    const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
    const page = await ctx.newPage();
    await auth.loginAs(page, who);
    await page.goto('/notifications/preferences');
    const name = new RegExp(`^${channel}: ${EVENTS[event].title}`);
    await expect(page.getByRole('checkbox', { name })).toBeChecked({ checked: !on });
    await page.getByRole('checkbox', { name }).click();
    await expect(page.getByRole('checkbox', { name })).toBeChecked({ checked: on });
    await page.reload();
    await expect(page.getByRole('checkbox', { name })).toBeChecked({ checked: on });
    await ctx.close();
  };
  for (const x of off) await setBox(x.who, x.event, x.channel, false);

  const pages = new Map<string, Page>();
  const pageOf = async (who: Person) => {
    let page = pages.get(who.userId);
    if (!page) {
      page = await (await browser.newContext({ ignoreHTTPSErrors: true })).newPage();
      await auth.loginAs(page, who);
      pages.set(who.userId, page);
    }
    return page;
  };
  const count = async (who: Person, event: EventId, channel: 'App' | Off): Promise<number> => {
    if (channel === 'App') return (await inboxTypes(fx.org, who)).filter((t) => t === event).length;
    if (channel === 'Email') return mailpit.countEmailsTo(who.email, EVENTS[event].title);
    return (await telegramFor(telegram, chatOf.get(who.userId)!, event)).length;
  };
  const channelsUsed: Off[] = [...(use.email ? ['Email' as const] : []), ...(use.telegram ? ['Telegram' as const] : [])];
  const pending: Array<{ who: Person; event: EventId; channel: Off; base: number }> = [];
  const never = async (p: { who: Person; event: EventId; channel: Off; base: number }) =>
    expect(await count(p.who, p.event, p.channel), `${p.event} by ${p.channel} to ${p.who.email} is switched off`).toBe(p.base);

  for (const step of world.steps) {
    const base = new Map<string, number>();
    const k = (e: Expect, c: 'App' | Off) => `${e.who.userId}:${e.event}:${c}`;
    for (const e of step.expects) for (const c of ['App', ...channelsUsed] as const) base.set(k(e, c), await count(e.who, e.event, c));
    await step.fire();
    for (const e of step.expects) {
      await poll(`${e.event} in the inbox of ${e.who.email}`, async () => ((await count(e.who, e.event, 'App')) > base.get(k(e, 'App'))! ? true : null), { timeoutMs: 120_000, intervalMs: 1_000 });
      if (use.screen) {
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
      }
      for (const channel of channelsUsed) {
        const before = base.get(k(e, channel))!;
        if (isOff(e.who, e.event, channel)) {
          pending.push({ who: e.who, event: e.event, channel, base: before });
          continue;
        }
        await poll(`${e.event} by ${channel} to ${e.who.email}`, async () => ((await count(e.who, e.event, channel)) > before ? true : null), { timeoutMs: 60_000, intervalMs: 1_000 });
        if (channel === 'Email') {
          const mail = await mailpit.lastEmailTo(e.who.email, { subjectIncludes: EVENTS[e.event].title, timeoutMs: 20_000 });
          expect(mail.Subject).toBe(EVENTS[e.event].title);
          expect(mail.Text).toContain('Xin chào');
          expect(mail.Text).toContain(EVENTS[e.event].body);
          expect(mailpit.linksIn(mail).some((l) => l === `${env.baseURL}/notifications`)).toBe(true);
        } else {
          const text = (await telegramFor(telegram, chatOf.get(e.who.userId)!, e.event)).map((m) => m.text).join('\n');
          expect(text).toContain(`<b>${EVENTS[e.event].title}</b>`);
          expect(text).toContain(EVENTS[e.event].body);
          expect(text).toContain(`href="${env.baseURL}/notifications"`);
          // nobody else's chat got it, unless they are a recipient of the same event
          for (const other of world.people) {
            if (other.userId !== e.who.userId && !world.steps.some((st) => st.expects.some((x) => x.who.userId === other.userId && x.event === e.event))) {
              expect(await count(other, e.event, 'Telegram'), `${e.event} must not reach ${other.email}`).toBe(0);
            }
          }
        }
        // a later event of the same person has now arrived on this channel: the switched-off earlier ones had every chance to arrive first
        for (const p of pending.filter((x) => x.who.userId === e.who.userId && x.channel === channel && x.event !== e.event)) {
          await new Promise((r) => setTimeout(r, SETTLE_MS));
          await never(p);
          pending.splice(pending.indexOf(p), 1);
        }
      }
    }
  }
  if (pending.length > 0) {
    await new Promise((r) => setTimeout(r, SETTLE_MS * 2));
    for (const p of pending) await never(p);
  }
  return { setBox, count };
}
