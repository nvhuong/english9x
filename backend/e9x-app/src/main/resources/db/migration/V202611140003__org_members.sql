-- P6.1 members, invitations to a class, join codes, leaving / removal (F44).
--
--  * join_codes and org_join_code_redeem already exist (V202611080001, V202611080006): the code is stored only as its SHA-256 digest. What
--    this migration ADDS is: class_members (who is in which class, written here only by the definer functions below; P6.2 adds the
--    class-management policies), invitations.class_id (a bulk import may name a class), the redeem function now records the class and
--    says which code was used, the accept function records the invitation's class, and members_remove.
--  * members_remove(org, user): a learner leaves, or an ORG_ADMIN / SYS_ADMIN removes a member. A learner cannot delete memberships under
--    Row-Level Security (only admins can), so this is a narrow SECURITY DEFINER function: the actor is the session's app.user_id, it decides
--    the authority itself, takes the per-organisation advisory lock RoleGrantService takes (same key), keeps the last ORG_ADMIN, ends the
--    person's live seats (the seat trigger ends their entitlements in the same statement), deletes class memberships, grants and the
--    membership. Exam attempts and the like carry owner_org_id and stay: the organisation keeps its read-only history.
--  * seat_assignments_guard now locks the membership row FOR SHARE and members_remove locks it FOR UPDATE first: a seat cannot be handed
--    to somebody who is being removed in a concurrent transaction.

-- ---- class membership --------------------------------------------------------------------------------------------------
ALTER TABLE classes ADD CONSTRAINT classes_id_org_uq UNIQUE (id, owner_org_id);

CREATE TABLE class_members (
  class_id uuid NOT NULL,
  org_id   uuid NOT NULL,
  user_id  uuid NOT NULL,
  added_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (class_id, user_id),
  FOREIGN KEY (class_id, org_id) REFERENCES classes (id, owner_org_id),
  FOREIGN KEY (org_id, user_id) REFERENCES org_memberships (org_id, user_id)
);
CREATE INDEX class_members_user_idx ON class_members (user_id);
ALTER TABLE class_members ENABLE ROW LEVEL SECURITY;
CREATE POLICY class_members_select ON class_members FOR SELECT USING (
  user_id = e9x_current_user()
  OR (org_id = e9x_current_org() AND e9x_is_current_org_admin())
  OR e9x_is_sys_admin());
REVOKE ALL ON class_members FROM PUBLIC;
GRANT SELECT ON class_members TO e9x_app;

-- ---- an invitation may name a class (bulk import) ------------------------------------------------------------------------
ALTER TABLE invitations ADD COLUMN class_id uuid;
ALTER TABLE invitations ADD FOREIGN KEY (class_id, org_id) REFERENCES classes (id, owner_org_id);
GRANT SELECT (class_id) ON invitations TO e9x_app;
GRANT INSERT (class_id) ON invitations TO e9x_app;

CREATE OR REPLACE FUNCTION org_invitation_accept(p_token_hash bytea, p_now timestamptz)
  RETURNS TABLE (r_outcome text, r_org_id uuid, r_invitation_id uuid, r_grants jsonb)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_user uuid := e9x_current_user();
  inv invitations%ROWTYPE;
  v_email text;
  v_verified timestamptz;
  g jsonb;
