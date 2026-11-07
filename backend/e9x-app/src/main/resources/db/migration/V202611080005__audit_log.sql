-- P1.9 audit log (spec section 6 "Hệ thống", F57): who did what, when, in which context. Append-only, written in the SAME transaction
-- as the action it records (a rolled-back action leaves no record; a record that cannot be written fails the action).
--
-- Append-only, for everybody: a trigger refuses UPDATE and DELETE per row and TRUNCATE per statement. It fires for the table owner
-- (e9x_owner) and for a superuser too, and is ENABLE ALWAYS so even `session_replication_role = replica` does not skip it. The request
-- role has no UPDATE, DELETE or TRUNCATE privilege on top. What nobody can promise at SQL level: the table owner (e9x_owner) can still
-- run DDL, for instance ALTER TABLE ... DISABLE TRIGGER and then DELETE, or DROP the table. Owner DDL is NOT blocked and NOT logged by default:
-- a statement run by hand leaves no row in flyway_schema_history, and the server logs statements only if log_statement is set (it is not
-- set here). In v1 the application process also holds the Flyway (owner) credentials (spring.flyway.user), so a compromised application is
-- not shielded from this either. Separating the Flyway credentials from the app process in production is on the hardening list under P6.5.
--
-- Who and where are SERVER-DERIVED, never arguments:
--   * actor_id DEFAULTs to e9x_current_user() and org_id to e9x_current_org() (the context the transaction was bound to); the request role has
--     no INSERT privilege on those columns, and the INSERT policy checks them against the session as well, so an INSERT cannot NAME another
--     actor or organisation. That holds only as far as the session-binding model does (the P1.3 trusted-setter limit): a statement run as the
--     request role can itself set app.user_id (and app.e9x_staff) with set_config() and then insert, or read, as that person. The database
--     ties a row to the session's binding, not to proof of identity; closing that is the binding model's job, not this table's.
--   * No user bound (a background job or listener without a person): no record can be written. The application refuses first
--     (AuditLog#record throws), the database refuses second (actor_kind USER needs an actor). A background step records what a PERSON did
--     only by running bound to that person, as the notification listeners already must. The system itself has no actor in v1.
--   * actor_kind OPERATOR (no actor_id) is for the one thing done outside the application: scripts/grant-sys-admin.sql, run as the owner. The
--     request role cannot write it (the policy demands USER).
--
-- Read: a real SYS_ADMIN only (e9x_is_sys_admin(): the English9x flag AND a SYS_ADMIN grant). No staff-wide clause, no ORG_ADMIN clause.
-- actor_id and org_id carry NO foreign key on purpose: a log must outlive the person and the organisation, and a cascading
-- or SET NULL action would be an UPDATE or DELETE of an audit row.
--
-- Personal data: detail holds none, by design. The invited address of invitation.send / invitation.revoke is NOT stored: the audit screen
-- reads it at read time through target_id from invitations (as the actor's name is read from users), so erasing the invitation erases
-- the address everywhere and the row then shows a neutral placeholder. An append-only table cannot erase rows (spec section 15 asks that personal
-- data be deletable on request), so the log keeps ONLY pseudonymous ids (actor_id, target_id, org_id). No plan task owned erasure until
-- P6.5: see its dated note ("xoá dữ liệu cá nhân"), which builds that path. detail never holds tokens, password material, comment text
-- or Telegram secrets (AuditLog refuses such keys).
CREATE TABLE audit_log (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  seq bigint GENERATED ALWAYS AS IDENTITY,                -- the stable order of the log (newest first = seq DESC)
  created_at timestamptz NOT NULL DEFAULT now(),
  actor_kind text NOT NULL DEFAULT 'USER',
  actor_id uuid DEFAULT e9x_current_user(),
  org_id uuid DEFAULT e9x_current_org(),
  action text NOT NULL,
  target_type text NOT NULL,
  target_id text NOT NULL,
  detail jsonb NOT NULL DEFAULT '{}',
  CONSTRAINT audit_log_actor_ck CHECK ((actor_kind = 'USER' AND actor_id IS NOT NULL) OR (actor_kind = 'OPERATOR' AND actor_id IS NULL)),
  CONSTRAINT audit_log_action_ck CHECK (action ~ '^[a-z][a-z0-9]*([.-][a-z0-9]+)*$' AND length(action) <= 80),
  CONSTRAINT audit_log_target_type_ck CHECK (target_type ~ '^[a-z][a-z0-9]*([.-][a-z0-9]+)*$' AND length(target_type) <= 60),
  CONSTRAINT audit_log_target_id_ck CHECK (length(target_id) BETWEEN 1 AND 200),
  CONSTRAINT audit_log_detail_ck CHECK (jsonb_typeof(detail) = 'object' AND octet_length(detail::text) <= 16384)
);
CREATE UNIQUE INDEX audit_log_seq_idx ON audit_log (seq);
CREATE INDEX audit_log_created_idx ON audit_log (created_at);
CREATE INDEX audit_log_action_idx ON audit_log (action, seq DESC);
CREATE INDEX audit_log_target_idx ON audit_log (target_type, target_id, seq DESC);
CREATE INDEX audit_log_actor_idx ON audit_log (actor_id, seq DESC);

CREATE FUNCTION audit_log_refuse() RETURNS trigger
  LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'audit_log is append-only: % is refused', TG_OP USING ERRCODE = '23001';
END $$;
CREATE TRIGGER audit_log_no_change BEFORE UPDATE OR DELETE ON audit_log
  FOR EACH ROW EXECUTE FUNCTION audit_log_refuse();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON audit_log
  FOR EACH STATEMENT EXECUTE FUNCTION audit_log_refuse();
ALTER TABLE audit_log ENABLE ALWAYS TRIGGER audit_log_no_change;
ALTER TABLE audit_log ENABLE ALWAYS TRIGGER audit_log_no_truncate;

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY audit_log_select ON audit_log FOR SELECT USING (e9x_is_sys_admin());
CREATE POLICY audit_log_insert ON audit_log FOR INSERT WITH CHECK (
  actor_kind = 'USER' AND actor_id = e9x_current_user() AND org_id IS NOT DISTINCT FROM e9x_current_org());
-- no UPDATE / DELETE policy, and no such privilege

REVOKE ALL ON audit_log FROM PUBLIC;
GRANT SELECT ON audit_log TO e9x_app;
GRANT INSERT (action, target_type, target_id, detail) ON audit_log TO e9x_app;   -- actor, context, time and order are filled by the database

-- ---- accepting an invitation: also say which invitation and which grants -------------------------------------------------
-- The audit record of an acceptance is written by Java in the same transaction (the accepting person is the actor). It needs the
-- invitation id and the grants this function applied; the token digest is never returned. Same body as V202611080001 otherwise.
DROP FUNCTION org_invitation_accept(bytea, timestamptz);
CREATE FUNCTION org_invitation_accept(p_token_hash bytea, p_now timestamptz)
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
  UPDATE invitations SET accepted_at = p_now, accepted_by = v_user WHERE id = inv.id;
  RETURN QUERY SELECT 'ACCEPTED'::text, inv.org_id, inv.id, inv.grants;
END $$;
REVOKE ALL ON FUNCTION org_invitation_accept(bytea, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION org_invitation_accept(bytea, timestamptz) TO e9x_app;
