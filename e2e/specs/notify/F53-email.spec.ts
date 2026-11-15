// F53 — Email (spec §16.2): "Email đúng sự kiện xuất hiện trong mailpit; tắt email cho sự kiện → không gửi".
// Since P2.9 the review events are real: a question submitted over the CMS API and decided on the real review screen (see _world.ts).
import { test, expect } from '../../fixtures';
import { submitQuestion } from '../../fixtures/review';
import { reviewWorld } from './_world';
import { EVENTS, classroomWorld, drive, gradingWorld, ordersWorld, planWorld, type Fx, type World } from './_events';

test.describe('F53 email notifications', () => {
  test('F53 the event arrives in Mailpit in the person\'s language; with email off for that event nothing is sent', async ({ page, browser, seed, auth, mailpit, uniqueEmail }) => {
    const w = await reviewWorld(seed, uniqueEmail, 'f53', browser);
    await w.requested();

    const mail = await mailpit.lastEmailTo(w.reviewer.email, { subjectIncludes: 'Có nội dung chờ duyệt', timeoutMs: 40_000 });
    expect(mail.Subject).toBe('Có nội dung chờ duyệt');
    expect(mail.Text).toContain(`Xin chào Người duyệt f53,`);
    expect(mail.Text).toContain('Một nội dung (question) của kỳ thi IELTS đang chờ bạn duyệt.');
    expect(mail.HTML).toContain('<html lang="vi">');
    expect(mailpit.linksIn(mail).some((l) => l === 'https://localhost/notifications')).toBe(true);

    // switch email off for THIS event in the preference screen
    await auth.loginAs(page, w.reviewer);
    await page.goto('/notifications/preferences');
    const email = page.getByRole('checkbox', { name: /^Email: Có nội dung chờ duyệt/ });
    await expect(email).toBeChecked();
    await email.click();
    await expect(email).not.toBeChecked();
    await page.reload();
    await expect(page.getByRole('checkbox', { name: /^Email: Có nội dung chờ duyệt/ })).not.toBeChecked();

    // a second review event: the in-app notification arrives, the email does not
    await w.requested();
    await page.goto('/notifications');
    await expect(async () => {
      await page.reload();
      await expect(page.getByRole('listitem').filter({ hasText: 'Có nội dung chờ duyệt' })).toHaveCount(2, { timeout: 2_000 });
    }).toPass({ timeout: 40_000 });
    // "no mail" is only proven by a mail that is known to come AFTER the one that must not be sent: a later event for the same person
    // whose email is still on. When that mail is in Mailpit, a wrongly planned mail of the earlier event (created in the same
    // transaction as its in-app row, long before) has had every chance to arrive first.
    await w.reviewerPublished();
    await mailpit.lastEmailTo(w.reviewer.email, { subjectIncludes: 'Nội dung đã được xuất bản', timeoutMs: 40_000 });
    expect(await mailpit.countEmailsTo(w.reviewer.email, 'Có nội dung chờ duyệt')).toBe(1);
  });

  test('F53 an English-speaking person gets the English mail, and the name cannot inject markup', async ({ seed, mailpit, uniqueEmail }) => {
    const suffix = Date.now().toString(36);
    const org = await seed.organization({ name: `Centre f53en ${suffix}`, type: 'CENTER' });
    const reviewer = await seed.verifiedLearner({ email: uniqueEmail('f53-en'), displayName: '<b>Ann</b> & "Lee"', locale: 'en' });
    const author = await seed.verifiedLearner({ email: uniqueEmail('f53-en-author') });
    await seed.member({ orgId: org.orgId, userId: reviewer.userId, roles: [{ role: 'REVIEWER', examCode: 'IELTS' }] });
    await seed.member({ orgId: org.orgId, userId: author.userId, roles: [{ role: 'AUTHOR', examCode: 'IELTS' }] });
    await submitQuestion(author, org.orgId);
    const mail = await mailpit.lastEmailTo(reviewer.email, { subjectIncludes: 'Content is waiting', timeoutMs: 40_000 });
    expect(mail.Text).toContain('Hello <b>Ann</b> & "Lee",');
    expect(mail.HTML).not.toContain('<b>Ann</b>');
    expect(mail.HTML).toContain('&lt;b&gt;Ann&lt;/b&gt; &amp; &quot;Lee&quot;');
  });
});

// The other §13 events, each by its real trigger (see _events.ts): the mail is in Mailpit with the event's subject and text, a link to the notification centre, and the person's name.
// "Email off for the event -> not sent" is proven per world on its LAST step's first event: its in-app notification arrives, and so does a mail of an earlier (email on) event
// of the same person or, where the person has none, a settle time after the in-app notice, long past the delivery job's pick-up.
const worlds: Array<{ name: string; build: (fx: Fx, label: string) => Promise<World>; gemini?: boolean }> = [
  { name: 'orders', build: ordersWorld },
  { name: 'plan', build: planWorld },
  { name: 'classroom', build: classroomWorld },
  { name: 'grading', build: gradingWorld, gemini: true },
];

async function mails(mailpit: typeof import('../../helpers/mailpit').mailpit, world: World, org: Fx['org'], page: import('@playwright/test').Page, auth: any, off: { event: keyof typeof EVENTS; who: World['people'][number] }) {
  // email off for one event of one person, on the real preference screen
  await auth.loginAs(page, off.who);
  await page.goto('/notifications/preferences');
  const box = page.getByRole('checkbox', { name: new RegExp(`^Email: ${EVENTS[off.event].title}`) });
  await expect(box).toBeChecked();
  await box.click();
  await expect(box).not.toBeChecked();
  await page.reload();
  await expect(page.getByRole('checkbox', { name: new RegExp(`^Email: ${EVENTS[off.event].title}`) })).not.toBeChecked();
  await drive(org, world, async (e) => {
    if (e.event === off.event && e.who === off.who) {
      // in the inbox (drive waited for that); no mail, once a settle time has passed
      await new Promise((r) => setTimeout(r, 8_000));
      expect(await mailpit.countEmailsTo(e.who.email, EVENTS[e.event].title)).toBe(0);
      return;
    }
    const mail = await mailpit.lastEmailTo(e.who.email, { subjectIncludes: EVENTS[e.event].title, timeoutMs: 60_000 });
    expect(mail.Subject).toBe(EVENTS[e.event].title);
    expect(mail.Text).toContain('Xin chào');
    expect(mail.Text).toContain(EVENTS[e.event].body);
    expect(mailpit.linksIn(mail).some((l) => l === 'https://localhost/notifications')).toBe(true);
  });
}

test.describe('F53 email: the other spec 13 events', () => {
  for (const w of worlds.filter((x) => !x.gemini)) {
    test(`F53 ${w.name} events arrive in Mailpit; email off for one of them sends none`, async ({ page, seed, auth, org, mailpit, uniqueEmail, request }) => {
      const world = await w.build({ seed, org, uniqueEmail, request }, `f53${w.name}`);
      test.setTimeout(world.timeoutMs);
      const last = world.steps[world.steps.length - 1]!.expects[0]!;
      await mails(mailpit, world, org, page, auth, last);
    });
  }

  test('F53 grading events arrive in Mailpit; email off for the official score sends none', async ({ page, seed, auth, org, mailpit, uniqueEmail, request, gemini }) => {
    const world = await gradingWorld({ seed, org, uniqueEmail, request, gemini }, 'f53grading');
    test.setTimeout(world.timeoutMs);
    const last = world.steps[world.steps.length - 1]!.expects[0]!;
    await mails(mailpit, world, org, page, auth, last);
  });
});
