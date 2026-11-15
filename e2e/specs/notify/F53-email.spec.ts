// F53 — Email (spec §16.2): "Email đúng sự kiện xuất hiện trong mailpit; tắt email cho sự kiện → không gửi".
// Since P2.9 the review events are real: a question submitted over the CMS API and decided on the real review screen (see _world.ts).
import { test, expect } from '../../fixtures';
import { submitQuestion } from '../../fixtures/review';
import { reviewWorld } from './_world';
import { classroomWorld, ordersWorld, pickOff, planWorld, runChannels, type Fx, type World } from './_events';

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

const worlds: Array<{ name: string; build: (fx: Fx, label: string) => Promise<World> }> = [
  { name: 'orders', build: ordersWorld },
  { name: 'plan', build: planWorld },
  { name: 'classroom', build: classroomWorld },
];

// The other §13 events, each by its real trigger (see _events.ts): the mail is in Mailpit with the event's subject, text and the link to the notification centre.
// Email off for one event (switched on the real preference screen; the first event of a person who has a LATER event): its in-app notice arrives, no mail comes, asserted
// only after the later event's mail has arrived. The grading events are asserted by the one grading run of F55-channel-prefs.spec.ts (it holds the fake Gemini for minutes).
test.describe('F53 email: the other spec 13 events', () => {
  for (const w of worlds) {
    test(`F53 ${w.name} events arrive in Mailpit; email off for one of them sends none`, async ({ browser, seed, auth, org, mailpit, telegram, uniqueEmail, request }) => {
      const fx: Fx = { seed, org, uniqueEmail, request };
      const world = await w.build(fx, `f53${w.name}`);
      test.setTimeout(world.timeoutMs);
      const { first } = pickOff(world);
      await runChannels({ fx, world, browser, auth, telegram, mailpit, use: { email: true }, off: [{ who: first.who, event: first.event, channel: 'Email' }] });
    });
  }
});
