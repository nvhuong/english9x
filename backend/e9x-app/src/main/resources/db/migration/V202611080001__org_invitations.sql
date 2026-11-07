-- P1.5 organisations, invitations, join codes.
--
--  * organizations: INSERT / SELECT-all / UPDATE for a real SYS_ADMIN only (flag AND grant, e9x_is_sys_admin()). A staff
--    flag alone, as every AUTHOR / REVIEWER / TEACHER carries it, authorises nothing here. Changing the type to
--    ENTERPRISE is refused while AUTHOR / REVIEWER / TEACHER grants (or pending invitations for those roles) exist.
--  * org_memberships, role_grants, users: SELECT is the caller's own rows, the current organisation's rows, or everything for
--    a real SYS_ADMIN. The bare staff flag these policies used to end in is also carried by AUTHOR / REVIEWER / TEACHER.
--  * Every grant inside an organisation needs a membership row of the same user: an AFTER trigger on role_grants and a
--    BEFORE trigger on org_memberships (definer functions, so they see the rows whatever the caller's RLS view). Insert the
--    membership FIRST, delete the grants FIRST. An ORG_ADMIN without a membership is impossible by any path.
--  * invitations / join_codes: written by the request role through the real SYS_ADMIN / ORG_ADMIN policies, but the token
--    digests are not even selectable and the only column an update may touch is revoked_at. The person who ACCEPTS has
--    no flag and no role yet, so accepting is one narrow SECURITY DEFINER function per table: it finds the row by the digest
--    of the token, checks it (unused, unexpired, not revoked, the inviter still has the authority, the signed-in account
--    is the invited address and verified), and writes exactly the grants the invitation recorded. It has no argument that
--    names a role, an organisation or a user: the user is the session's app.user_id, the rest comes from the row.
--  * Like the identity functions, they narrow what SQL can touch; a compromised statement of e9x_app can still pass any
--    p_now and forge app.user_id (the trusted-setter limit of P1.3), but it cannot choose what is granted.

-- ---- who may read other people's memberships, grants and accounts ------------------------------------------------------
-- e9x_is_sys_admin() cannot be used in a policy of role_grants: it runs as the caller and reads role_grants, so the policy
-- would call itself. This helper is SECURITY DEFINER and owned by the table owner, which RLS exempts because none of these
-- tables use FORCE ROW LEVEL SECURITY (db-test 31 asserts that, and RlsIT runs it against a schema Flyway migrated as the
-- non-superuser e9x_owner). No argument: it can only answer about the person bound to the session, and the flag must be on too.
CREATE FUNCTION e9x_caller_is_sys_admin() RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT e9x_is_staff() AND EXISTS (SELECT 1 FROM role_grants g
                                     WHERE g.user_id = e9x_current_user() AND g.role = 'SYS_ADMIN' AND g.org_id IS NULL) $$;
REVOKE ALL ON FUNCTION e9x_caller_is_sys_admin() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION e9x_caller_is_sys_admin() TO e9x_app;  -- the only non-owner role that reads these three tables

DROP POLICY org_memberships_select ON org_memberships;
CREATE POLICY org_memberships_select ON org_memberships FOR SELECT USING (
  user_id = e9x_current_user() OR org_id = e9x_current_org() OR e9x_caller_is_sys_admin());
DROP POLICY role_grants_select ON role_grants;
CREATE POLICY role_grants_select ON role_grants FOR SELECT USING (
  user_id = e9x_current_user() OR org_id = e9x_current_org() OR e9x_caller_is_sys_admin());
DROP POLICY users_select ON users;
CREATE POLICY users_select ON users FOR SELECT USING (
  id = e9x_current_user()
  OR (e9x_current_org() IS NOT NULL
      AND EXISTS (SELECT 1 FROM org_memberships m
                   WHERE m.user_id = users.id AND m.org_id = e9x_current_org()))
  OR e9x_caller_is_sys_admin());

-- ---- organizations --------------------------------------------------------------------------------------------------
CREATE POLICY organizations_insert ON organizations FOR INSERT WITH CHECK (e9x_is_sys_admin());
CREATE POLICY organizations_select_sys_admin ON organizations FOR SELECT USING (e9x_is_sys_admin());
CREATE POLICY organizations_update ON organizations FOR UPDATE USING (e9x_is_sys_admin()) WITH CHECK (e9x_is_sys_admin());

-- ---- invitations ----------------------------------------------------------------------------------------------------
-- org_id NULL = an English9x (internal) invitation: AUTHOR / REVIEWER / TEACHER of an exam, or SYS_ADMIN.
-- grants = [{"role": "...", "exam": "IELTS" | null}, ...]; email is stored lower case.
CREATE TABLE invitations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid REFERENCES organizations(id),
  email text NOT NULL,
  grants jsonb NOT NULL,
  token_hash bytea NOT NULL UNIQUE,
  invited_by uuid NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  accepted_at timestamptz,
  accepted_by uuid REFERENCES users(id),
  revoked_at timestamptz,
  CONSTRAINT invitations_email_ck CHECK (email = lower(email) AND length(email) BETWEEN 3 AND 254 AND position('@' IN email) > 1),
  CONSTRAINT invitations_grants_ck CHECK (jsonb_typeof(grants) = 'array' AND jsonb_array_length(grants) BETWEEN 1 AND 20)
);
CREATE INDEX invitations_org_idx ON invitations (org_id);
CREATE INDEX invitations_email_idx ON invitations (email);
ALTER TABLE invitations ENABLE ROW LEVEL SECURITY;

