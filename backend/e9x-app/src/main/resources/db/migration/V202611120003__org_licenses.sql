-- P5.3 (spec section 11, F42/F43): organisation seat licences and seat assignment, with the rules in the DATABASE.
--
-- A licence gives one organisation N seats for M months over a fixed set of courses. An ORG_ADMIN assigns a seat to a MEMBER of the
-- organisation for some of the licence's courses; the seat writes SEAT-sourced entitlements that end with the licence. Revoking a seat
-- ends them at once and gives the seat back.
--
--   * org_licenses: written by a real SYS_ADMIN only (e9x_is_sys_admin()). CONTRACT licences are ACTIVE from creation; QR licences start
--     PENDING_PAYMENT, tied to an order whose payer is an ORG_ADMIN of the organisation (orders.license_id), and become ACTIVE when the
--     SYS_ADMIN confirms that order (P5.2 flow). The price and the length are copied onto the licence when it is created and never recomputed.
--     The ORG_ADMIN of the organisation reads its licences.
--   * org_license_courses: the courses a licence covers. Immutable once written (no UPDATE / DELETE grant).
--   * seat_assignments: one row per person holding a seat. ORG_ADMIN of the CURRENT organisation inserts (for that organisation only) and
--     may set revoked_at, nothing else. Capacity, membership and the state of the licence are enforced by seat_assignments_guard (below),
--     which locks the licence row: two concurrent assignments for the last seat serialise there and exactly one succeeds.
--   * entitlements of source SEAT: source_seat_id now REFERENCES seat_assignments, and entitlements_seat_guard checks that the seat is live
--     and belongs to that person and organisation, that the course is one of the licence's, and that the entitlement does not outlive the licence (the service sets it to exactly the licence's end);
--     an UPDATE may only shorten it. Revoking a seat (revoked_at) ends its entitlements in the same statement (seat_assignments_revoked).
--
-- The guards are AFTER ROW triggers (so a refusal by the Row-Level Security policies of the table is still the first answer) and SECURITY DEFINER with a pinned search_path, REVOKEd from PUBLIC (a trigger needs no EXECUTE grant): they must read and lock
-- rows the ORG_ADMIN's own Row-Level Security does not show, and they decide nothing from client input.

CREATE TABLE org_licenses (
  id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id      uuid        NOT NULL REFERENCES organizations(id),
  seats       integer     NOT NULL CHECK (seats BETWEEN 1 AND 100000),
  months      integer     NOT NULL CHECK (months BETWEEN 1 AND 60),
  price_vnd   bigint      NOT NULL CHECK (price_vnd > 0),
  activation  text        NOT NULL CHECK (activation IN ('QR', 'CONTRACT')),
  contract_no text        CHECK (char_length(btrim(contract_no)) BETWEEN 1 AND 100),
  status      text        NOT NULL CHECK (status IN ('PENDING_PAYMENT', 'ACTIVE', 'CANCELLED')),
  valid_from  timestamptz,
  valid_until timestamptz,
  created_at  timestamptz NOT NULL DEFAULT now(),
  created_by  uuid        NOT NULL REFERENCES users(id),
  CONSTRAINT org_licenses_id_org_uq UNIQUE (id, org_id),
  CONSTRAINT org_licenses_contract_ck CHECK ((activation = 'CONTRACT') = (contract_no IS NOT NULL)),
  CONSTRAINT org_licenses_state_ck CHECK (
       (status = 'ACTIVE' AND valid_from IS NOT NULL AND valid_until IS NOT NULL AND valid_until > valid_from)
    OR (status IN ('PENDING_PAYMENT', 'CANCELLED') AND valid_from IS NULL AND valid_until IS NULL)),
  CONSTRAINT org_licenses_activation_state_ck CHECK (activation = 'QR' OR status = 'ACTIVE')   -- a CONTRACT licence is active from the start
);
CREATE INDEX org_licenses_org_idx ON org_licenses (org_id, created_at DESC);

CREATE TABLE org_license_courses (
  license_id uuid NOT NULL,
  org_id     uuid NOT NULL,   -- the licence's organisation, so every tenant table says whose it is
  course_id  uuid NOT NULL REFERENCES courses(id),
  PRIMARY KEY (license_id, course_id),
  FOREIGN KEY (license_id, org_id) REFERENCES org_licenses (id, org_id)
);

