// F41 — Quyền học (spec §16.2): "Entitlement hết hạn → không vào học được nhưng xem được kết quả cũ".
//
// !! PARTIAL (see e2e/partial-features.json). P5.1 builds the gate (billing.api.Entitlements) and the plan-expiry notices. Here the REAL gate is asked as a person
// !! through the seed API, and the learner's own entitlement list and notifications are read through the real API. The lesson page and the exam room that call the
// !! gate arrive with P3.1 and P3.3, and the results page with P4.4: "cannot open a lesson / can still read old results" is then asserted on those screens.
import { test, expect } from '../../fixtures';

test.describe('F41 entitlement access', () => {
  test('F41 an active plan opens new learning, an expired one does not but keeps the attempt that was under way and the history, and the learner is told twice', async ({ seed, org, uniqueEmail }) => {
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f41') });
    const l = await org.apiLogin(learner);
    const active = await seed.billing.course();
    const expired = await seed.billing.course();
    const soon = await seed.billing.course();
    await seed.billing.entitlement({ userId: learner.userId, courseId: active.courseId, startsInDays: -10, endsInDays: 30 });
    await seed.billing.entitlement({ userId: learner.userId, courseId: expired.courseId, startsInDays: -40, endsInDays: -1 });
    await seed.billing.entitlement({ userId: learner.userId, courseId: soon.courseId, startsInDays: -27, endsInDays: 3 });

    // the gate: new learning only while the plan is valid; an attempt begun while it was valid may be finished
    expect(await seed.billing.gate({ userId: learner.userId, courseId: active.courseId })).toMatchObject({ activeFor: true });
    expect(await seed.billing.gate({ userId: learner.userId, courseId: expired.courseId, attemptStartedDaysAgo: 10 })).toMatchObject({ activeFor: false, canContinueAttempt: true });
    expect(await seed.billing.gate({ userId: learner.userId, courseId: expired.courseId, attemptStartedDaysAgo: 60 })).toMatchObject({ activeFor: false, canContinueAttempt: false });
    const stranger = await seed.verifiedLearner({ email: uniqueEmail('f41-stranger') });
    expect(await seed.billing.gate({ userId: stranger.userId, courseId: active.courseId })).toMatchObject({ activeFor: false });

    // the history stays: the expired plan is still listed, as not active
    const mine = (await org.api(l.token, 'GET', '/api/me/entitlements')).body as Array<{ courseId: string; active: boolean }>;
    expect(mine).toHaveLength(3);
    expect(mine.find((e) => e.courseId === expired.courseId)?.active).toBe(false);
    expect(mine.find((e) => e.courseId === active.courseId)?.active).toBe(true);
    expect((await org.api((await org.apiLogin(stranger)).token, 'GET', '/api/me/entitlements')).body).toHaveLength(0);

    // the learner is told once when the plan is about to end and once when it has ended, however often the jobs run
    for (let i = 0; i < 2; i++) {
      await seed.billing.runJob('plan_expiry_reminder');
      await seed.billing.runJob('expire_entitlements');
    }
    const kinds = async () => ((await org.api(l.token, 'GET', '/api/me/notifications')).body.items ?? []).map((n: { type: string }) => n.type).sort();
    await expect.poll(kinds, { timeout: 40_000 }).toEqual(['PLAN_EXPIRED', 'PLAN_EXPIRING']);
    await new Promise((r) => setTimeout(r, 3_000)); // the jobs have run twice: a duplicate would have been made by now
    expect(await kinds()).toEqual(['PLAN_EXPIRED', 'PLAN_EXPIRING']);
  });
});