CREATE FUNCTION invitations_validate() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  g jsonb;
  v_role text;
  v_exam text;
  v_type text;
BEGIN
  IF NEW.org_id IS NOT NULL THEN
    SELECT o.type INTO v_type FROM organizations o WHERE o.id = NEW.org_id;
  END IF;
  FOR g IN SELECT value FROM jsonb_array_elements(NEW.grants) LOOP
    IF jsonb_typeof(g) <> 'object' THEN
      RAISE EXCEPTION 'each invited grant must be an object' USING ERRCODE = '23514';
    END IF;
    v_role := g->>'role';
    v_exam := g->>'exam';
    IF v_role IS NULL OR v_role NOT IN ('LEARNER','AUTHOR','REVIEWER','TEACHER','ORG_ADMIN','SYS_ADMIN') THEN
      RAISE EXCEPTION 'unknown role in an invitation' USING ERRCODE = '23514';
    END IF;
    IF v_role IN ('AUTHOR','REVIEWER','TEACHER') AND (v_exam IS NULL OR v_exam = '') THEN
      RAISE EXCEPTION 'role % needs an exam', v_role USING ERRCODE = '23514';
    END IF;
    IF v_role IN ('ORG_ADMIN','SYS_ADMIN') AND v_exam IS NOT NULL THEN
      RAISE EXCEPTION 'role % applies to every exam', v_role USING ERRCODE = '23514';
    END IF;
    IF NEW.org_id IS NULL AND v_role IN ('ORG_ADMIN','LEARNER') THEN
      RAISE EXCEPTION 'role % needs an organisation', v_role USING ERRCODE = '23514';
    END IF;
    IF NEW.org_id IS NOT NULL AND v_role = 'SYS_ADMIN' THEN
      RAISE EXCEPTION 'SYS_ADMIN belongs to English9x only' USING ERRCODE = '23514';
    END IF;
    IF v_type = 'ENTERPRISE' AND v_role IN ('AUTHOR','REVIEWER','TEACHER') THEN
      RAISE EXCEPTION 'ENTERPRISE organizations cannot grant role %', v_role USING ERRCODE = '23514';
    END IF;
  END LOOP;
  RETURN NULL;
END $$;
-- AFTER, not BEFORE: the row-level security check of the INSERT runs first, so a caller who may not write the row learns
-- nothing about it (for instance that another organisation is an ENTERPRISE) from the error.
CREATE TRIGGER invitations_validate_trg AFTER INSERT ON invitations
  FOR EACH ROW EXECUTE FUNCTION invitations_validate();