CREATE TABLE seat_assignments (
  id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  license_id  uuid        NOT NULL,
  org_id      uuid        NOT NULL,
  user_id     uuid        NOT NULL REFERENCES users(id),
  assigned_at timestamptz NOT NULL DEFAULT now(),
  assigned_by uuid        NOT NULL REFERENCES users(id),
  revoked_at  timestamptz,
  FOREIGN KEY (license_id, org_id) REFERENCES org_licenses (id, org_id)
);
CREATE UNIQUE INDEX seat_assignments_live_uq ON seat_assignments (license_id, user_id) WHERE revoked_at IS NULL;
CREATE INDEX seat_assignments_user_idx ON seat_assignments (user_id);

ALTER TABLE entitlements ADD CONSTRAINT entitlements_source_seat_fk FOREIGN KEY (source_seat_id) REFERENCES seat_assignments (id);
CREATE INDEX entitlements_seat_idx ON entitlements (source_seat_id) WHERE source_seat_id IS NOT NULL;

-- ---- orders may belong to a licence instead of a plan --------------------------------------------------------------------------------
ALTER TABLE orders ADD COLUMN license_id uuid REFERENCES org_licenses(id);
ALTER TABLE orders ALTER COLUMN plan_id DROP NOT NULL;
ALTER TABLE orders ADD CONSTRAINT orders_plan_or_license_ck CHECK ((plan_id IS NULL) <> (license_id IS NULL));
CREATE UNIQUE INDEX orders_license_uq ON orders (license_id) WHERE license_id IS NOT NULL;

ALTER TABLE org_licenses        ENABLE ROW LEVEL SECURITY;
ALTER TABLE org_license_courses ENABLE ROW LEVEL SECURITY;
ALTER TABLE seat_assignments    ENABLE ROW LEVEL SECURITY;

CREATE POLICY org_licenses_select ON org_licenses FOR SELECT TO e9x_app
  USING (e9x_is_sys_admin() OR (org_id = e9x_current_org() AND e9x_is_current_org_admin()));
CREATE POLICY org_licenses_insert ON org_licenses FOR INSERT TO e9x_app
  WITH CHECK (e9x_is_sys_admin() AND created_by = e9x_current_user()
              AND ((activation = 'CONTRACT' AND status = 'ACTIVE') OR (activation = 'QR' AND status = 'PENDING_PAYMENT')));   -- a QR licence never starts active: its order does that
-- a SYS_ADMIN decides a QR licence once: PENDING_PAYMENT to ACTIVE or CANCELLED
CREATE POLICY org_licenses_decide ON org_licenses FOR UPDATE TO e9x_app
  USING (e9x_is_sys_admin() AND status = 'PENDING_PAYMENT')
  WITH CHECK (e9x_is_sys_admin() AND status IN ('ACTIVE', 'CANCELLED'));

CREATE POLICY org_license_courses_select ON org_license_courses FOR SELECT TO e9x_app
  USING (EXISTS (SELECT 1 FROM org_licenses l WHERE l.id = license_id));   -- exactly the licences the caller may read
CREATE POLICY org_license_courses_insert ON org_license_courses FOR INSERT TO e9x_app
  WITH CHECK (e9x_is_sys_admin());

CREATE POLICY seat_assignments_select ON seat_assignments FOR SELECT TO e9x_app
  USING (e9x_is_sys_admin() OR (org_id = e9x_current_org() AND e9x_is_current_org_admin()));
CREATE POLICY seat_assignments_insert ON seat_assignments FOR INSERT TO e9x_app
  WITH CHECK (org_id = e9x_current_org() AND e9x_is_current_org_admin() AND revoked_at IS NULL AND assigned_by = e9x_current_user());
CREATE POLICY seat_assignments_revoke ON seat_assignments FOR UPDATE TO e9x_app
  USING (org_id = e9x_current_org() AND e9x_is_current_org_admin())
  WITH CHECK (org_id = e9x_current_org() AND e9x_is_current_org_admin());

REVOKE ALL ON org_licenses, org_license_courses, seat_assignments FROM PUBLIC;
GRANT SELECT, INSERT ON org_licenses TO e9x_app;
GRANT UPDATE (status, valid_from, valid_until) ON org_licenses TO e9x_app;
GRANT SELECT, INSERT ON org_license_courses TO e9x_app;
GRANT SELECT ON seat_assignments TO e9x_app;
GRANT INSERT (id, license_id, org_id, user_id, assigned_at, assigned_by) ON seat_assignments TO e9x_app;
GRANT UPDATE (revoked_at) ON seat_assignments TO e9x_app;

