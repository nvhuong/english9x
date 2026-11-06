-- P1.3: application login role + entitlement write/read policies.
--
-- Roles and passwords belong to infrastructure (infra/postgres/init/01-roles.sh, Testcontainers init): Flyway runs as
-- the non-superuser owner e9x_owner and the app as e9x_app_login. This migration never sets a password.
-- Where the login role is missing (plain `psql` as superuser, scripts/test-db.sh) it is created NOLOGIN so it cannot
-- be used until infrastructure enables it.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'e9x_app_login') THEN
    CREATE ROLE e9x_app_login NOLOGIN NOSUPERUSER NOBYPASSRLS INHERIT;
  END IF;
  IF NOT pg_has_role('e9x_app_login', 'e9x_app', 'MEMBER') THEN
    GRANT e9x_app TO e9x_app_login;
  END IF;
  -- Fail the migration (and with it startup) if the runtime login could ever bypass RLS.
  IF EXISTS (SELECT 1 FROM pg_roles
              WHERE rolname IN ('e9x_app_login', 'e9x_app', 'e9x_grader')
                AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb)) THEN
    RAISE EXCEPTION 'application roles must not be superuser / BYPASSRLS / CREATEROLE / CREATEDB';
  END IF;
END $$;

-- Facts derived from role data (not from client input). They run as the caller, so they only see rows the caller's
-- RLS lets them see: role_grants and org_memberships expose the caller's own rows and the current org's rows.
CREATE FUNCTION e9x_has_role(p_role text, p_org uuid) RETURNS boolean
  LANGUAGE sql STABLE AS $$
  SELECT EXISTS (SELECT 1 FROM role_grants g
                  WHERE g.user_id = e9x_current_user()
                    AND g.role = p_role
                    AND g.org_id IS NOT DISTINCT FROM p_org) $$;

CREATE FUNCTION e9x_org_has_member(p_org uuid, p_user uuid) RETURNS boolean
  LANGUAGE sql STABLE AS $$
  SELECT p_org IS NOT NULL AND EXISTS (SELECT 1 FROM org_memberships m WHERE m.org_id = p_org AND m.user_id = p_user) $$;

-- app.e9x_staff is also set for AUTHOR / REVIEWER / TEACHER, so it cannot authorise billing writes by itself.
-- English9x SYS_ADMIN = the English9x context flag AND a SYS_ADMIN grant with no org (two independent facts).
CREATE FUNCTION e9x_is_sys_admin() RETURNS boolean
  LANGUAGE sql STABLE AS $$ SELECT e9x_is_staff() AND e9x_has_role('SYS_ADMIN', NULL) $$;

-- ORG_ADMIN of the organisation in the current context: flag AND ORG_ADMIN grant for exactly that org.
CREATE FUNCTION e9x_is_current_org_admin() RETURNS boolean
  LANGUAGE sql STABLE AS $$
  SELECT e9x_current_org() IS NOT NULL AND e9x_is_org_admin() AND e9x_has_role('ORG_ADMIN', e9x_current_org()) $$;

GRANT EXECUTE ON FUNCTION e9x_has_role(text, uuid), e9x_org_has_member(uuid, uuid),
  e9x_is_sys_admin(), e9x_is_current_org_admin() TO e9x_app, e9x_grader;

-- Write policies of role_grants / org_memberships (V202611060001 let ANY e9x_is_staff() session write them, i.e. an
-- AUTHOR or a forged flag could mint themselves SYS_ADMIN and then everything that trusts SYS_ADMIN facts).
-- Now: English9x side = real SYS_ADMIN (flag AND SYS_ADMIN grant); org side = ORG_ADMIN of exactly the current org
-- (flag AND ORG_ADMIN grant). A flag alone never authorises these writes. The first SYS_ADMIN is seeded by operations.
DROP POLICY role_grants_insert ON role_grants;
DROP POLICY role_grants_update ON role_grants;
DROP POLICY role_grants_delete ON role_grants;
DROP POLICY org_memberships_insert ON org_memberships;
DROP POLICY org_memberships_update ON org_memberships;
DROP POLICY org_memberships_delete ON org_memberships;

CREATE POLICY role_grants_insert ON role_grants FOR INSERT WITH CHECK (
  e9x_is_sys_admin() OR (org_id IS NOT NULL AND org_id = e9x_current_org() AND e9x_is_current_org_admin()));
