// F53 — Email (spec §16.2): "Email đúng sự kiện xuất hiện trong mailpit; tắt email cho sự kiện → không gửi".
//
// !! PARTIAL for the same reason as F52: real events through the real registry, real job, real SMTP to Mailpit, real preference
// !! screen; the trigger is the seed API standing in for the CMS review screens (P2.9). e2e/partial-features.json.
import { test, expect } from '../../fixtures';
import { reviewWorld } from './_world';

test.describe('F53 email notifications', () => {
  test('F53 the event arrives in Mailpit in the person\'s language; with email off for that event nothing is sent', async ({ page, seed, auth, mailpit, uniqueEmail }) => {
    const w = await reviewWorld(seed, uniqueEmail, 'f53');
    await w.requested();

    const mail = await mailpit.lastEmailTo(w.reviewer.email, { subjectIncludes: 'Có nội dung chờ duyệt', timeoutMs: 40_000 });
    expect(mail.Subject).toBe('Có nội dung chờ duyệt');
    expect(mail.Text).toContain(`Xin chào Người duyệt f53,`);
    expect(mail.Text).toContain('Một nội dung (e2e_item) của kỳ thi IELTS đang chờ bạn duyệt.');
    expect(mail.HTML).toContain('<html lang="vi">');
    expect(mailpit.linksIn(mail).some((l) => l === 'https://localhost/notifications')).toBe(true);

    // the same person also writes content, so a later event of another kind can be sent to them (see the end of the test)
    await seed.member({ orgId: w.org.orgId, userId: w.reviewer.userId, roles: [{ role: 'AUTHOR', examCode: 'IELTS' }] });
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
    await seed.reviewEvent({ kind: 'approved', authorId: w.reviewer.userId, orgId: w.org.orgId });
    await mailpit.lastEmailTo(w.reviewer.email, { subjectIncludes: 'Nội dung đã được xuất bản', timeoutMs: 40_000 });
    expect(await mailpit.countEmailsTo(w.reviewer.email, 'Có nội dung chờ duyệt')).toBe(1);
  });

  test('F53 an English-speaking person gets the English mail, and the name cannot inject markup', async ({ seed, mailpit, uniqueEmail }) => {
    const suffix = Date.now().toString(36);
    const org = await seed.organization({ name: `Centre f53en ${suffix}`, type: 'CENTER' });
    const reviewer = await seed.verifiedLearner({ email: uniqueEmail('f53-en'), displayName: '<b>Ann</b> & "Lee"', locale: 'en' });
    const author = await seed.verifiedLearner({ email: uniqueEmail('f53-en-author') });
    await seed.member({ orgId: org.orgId, userId: reviewer.userId, roles: [{ role: 'REVIEWER', examCode: 'IELTS' }] });
    await seed.reviewEvent({ kind: 'requested', authorId: author.userId, orgId: org.orgId });
    const mail = await mailpit.lastEmailTo(reviewer.email, { subjectIncludes: 'Content is waiting', timeoutMs: 40_000 });
    expect(mail.Text).toContain('Hello <b>Ann</b> & "Lee",');
    expect(mail.HTML).not.toContain('<b>Ann</b>');
    expect(mail.HTML).toContain('&lt;b&gt;Ann&lt;/b&gt; &amp; &quot;Lee&quot;');
  });
});