BEGIN
  IF v_user IS NULL OR p_token_hash IS NULL THEN
    RETURN QUERY SELECT 'INVALID'::text, NULL::uuid, NULL::uuid, NULL::jsonb;
    RETURN;
  END IF;
  SELECT * INTO inv FROM invitations i WHERE i.token_hash = p_token_hash FOR UPDATE;
  IF NOT FOUND OR inv.accepted_at IS NOT NULL OR inv.revoked_at IS NOT NULL OR inv.expires_at <= p_now
     OR NOT e9x_inviter_holds_authority(inv.invited_by, inv.org_id) THEN
    RETURN QUERY SELECT 'INVALID'::text, NULL::uuid, NULL::uuid, NULL::jsonb;
    RETURN;
  END IF;
  SELECT lower(u.email), u.email_verified_at INTO v_email, v_verified FROM users u WHERE u.id = v_user;
  IF NOT FOUND THEN
    RETURN QUERY SELECT 'INVALID'::text, NULL::uuid, NULL::uuid, NULL::jsonb;
    RETURN;
  END IF;
  IF v_email <> inv.email THEN
    RETURN QUERY SELECT 'EMAIL_MISMATCH'::text, NULL::uuid, NULL::uuid, NULL::jsonb;
    RETURN;
  END IF;
  IF v_verified IS NULL THEN
    RETURN QUERY SELECT 'EMAIL_NOT_VERIFIED'::text, NULL::uuid, NULL::uuid, NULL::jsonb;
    RETURN;
  END IF;

  IF inv.org_id IS NOT NULL THEN
    INSERT INTO org_memberships(org_id, user_id) VALUES (inv.org_id, v_user) ON CONFLICT DO NOTHING;
  END IF;
  FOR g IN SELECT value FROM jsonb_array_elements(inv.grants) LOOP
    INSERT INTO role_grants(user_id, org_id, exam_code, role)
    VALUES (v_user, inv.org_id, g->>'exam', g->>'role')
    ON CONFLICT ON CONSTRAINT role_grants_scope_uq DO NOTHING;
  END LOOP;
  IF inv.class_id IS NOT NULL AND inv.org_id IS NOT NULL THEN
    INSERT INTO class_members(class_id, org_id, user_id) VALUES (inv.class_id, inv.org_id, v_user) ON CONFLICT DO NOTHING;
  END IF;
  UPDATE invitations SET accepted_at = p_now, accepted_by = v_user WHERE id = inv.id;
  RETURN QUERY SELECT 'ACCEPTED'::text, inv.org_id, inv.id, inv.grants;
END $$;

-- ---- redeeming a join code: also the class, and which code ---------------------------------------------------------------
DROP FUNCTION org_join_code_redeem(bytea, timestamptz);
CREATE FUNCTION org_join_code_redeem(p_code_hash bytea, p_now timestamptz)
  RETURNS TABLE (r_outcome text, r_org_id uuid, r_class_id uuid, r_code_id uuid)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_user uuid := e9x_current_user();
  jc join_codes%ROWTYPE;
BEGIN
  IF v_user IS NULL OR p_code_hash IS NULL OR NOT EXISTS (SELECT 1 FROM users u WHERE u.id = v_user) THEN
    RETURN QUERY SELECT 'INVALID'::text, NULL::uuid, NULL::uuid, NULL::uuid;
    RETURN;
  END IF;
  SELECT * INTO jc FROM join_codes c WHERE c.code_hash = p_code_hash FOR UPDATE;
  IF NOT FOUND OR jc.revoked_at IS NOT NULL OR jc.expires_at <= p_now
     OR (jc.max_uses IS NOT NULL AND jc.used_count >= jc.max_uses) THEN
    RETURN QUERY SELECT 'INVALID'::text, NULL::uuid, NULL::uuid, NULL::uuid;
    RETURN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM users u WHERE u.id = v_user AND u.email_verified_at IS NOT NULL) THEN
    RETURN QUERY SELECT 'EMAIL_NOT_VERIFIED'::text, NULL::uuid, NULL::uuid, NULL::uuid;
    RETURN;
  END IF;
  IF EXISTS (SELECT 1 FROM org_memberships m WHERE m.org_id = jc.org_id AND m.user_id = v_user) THEN
    RETURN QUERY SELECT 'ALREADY_MEMBER'::text, jc.org_id, NULL::uuid, jc.id;
    RETURN;
  END IF;
  INSERT INTO org_memberships(org_id, user_id) VALUES (jc.org_id, v_user);
  INSERT INTO role_grants(user_id, org_id, exam_code, role) VALUES (v_user, jc.org_id, NULL, 'LEARNER')
    ON CONFLICT ON CONSTRAINT role_grants_scope_uq DO NOTHING;
  IF jc.class_id IS NOT NULL THEN
    INSERT INTO class_members(class_id, org_id, user_id) VALUES (jc.class_id, jc.org_id, v_user) ON CONFLICT DO NOTHING;
  END IF;
  UPDATE join_codes SET used_count = used_count + 1 WHERE id = jc.id;
  RETURN QUERY SELECT 'JOINED'::text, jc.org_id, jc.class_id, jc.id;
