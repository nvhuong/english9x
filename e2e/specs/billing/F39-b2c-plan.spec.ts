// F39 — Gói B2C (spec §16.2): "Mua gói, chọn khoá ≤ tối đa".
//
// !! PARTIAL (see e2e/partial-features.json). P5.1 is the backend: plans, the order with its locked price, the course limit, the verified-email rule, the
// !! order code and the transfer note, the audit trail and the expiry job, all driven here through the REAL API of the running stack (no UI exists yet: the plan
// !! and checkout screens are P5.4, the QR and the admin confirmation are P5.2). The browser steps and the QR/confirm half of the scenario are pending.
import { test, expect } from '../../fixtures';

test.describe('F39 B2C plan', () => {
  test('F39 an administrator sells a plan, a verified learner orders within the course limit at a locked price, an unverified one cannot, and an unpaid order expires', async ({ seed, org, uniqueEmail }) => {
    const admin = await seed.sysAdmin({ email: uniqueEmail('f39-admin') });
    const learner = await seed.verifiedLearner({ email: uniqueEmail('f39-learner') });
    const unverified = await seed.verifiedLearner({ email: uniqueEmail('f39-unverified'), verified: false });
    const a = await org.apiLogin(admin);
    const l = await org.apiLogin(learner);
    const u = await org.apiLogin(unverified);
    const c1 = await seed.billing.course();
    const c2 = await seed.billing.course();

    // only the SYS_ADMIN sells plans; a learner sees plans on sale only
    const body = { name: `Gói F39 ${Date.now()}`, priceVnd: 499000, months: 3, maxCourses: 1, active: true };
    expect((await org.api(l.token, 'POST', '/api/admin/plans', { body })).status).toBe(403);
    const created = await org.api(a.token, 'POST', '/api/admin/plans', { body });
    expect(created.status).toBe(201);
    const planId: string = created.body.id;
    const hidden = await org.api(a.token, 'POST', '/api/admin/plans', { body: { ...body, name: `Ẩn ${Date.now()}`, active: false } });
    const onSale = (await org.api(l.token, 'GET', '/api/plans')).body as Array<{ id: string }>;
    expect(onSale.map((p) => p.id)).toContain(planId);
    expect(onSale.map((p) => p.id)).not.toContain(hidden.body.id);

    // more courses than the plan allows, and a course that is not published, are refused
    const tooMany = await org.api(l.token, 'POST', '/api/orders', { body: { planId, courseIds: [c1.courseId, c2.courseId] } });
    expect(tooMany.status).toBe(400);
    expect(tooMany.body.type).toBe('urn:e9x:problem:validation-failed');
    expect((await org.api(l.token, 'POST', '/api/orders', { body: { planId, courseIds: ['00000000-0000-4000-8000-000000000001'] } })).status).toBe(400);

    // an unverified email cannot buy (F01: the same rule at the real order endpoint)
    const refused = await org.api(u.token, 'POST', '/api/orders', { body: { planId, courseIds: [c1.courseId] } });
    expect(refused.status).toBe(403);
    expect(refused.body.type).toBe('urn:e9x:problem:email-not-verified');

    // the order: pending, the price of today, a code, a transfer note
    const ok = await org.api(l.token, 'POST', '/api/orders', { body: { planId, courseIds: [c1.courseId] } });
    expect(ok.status).toBe(201);
    expect(ok.body.orderCode).toMatch(/^[A-Z0-9]{6}$/);
    expect(ok.body.amountVnd).toBe(499000);
    expect(ok.body.transferNote).toBe(`E9X ${ok.body.orderCode} ${c1.code}`);
    const code: string = ok.body.orderCode;

    // the administrator changes the price: the order keeps what was agreed; nobody else reads it
    expect((await org.api(a.token, 'PUT', `/api/admin/plans/${planId}`, { body: { ...body, priceVnd: 999000 } })).status).toBe(200);
    const mine = await org.api(l.token, 'GET', `/api/orders/${code}`);
    expect(mine.status).toBe(200);
    expect(mine.body.status).toBe('PENDING_PAYMENT');
    expect(mine.body.amountVnd).toBe(499000);
    expect((await org.api(u.token, 'GET', `/api/orders/${code}`)).status).toBe(404);
    expect((await org.api(a.token, 'GET', `/api/orders/${code}`)).status).toBe(404);

    // an unpaid order older than 7 days expires; the system leaves an audit record that the SYS_ADMIN reads
    const old = await seed.billing.agedOrder({ userId: learner.userId, planId, ageDays: 8 });
    await seed.billing.runJob('expire_orders');
    await expect.poll(async () => (await org.api(l.token, 'GET', `/api/orders/${old.code}`)).body.status, { timeout: 40_000 }).toBe('EXPIRED');
    expect((await org.api(l.token, 'GET', `/api/orders/${code}`)).body.status).toBe('PENDING_PAYMENT');
    const log = await org.api(a.token, 'GET', `/api/admin/audit-log?action=order.expire&targetId=${old.orderId}`);
    expect(log.body.items).toHaveLength(1);
    expect(log.body.items[0].actorKind).toBe('SYSTEM');
    const placed = await org.api(a.token, 'GET', `/api/admin/audit-log?action=order.create&targetType=order&actor=${learner.userId}`);
    expect(placed.body.items.length).toBeGreaterThanOrEqual(1);
  });
});
