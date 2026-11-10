-- P5.1 (spec section 11, F39/F41): plans, orders and what the background jobs of billing may read. The `entitlements` table is the baseline's
-- (V202611060001/V202611060002, P1.3) and is not touched here: P5.2 writes order-sourced rows when an administrator confirms an order.
--
-- Who may do what (all policies are TO e9x_app unless said otherwise; the worker role cannot even evaluate the e9x_* helper functions):
--   * plans: the price list. Every signed-in person reads the plans on sale; a real SYS_ADMIN (flag AND grant, e9x_is_sys_admin()) reads and
--     writes all of them. There is no DELETE: a plan that stops selling is deactivated (orders keep pointing at it).
--   * orders / order_items: yours only (user_id = e9x_current_user()); a SYS_ADMIN also reads every order (P5.2's queue). A person INSERTS
--     only a PENDING_PAYMENT order for themself and gets no UPDATE or DELETE: confirming, rejecting (P5.2) and expiring (below) are not theirs.
--     order_items carries user_id as well, tied to the order by a composite key, so the item rows are RLS-protected on their own and cannot
--     name another person's order.
--   * The price is locked at order time: amount_vnd and plan_months are COPIED onto the order, nothing reads them from the plan later.
--   * The worker (e9x_worker_login) gets NO table grant here. It reaches billing data only through two SECURITY DEFINER functions with a
--     narrow answer (billing_expire_orders, billing_entitlements_due), EXECUTE for e9x_worker only: the same pattern as the notification
--     delivery functions. Table grants plus policies would have meant evaluating the e9x_* policy helpers as the worker, which it may not.
--
-- orders.code is the 6-character order code of the transfer note (domain Codes.isValidOrderCode). It is globally unique; the application
-- generates it at random and retries on a collision (the unique index is the arbiter under concurrency).

CREATE TABLE plans (
  id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  name        text        NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 100),
  price_vnd   bigint      NOT NULL CHECK (price_vnd > 0),
  months      integer     NOT NULL CHECK (months BETWEEN 1 AND 60),
  max_courses integer     NOT NULL CHECK (max_courses BETWEEN 1 AND 50),
  active      boolean     NOT NULL DEFAULT true,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE orders (
  id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  code          char(6)     NOT NULL CHECK (code ~ '^[A-Z0-9]{6}$'),
  user_id       uuid        NOT NULL REFERENCES users(id),
  plan_id       uuid        NOT NULL REFERENCES plans(id),
  plan_months   integer     NOT NULL CHECK (plan_months >= 1),   -- locked with the price: the plan may change, the entitlement length does not
  amount_vnd    bigint      NOT NULL CHECK (amount_vnd > 0),     -- locked at order time
  status        text        NOT NULL DEFAULT 'PENDING_PAYMENT'
                CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'REJECTED', 'EXPIRED')),
  created_at    timestamptz NOT NULL DEFAULT now(),
  expired_at    timestamptz,
  confirmed_at  timestamptz,
  bank_ref      text        CHECK (char_length(bank_ref) <= 100),
  reject_reason text        CHECK (char_length(reject_reason) <= 500),
  CONSTRAINT orders_code_uq UNIQUE (code),
  CONSTRAINT orders_id_user_uq UNIQUE (id, user_id),
  CONSTRAINT orders_state_ck CHECK (
       (status = 'PENDING_PAYMENT' AND expired_at IS NULL AND confirmed_at IS NULL AND reject_reason IS NULL)
    OR (status = 'EXPIRED'         AND expired_at IS NOT NULL AND confirmed_at IS NULL AND reject_reason IS NULL)
    OR (status = 'CONFIRMED'       AND confirmed_at IS NOT NULL AND expired_at IS NULL AND reject_reason IS NULL)
    OR (status = 'REJECTED'        AND reject_reason IS NOT NULL AND confirmed_at IS NULL AND expired_at IS NULL))
);
CREATE INDEX orders_user_idx ON orders (user_id, created_at DESC);
CREATE INDEX orders_pending_idx ON orders (created_at) WHERE status = 'PENDING_PAYMENT';