-- orders: a learner places plan orders only; a SYS_ADMIN places the order of a licence for its payer (an ORG_ADMIN), at the licence's price.
DROP POLICY orders_insert ON orders;
CREATE POLICY orders_insert ON orders FOR INSERT TO e9x_app
  WITH CHECK (user_id = e9x_current_user() AND status = 'PENDING_PAYMENT' AND license_id IS NULL
              AND expired_at IS NULL AND confirmed_at IS NULL AND bank_ref IS NULL AND reject_reason IS NULL);
CREATE POLICY orders_insert_license ON orders FOR INSERT TO e9x_app
  WITH CHECK (e9x_is_sys_admin() AND status = 'PENDING_PAYMENT' AND license_id IS NOT NULL AND plan_id IS NULL
              AND expired_at IS NULL AND confirmed_at IS NULL AND bank_ref IS NULL AND reject_reason IS NULL
              AND EXISTS (SELECT 1 FROM org_licenses l
                           WHERE l.id = license_id AND l.status = 'PENDING_PAYMENT' AND l.activation = 'QR'
                             AND l.price_vnd = amount_vnd AND l.months = plan_months
                             AND EXISTS (SELECT 1 FROM role_grants g WHERE g.user_id = orders.user_id AND g.role = 'ORG_ADMIN' AND g.org_id = l.org_id)));
CREATE POLICY order_items_insert_license ON order_items FOR INSERT TO e9x_app
  WITH CHECK (e9x_is_sys_admin() AND EXISTS (SELECT 1 FROM orders o WHERE o.id = order_id AND o.user_id = order_items.user_id AND o.license_id IS NOT NULL)
              AND EXISTS (SELECT 1 FROM orders o JOIN org_license_courses c ON c.license_id = o.license_id
                           WHERE o.id = order_id AND c.course_id = order_items.course_id));
GRANT INSERT (license_id) ON orders TO e9x_app;

-- the expiry job also cancels the licence of an order it expires
CREATE OR REPLACE FUNCTION billing_expire_orders(p_cutoff timestamptz, p_now timestamptz)
  RETURNS TABLE (r_order_id uuid)
  LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  WITH expired AS (
    UPDATE orders SET status = 'EXPIRED', expired_at = p_now
     WHERE status = 'PENDING_PAYMENT' AND created_at <= p_cutoff
    RETURNING id, license_id),
  cancelled AS (
    UPDATE org_licenses SET status = 'CANCELLED'
     WHERE status = 'PENDING_PAYMENT' AND id IN (SELECT license_id FROM expired WHERE license_id IS NOT NULL)
    RETURNING id)
  SELECT id FROM expired $$;

-- ---- guards ------------------------------------------------------------------------------------------------------------------------
-- A seat row (AFTER trigger: the row is counted already): live licence of that organisation, a member of the organisation, a free seat. Locks the licence row so that concurrent
-- assignments are counted one after the other. An UPDATE may only set revoked_at once (null to a time).
CREATE FUNCTION seat_assignments_guard() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  lic org_licenses%ROWTYPE;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF OLD.revoked_at IS NOT NULL OR NEW.revoked_at IS NULL
       OR (NEW.id, NEW.license_id, NEW.org_id, NEW.user_id, NEW.assigned_at, NEW.assigned_by)
          IS DISTINCT FROM (OLD.id, OLD.license_id, OLD.org_id, OLD.user_id, OLD.assigned_at, OLD.assigned_by) THEN
      RAISE EXCEPTION 'a seat can only be revoked, once' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
  END IF;
  SELECT * INTO lic FROM org_licenses WHERE id = NEW.license_id FOR UPDATE;
  IF NOT FOUND OR lic.org_id <> NEW.org_id OR lic.status <> 'ACTIVE' OR now() < lic.valid_from OR now() >= lic.valid_until THEN
    RAISE EXCEPTION 'the licence is not active for this organisation' USING ERRCODE = 'check_violation';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM org_memberships m WHERE m.org_id = NEW.org_id AND m.user_id = NEW.user_id) THEN
    RAISE EXCEPTION 'the user is not a member of the organisation' USING ERRCODE = 'check_violation';
  END IF;
  -- the new row is already counted (an AFTER trigger): more live seats than the licence has means this one is one too many
  IF (SELECT count(*) FROM seat_assignments s WHERE s.license_id = NEW.license_id AND s.revoked_at IS NULL) > lic.seats THEN
    RAISE EXCEPTION 'seat capacity exceeded' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION seat_assignments_guard() FROM PUBLIC;