CREATE POLICY invitations_select ON invitations FOR SELECT USING (
  e9x_is_sys_admin() OR (org_id IS NOT NULL AND org_id = e9x_current_org() AND e9x_is_current_org_admin()));
CREATE POLICY invitations_insert ON invitations FOR INSERT WITH CHECK (
  invited_by = e9x_current_user()
  AND (e9x_is_sys_admin() OR (org_id IS NOT NULL AND org_id = e9x_current_org() AND e9x_is_current_org_admin())));
CREATE POLICY invitations_update ON invitations FOR UPDATE USING (
  e9x_is_sys_admin() OR (org_id IS NOT NULL AND org_id = e9x_current_org() AND e9x_is_current_org_admin()))
  WITH CHECK (
  e9x_is_sys_admin() OR (org_id IS NOT NULL AND org_id = e9x_current_org() AND e9x_is_current_org_admin()));
-- No DELETE policy and no DELETE privilege.

GRANT SELECT (id, org_id, email, grants, invited_by, created_at, expires_at, accepted_at, accepted_by, revoked_at)
  ON invitations TO e9x_app;                                  -- never token_hash
GRANT INSERT (org_id, email, grants, token_hash, invited_by, expires_at) ON invitations TO e9x_app;
GRANT UPDATE (revoked_at) ON invitations TO e9x_app;           -- accepted_* only by org_invitation_accept

-- ---- join codes (the use-flow, endpoint and UI are P6.1 / F44) ----------------------------------------------------
CREATE TABLE join_codes (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organizations(id),
  class_id uuid REFERENCES classes(id),                      -- P6.1 adds the class membership; today only the organisation is joined
  code_hash bytea NOT NULL UNIQUE,
  created_by uuid NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  max_uses integer CHECK (max_uses IS NULL OR max_uses > 0),
  used_count integer NOT NULL DEFAULT 0 CHECK (used_count >= 0),
  revoked_at timestamptz
);
CREATE INDEX join_codes_org_idx ON join_codes (org_id);
ALTER TABLE join_codes ENABLE ROW LEVEL SECURITY;

CREATE POLICY join_codes_select ON join_codes FOR SELECT USING (
  e9x_is_sys_admin() OR (org_id = e9x_current_org() AND e9x_is_current_org_admin()));
CREATE POLICY join_codes_insert ON join_codes FOR INSERT WITH CHECK (
  created_by = e9x_current_user()
  AND (e9x_is_sys_admin() OR (org_id = e9x_current_org() AND e9x_is_current_org_admin()))
  AND (class_id IS NULL OR EXISTS (SELECT 1 FROM classes c WHERE c.id = class_id AND c.owner_org_id = org_id)));
CREATE POLICY join_codes_update ON join_codes FOR UPDATE USING (
  e9x_is_sys_admin() OR (org_id = e9x_current_org() AND e9x_is_current_org_admin()))
  WITH CHECK (
  e9x_is_sys_admin() OR (org_id = e9x_current_org() AND e9x_is_current_org_admin()));

GRANT SELECT (id, org_id, class_id, created_by, created_at, expires_at, max_uses, used_count, revoked_at) ON join_codes TO e9x_app;
GRANT INSERT (org_id, class_id, code_hash, created_by, expires_at, max_uses) ON join_codes TO e9x_app;
GRANT UPDATE (revoked_at) ON join_codes TO e9x_app;

-- ---- a grant needs a membership, a membership cannot go while a grant needs it -----------------------------------------
CREATE FUNCTION role_grants_require_membership() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  IF NEW.org_id IS NOT NULL AND NOT EXISTS (
       SELECT 1 FROM org_memberships m WHERE m.org_id = NEW.org_id AND m.user_id = NEW.user_id) THEN
    RAISE EXCEPTION 'a grant in an organisation needs a membership of that user in it' USING ERRCODE = '23514';
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER role_grants_membership_guard
  AFTER INSERT OR UPDATE OF user_id, org_id ON role_grants
  FOR EACH ROW EXECUTE FUNCTION role_grants_require_membership();