END $$;
REVOKE ALL ON FUNCTION org_join_code_redeem(bytea, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION org_join_code_redeem(bytea, timestamptz) TO e9x_app;

-- ---- seats cannot go to somebody being removed -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION seat_assignments_guard() RETURNS trigger
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
  PERFORM 1 FROM org_memberships m WHERE m.org_id = NEW.org_id AND m.user_id = NEW.user_id FOR SHARE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'the user is not a member of the organisation' USING ERRCODE = 'check_violation';
  END IF;
  IF (SELECT count(*) FROM seat_assignments s WHERE s.license_id = NEW.license_id AND s.revoked_at IS NULL) > lic.seats THEN
    RAISE EXCEPTION 'seat capacity exceeded' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END $$;

-- ---- leaving / removing a member ---------------------------------------------------------------------------------------------
CREATE FUNCTION members_remove(p_org uuid, p_user uuid, p_now timestamptz)
  RETURNS TABLE (r_outcome text, r_seat_ids uuid[], r_class_ids uuid[], r_roles text[])
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_actor uuid := e9x_current_user();
  v_seats uuid[];
  v_classes uuid[];
  v_roles text[];
BEGIN
  IF v_actor IS NULL OR p_org IS NULL OR p_user IS NULL THEN
    RETURN QUERY SELECT 'FORBIDDEN'::text, NULL::uuid[], NULL::uuid[], NULL::text[];
    RETURN;
  END IF;
  -- Same lock and key as RoleGrantService.lockOrg: removing a member and replacing grants are one queue per organisation.
  PERFORM pg_advisory_xact_lock(hashtextextended(p_org::text, 7));
  IF NOT (v_actor = p_user
          OR (e9x_current_org() = p_org AND e9x_is_current_org_admin())
          OR e9x_is_sys_admin()) THEN
    RETURN QUERY SELECT 'FORBIDDEN'::text, NULL::uuid[], NULL::uuid[], NULL::text[];
    RETURN;
  END IF;
  PERFORM 1 FROM org_memberships m WHERE m.org_id = p_org AND m.user_id = p_user FOR UPDATE;
  IF NOT FOUND THEN
    RETURN QUERY SELECT 'NOT_FOUND'::text, NULL::uuid[], NULL::uuid[], NULL::text[];
    RETURN;
  END IF;
  IF EXISTS (SELECT 1 FROM role_grants g WHERE g.org_id = p_org AND g.user_id = p_user AND g.role = 'ORG_ADMIN')
     AND NOT EXISTS (SELECT 1 FROM role_grants g WHERE g.org_id = p_org AND g.user_id <> p_user AND g.role = 'ORG_ADMIN') THEN
    RETURN QUERY SELECT 'LAST_ADMIN'::text, NULL::uuid[], NULL::uuid[], NULL::text[];
    RETURN;
  END IF;
  SELECT coalesce(array_agg(g.role || coalesce(':' || g.exam_code, '') ORDER BY g.role, g.exam_code), ARRAY[]::text[])
    INTO v_roles FROM role_grants g WHERE g.org_id = p_org AND g.user_id = p_user;
  WITH r AS (UPDATE seat_assignments s SET revoked_at = p_now
              WHERE s.org_id = p_org AND s.user_id = p_user AND s.revoked_at IS NULL RETURNING s.id)
    SELECT coalesce(array_agg(r.id ORDER BY r.id), ARRAY[]::uuid[]) INTO v_seats FROM r;
  WITH d AS (DELETE FROM class_members c WHERE c.org_id = p_org AND c.user_id = p_user RETURNING c.class_id)
    SELECT coalesce(array_agg(d.class_id ORDER BY d.class_id), ARRAY[]::uuid[]) INTO v_classes FROM d;
  DELETE FROM role_grants g WHERE g.org_id = p_org AND g.user_id = p_user;
  DELETE FROM org_memberships m WHERE m.org_id = p_org AND m.user_id = p_user;
  RETURN QUERY SELECT 'REMOVED'::text, v_seats, v_classes, v_roles;
END $$;
REVOKE ALL ON FUNCTION members_remove(uuid, uuid, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION members_remove(uuid, uuid, timestamptz) TO e9x_app;

-- ---- who may read another person's users row (ruled): only the ORG_ADMIN of the current organisation (and English9x staff) -------------
-- A plain member (LEARNER, teacher, ...) sees their own row only: other learners' e-mail addresses are not theirs to read.
DROP POLICY users_select ON users;
CREATE POLICY users_select ON users FOR SELECT USING (
  id = e9x_current_user()
  OR (e9x_current_org() IS NOT NULL
      AND e9x_is_current_org_admin()
      AND EXISTS (SELECT 1 FROM org_memberships m
                   WHERE m.user_id = users.id AND m.org_id = e9x_current_org()))
  OR e9x_caller_is_sys_admin());