CREATE TRIGGER seat_assignments_guard AFTER INSERT OR UPDATE ON seat_assignments
  FOR EACH ROW EXECUTE FUNCTION seat_assignments_guard();

-- The lock comes FIRST (BEFORE trigger): the foreign key of the new row takes a shared lock on the licence row, and two inserts that both hold it
-- and then ask for the exclusive one would deadlock. Only the ORG_ADMIN of the licence's own organisation takes it (anybody else is refused by
-- the policy, and must not be able to hold a licence's lock meanwhile).
CREATE FUNCTION seat_assignments_lock() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  IF NEW.org_id = e9x_current_org() AND e9x_is_current_org_admin() THEN
    PERFORM 1 FROM org_licenses WHERE id = NEW.license_id FOR UPDATE;
  END IF;
  RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION seat_assignments_lock() FROM PUBLIC;
CREATE TRIGGER seat_assignments_lock BEFORE INSERT ON seat_assignments
  FOR EACH ROW EXECUTE FUNCTION seat_assignments_lock();

-- Revoking a seat ends what it granted, at the same instant (never before the entitlement began: valid_until > valid_from is a CHECK).
CREATE FUNCTION seat_assignments_revoked() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  UPDATE entitlements SET valid_until = greatest(NEW.revoked_at, valid_from + interval '1 millisecond')
   WHERE source_seat_id = NEW.id AND valid_until > NEW.revoked_at;
  RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION seat_assignments_revoked() FROM PUBLIC;
CREATE TRIGGER seat_assignments_revoked AFTER UPDATE OF revoked_at ON seat_assignments
  FOR EACH ROW WHEN (OLD.revoked_at IS NULL AND NEW.revoked_at IS NOT NULL) EXECUTE FUNCTION seat_assignments_revoked();

CREATE FUNCTION entitlements_seat_guard() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  seat seat_assignments%ROWTYPE;
  lic org_licenses%ROWTYPE;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    -- a seat entitlement is only ever shortened (revoking); who it is for, what it covers and where it comes from never change
    IF (NEW.id, NEW.user_id, NEW.course_id, NEW.valid_from, NEW.source_order_id, NEW.source_seat_id, NEW.source_org_id)
         IS DISTINCT FROM (OLD.id, OLD.user_id, OLD.course_id, OLD.valid_from, OLD.source_order_id, OLD.source_seat_id, OLD.source_org_id)
       OR NEW.valid_until > OLD.valid_until THEN
      RAISE EXCEPTION 'a seat entitlement can only be shortened' USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
  END IF;
  SELECT * INTO seat FROM seat_assignments WHERE id = NEW.source_seat_id FOR SHARE;
  IF NOT FOUND OR seat.revoked_at IS NOT NULL OR seat.user_id <> NEW.user_id OR seat.org_id IS DISTINCT FROM NEW.source_org_id THEN
    RAISE EXCEPTION 'the seat is not a live seat of this user and organisation' USING ERRCODE = 'check_violation';
  END IF;
  SELECT * INTO lic FROM org_licenses WHERE id = seat.license_id;
  IF lic.status <> 'ACTIVE' OR NEW.valid_until > lic.valid_until THEN
    RAISE EXCEPTION 'a seat entitlement ends with its licence' USING ERRCODE = 'check_violation';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM org_license_courses c WHERE c.license_id = seat.license_id AND c.course_id = NEW.course_id) THEN
    RAISE EXCEPTION 'the course is not part of the licence' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$;
REVOKE ALL ON FUNCTION entitlements_seat_guard() FROM PUBLIC;
CREATE TRIGGER entitlements_seat_guard_ins AFTER INSERT ON entitlements
  FOR EACH ROW WHEN (NEW.source_seat_id IS NOT NULL) EXECUTE FUNCTION entitlements_seat_guard();
CREATE TRIGGER entitlements_seat_guard_upd AFTER UPDATE ON entitlements
  FOR EACH ROW WHEN (OLD.source_seat_id IS NOT NULL OR NEW.source_seat_id IS NOT NULL) EXECUTE FUNCTION entitlements_seat_guard();