CREATE FUNCTION memberships_guard_grants() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM role_grants g WHERE g.org_id = OLD.org_id AND g.user_id = OLD.user_id) THEN
    RAISE EXCEPTION 'remove the grants of this user in the organisation before removing the membership' USING ERRCODE = '23514';
  END IF;
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER org_memberships_grants_guard
  BEFORE DELETE OR UPDATE OF user_id, org_id ON org_memberships
  FOR EACH ROW EXECUTE FUNCTION memberships_guard_grants();

-- ---- ENTERPRISE rule when the type changes (ledger: "ENTERPRISE role rule not rechecked on org type change") ---------------
CREATE FUNCTION organizations_guard_type_change() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  IF NEW.type = 'ENTERPRISE' AND OLD.type IS DISTINCT FROM 'ENTERPRISE' AND (
       EXISTS (SELECT 1 FROM role_grants g WHERE g.org_id = NEW.id AND g.role IN ('AUTHOR','REVIEWER','TEACHER'))
       OR EXISTS (SELECT 1 FROM invitations i, jsonb_array_elements(i.grants) e
                   WHERE i.org_id = NEW.id AND i.accepted_at IS NULL AND i.revoked_at IS NULL AND i.expires_at > now()
                     AND e->>'role' IN ('AUTHOR','REVIEWER','TEACHER'))) THEN
    RAISE EXCEPTION 'cannot become ENTERPRISE while AUTHOR / REVIEWER / TEACHER grants or invitations exist' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER organizations_type_guard
  BEFORE UPDATE OF type ON organizations
  FOR EACH ROW EXECUTE FUNCTION organizations_guard_type_change();