CREATE POLICY role_grants_update ON role_grants FOR UPDATE USING (
  e9x_is_sys_admin() OR (org_id IS NOT NULL AND org_id = e9x_current_org() AND e9x_is_current_org_admin()))
  WITH CHECK (
  e9x_is_sys_admin() OR (org_id IS NOT NULL AND org_id = e9x_current_org() AND e9x_is_current_org_admin()));
CREATE POLICY role_grants_delete ON role_grants FOR DELETE USING (
  e9x_is_sys_admin() OR (org_id IS NOT NULL AND org_id = e9x_current_org() AND e9x_is_current_org_admin()));

CREATE POLICY org_memberships_insert ON org_memberships FOR INSERT WITH CHECK (
  e9x_is_sys_admin() OR (org_id IS NOT NULL AND org_id = e9x_current_org() AND e9x_is_current_org_admin()));
CREATE POLICY org_memberships_update ON org_memberships FOR UPDATE USING (
  e9x_is_sys_admin() OR (org_id IS NOT NULL AND org_id = e9x_current_org() AND e9x_is_current_org_admin()))
  WITH CHECK (
  e9x_is_sys_admin() OR (org_id IS NOT NULL AND org_id = e9x_current_org() AND e9x_is_current_org_admin()));
CREATE POLICY org_memberships_delete ON org_memberships FOR DELETE USING (
  e9x_is_sys_admin() OR (org_id IS NOT NULL AND org_id = e9x_current_org() AND e9x_is_current_org_admin()));

-- Seat entitlements are issued by one organisation; without this column an admin of A could read and change seats
-- issued by B for a user who belongs to both.
ALTER TABLE entitlements ADD COLUMN source_org_id uuid REFERENCES organizations(id);
ALTER TABLE entitlements ADD CONSTRAINT entitlements_seat_org_ck
  CHECK (source_seat_id IS NULL OR source_org_id IS NOT NULL);

-- Entitlements. A learner can never write: every INSERT/UPDATE policy needs SYS_ADMIN or ORG_ADMIN facts.
-- There is deliberately no DELETE policy: revoking ends the entitlement (UPDATE valid_until).

-- P5 order confirmation: English9x SYS_ADMIN creates / adjusts order-sourced entitlements.
CREATE POLICY entitlements_select_sys_admin ON entitlements FOR SELECT USING (e9x_is_sys_admin());
CREATE POLICY entitlements_insert_order ON entitlements FOR INSERT
  WITH CHECK (source_order_id IS NOT NULL AND e9x_is_sys_admin());
CREATE POLICY entitlements_update_order ON entitlements FOR UPDATE
  USING (source_order_id IS NOT NULL AND e9x_is_sys_admin())
  WITH CHECK (source_order_id IS NOT NULL AND e9x_is_sys_admin());

-- P5.3 seat assignment / revocation: ORG_ADMIN of the current org, for members of that org, seat rows issued by
-- that org (source_org_id) only.
CREATE POLICY entitlements_insert_seat ON entitlements FOR INSERT
  WITH CHECK (source_seat_id IS NOT NULL AND source_org_id = e9x_current_org() AND e9x_is_current_org_admin()
              AND e9x_org_has_member(e9x_current_org(), user_id));
CREATE POLICY entitlements_update_seat ON entitlements FOR UPDATE
  USING (source_seat_id IS NOT NULL AND source_org_id = e9x_current_org() AND e9x_is_current_org_admin()
         AND e9x_org_has_member(e9x_current_org(), user_id))
  WITH CHECK (source_seat_id IS NOT NULL AND source_org_id = e9x_current_org() AND e9x_is_current_org_admin()
              AND e9x_org_has_member(e9x_current_org(), user_id));

-- spec §8: ORG_ADMIN / TEACHER of the current org see the seat entitlements of that org's members. Personal
-- (order-sourced) entitlements of a member stay private to the member and English9x SYS_ADMIN.
-- Only seats issued by the current org (source_org_id).
CREATE POLICY entitlements_select_org_staff ON entitlements FOR SELECT USING (
  source_seat_id IS NOT NULL
  AND source_org_id = e9x_current_org()
  AND e9x_current_org() IS NOT NULL
  AND (e9x_has_role('ORG_ADMIN', e9x_current_org()) OR e9x_has_role('TEACHER', e9x_current_org()))
  AND e9x_org_has_member(e9x_current_org(), user_id));