CREATE TABLE order_items (
  order_id  uuid NOT NULL,
  user_id   uuid NOT NULL,
  course_id uuid NOT NULL REFERENCES courses(id),
  course_code char(6) NOT NULL,   -- as it was when ordered: the transfer note and the order screen do not depend on the course staying visible
  PRIMARY KEY (order_id, course_id),
  FOREIGN KEY (order_id, user_id) REFERENCES orders (id, user_id)
);
CREATE INDEX order_items_user_idx ON order_items (user_id);

ALTER TABLE plans       ENABLE ROW LEVEL SECURITY;
ALTER TABLE orders      ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_items ENABLE ROW LEVEL SECURITY;

CREATE POLICY plans_select ON plans FOR SELECT TO e9x_app USING (active OR e9x_is_sys_admin());
CREATE POLICY plans_insert ON plans FOR INSERT TO e9x_app WITH CHECK (e9x_is_sys_admin());
CREATE POLICY plans_update ON plans FOR UPDATE TO e9x_app USING (e9x_is_sys_admin()) WITH CHECK (e9x_is_sys_admin());

CREATE POLICY orders_select ON orders FOR SELECT TO e9x_app
  USING (user_id = e9x_current_user() OR e9x_is_sys_admin());
CREATE POLICY orders_insert ON orders FOR INSERT TO e9x_app
  WITH CHECK (user_id = e9x_current_user() AND status = 'PENDING_PAYMENT'
              AND expired_at IS NULL AND confirmed_at IS NULL AND bank_ref IS NULL AND reject_reason IS NULL);

CREATE POLICY order_items_select ON order_items FOR SELECT TO e9x_app
  USING (user_id = e9x_current_user() OR e9x_is_sys_admin());
CREATE POLICY order_items_insert ON order_items FOR INSERT TO e9x_app
  WITH CHECK (user_id = e9x_current_user());

REVOKE ALL ON plans, orders, order_items FROM PUBLIC;
GRANT SELECT, INSERT ON plans TO e9x_app;
GRANT UPDATE (name, price_vnd, months, max_courses, active, updated_at) ON plans TO e9x_app;
GRANT SELECT ON orders, order_items TO e9x_app;
GRANT INSERT (code, user_id, plan_id, plan_months, amount_vnd, created_at) ON orders TO e9x_app;
GRANT INSERT ON order_items TO e9x_app;

-- ---- the worker's two narrow doors ---------------------------------------------------------------------------------------------------
-- expire_orders: PENDING_PAYMENT orders created at or before p_cutoff become EXPIRED. Idempotent (an expired order is no longer pending),
-- returns only the ids it changed, which the handler records in the audit log in the same worker transaction. It cannot name an order, a user
-- or a target status.
CREATE FUNCTION billing_expire_orders(p_cutoff timestamptz, p_now timestamptz)
  RETURNS TABLE (r_order_id uuid)
  LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  UPDATE orders SET status = 'EXPIRED', expired_at = p_now
   WHERE status = 'PENDING_PAYMENT' AND created_at <= p_cutoff
  RETURNING id $$;
REVOKE ALL ON FUNCTION billing_expire_orders(timestamptz, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION billing_expire_orders(timestamptz, timestamptz) TO e9x_worker;

-- plan_expiry_reminder / expire_entitlements: the order-sourced entitlements whose end falls in the half-open window (p_after, p_until].
-- The job notifies each learner (dedupe key per entitlement), so it needs the id, the person, the course and the end, and nothing else.
-- Seat entitlements (an organisation's licence) are not B2C plans and are left out; P5.3 decides whether they get a reminder.
CREATE FUNCTION billing_entitlements_due(p_after timestamptz, p_until timestamptz)
  RETURNS TABLE (r_id uuid, r_user_id uuid, r_course_id uuid, r_valid_until timestamptz)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT e.id, e.user_id, e.course_id, e.valid_until
    FROM entitlements e
   WHERE e.source_order_id IS NOT NULL AND e.valid_until > p_after AND e.valid_until <= p_until
   ORDER BY e.valid_until, e.id $$;
REVOKE ALL ON FUNCTION billing_entitlements_due(timestamptz, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION billing_entitlements_due(timestamptz, timestamptz) TO e9x_worker;