-- ---- reading what RLS must not widen ----------------------------------------------------------------------------------
-- The inviter's authority: SYS_ADMIN, or ORG_ADMIN of the invitation's organisation. One rule for accepting and for listing.
-- Internal: not executable by any request role, because it answers about any person. The definer functions below call it as owner.
CREATE FUNCTION e9x_inviter_holds_authority(p_inviter uuid, p_org uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT EXISTS (SELECT 1 FROM role_grants rg
                  WHERE rg.user_id = p_inviter
                    AND ((rg.role = 'SYS_ADMIN' AND rg.org_id IS NULL)
                         OR (p_org IS NOT NULL AND rg.role = 'ORG_ADMIN' AND rg.org_id = p_org))) $$;
REVOKE ALL ON FUNCTION e9x_inviter_holds_authority(uuid, uuid) FROM PUBLIC;

-- For the invitation lists: can this invitation still be accepted as far as its inviter goes? An ORG_ADMIN cannot see the
-- SYS_ADMIN grant of the person who invited, hence a definer. NULL unless the caller may see the invitation (the same test
-- as invitations_select), so it is no oracle about other organisations.
CREATE FUNCTION e9x_invitation_inviter_active(p_invitation uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT e9x_inviter_holds_authority(i.invited_by, i.org_id) FROM invitations i
   WHERE i.id = p_invitation
     AND (e9x_is_sys_admin() OR (i.org_id IS NOT NULL AND i.org_id = e9x_current_org() AND e9x_is_current_org_admin())) $$;

-- The hourly limit is per person across every organisation (spec section 15.1); RLS would show only the current one.
-- No user argument: the count is the session's own.
CREATE FUNCTION e9x_invitations_sent_since(p_since timestamptz) RETURNS bigint
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT count(*) FROM invitations i WHERE i.invited_by = e9x_current_user() AND i.created_at > p_since $$;

REVOKE ALL ON FUNCTION e9x_invitation_inviter_active(uuid), e9x_invitations_sent_since(timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION e9x_invitation_inviter_active(uuid), e9x_invitations_sent_since(timestamptz) TO e9x_app;

-- ---- accepting an invitation ------------------------------------------------------------------------------------------
-- Outcomes: ACCEPTED, INVALID (forged / unknown / used / expired / revoked / inviter without authority / no user),
-- EMAIL_MISMATCH (the signed-in account is not the invited address; nothing is consumed), EMAIL_NOT_VERIFIED
-- (the address matches but its mailbox is unconfirmed: it could be an account somebody registered with it; nothing is consumed).
CREATE FUNCTION org_invitation_accept(p_token_hash bytea, p_now timestamptz)
  RETURNS TABLE (r_outcome text, r_org_id uuid)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_user uuid := e9x_current_user();
  inv invitations%ROWTYPE;
  v_email text;
  v_verified timestamptz;
  g jsonb;
BEGIN
  IF v_user IS NULL OR p_token_hash IS NULL THEN
    RETURN QUERY SELECT 'INVALID'::text, NULL::uuid;
    RETURN;
  END IF;
  SELECT * INTO inv FROM invitations i WHERE i.token_hash = p_token_hash FOR UPDATE;
  IF NOT FOUND OR inv.accepted_at IS NOT NULL OR inv.revoked_at IS NOT NULL OR inv.expires_at <= p_now
     OR NOT e9x_inviter_holds_authority(inv.invited_by, inv.org_id) THEN
    RETURN QUERY SELECT 'INVALID'::text, NULL::uuid;
    RETURN;
  END IF;
  SELECT lower(u.email), u.email_verified_at INTO v_email, v_verified FROM users u WHERE u.id = v_user;
  IF NOT FOUND THEN
    RETURN QUERY SELECT 'INVALID'::text, NULL::uuid;
    RETURN;
  END IF;
  IF v_email <> inv.email THEN
    RETURN QUERY SELECT 'EMAIL_MISMATCH'::text, NULL::uuid;
    RETURN;
  END IF;
  IF v_verified IS NULL THEN
    RETURN QUERY SELECT 'EMAIL_NOT_VERIFIED'::text, NULL::uuid;
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
  UPDATE invitations SET accepted_at = p_now, accepted_by = v_user WHERE id = inv.id;
  RETURN QUERY SELECT 'ACCEPTED'::text, inv.org_id;
END $$;

-- ---- redeeming a join code (callable now; P6.1 builds the endpoint, rate limit and screens) ----------------------------
-- Outcomes: JOINED, ALREADY_MEMBER (nothing consumed), INVALID. Grants LEARNER in the code's organisation and nothing else.
CREATE FUNCTION org_join_code_redeem(p_code_hash bytea, p_now timestamptz)
  RETURNS TABLE (r_outcome text, r_org_id uuid)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_user uuid := e9x_current_user();
  jc join_codes%ROWTYPE;
BEGIN
  IF v_user IS NULL OR p_code_hash IS NULL OR NOT EXISTS (SELECT 1 FROM users u WHERE u.id = v_user) THEN
    RETURN QUERY SELECT 'INVALID'::text, NULL::uuid;
    RETURN;
  END IF;
  SELECT * INTO jc FROM join_codes c WHERE c.code_hash = p_code_hash FOR UPDATE;
  IF NOT FOUND OR jc.revoked_at IS NOT NULL OR jc.expires_at <= p_now
     OR (jc.max_uses IS NOT NULL AND jc.used_count >= jc.max_uses) THEN
    RETURN QUERY SELECT 'INVALID'::text, NULL::uuid;
    RETURN;
  END IF;
  IF EXISTS (SELECT 1 FROM org_memberships m WHERE m.org_id = jc.org_id AND m.user_id = v_user) THEN
    RETURN QUERY SELECT 'ALREADY_MEMBER'::text, jc.org_id;
    RETURN;
  END IF;
  INSERT INTO org_memberships(org_id, user_id) VALUES (jc.org_id, v_user);
  INSERT INTO role_grants(user_id, org_id, exam_code, role) VALUES (v_user, jc.org_id, NULL, 'LEARNER')
    ON CONFLICT ON CONSTRAINT role_grants_scope_uq DO NOTHING;
  UPDATE join_codes SET used_count = used_count + 1 WHERE id = jc.id;
  RETURN QUERY SELECT 'JOINED'::text, jc.org_id;
END $$;

REVOKE ALL ON FUNCTION org_invitation_accept(bytea, timestamptz), org_join_code_redeem(bytea, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION org_invitation_accept(bytea, timestamptz), org_join_code_redeem(bytea, timestamptz) TO e9x_app;
