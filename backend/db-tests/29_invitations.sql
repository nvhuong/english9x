-- P1.5: invitations. Writes go through the real SYS_ADMIN / ORG_ADMIN policies; the accept path is one narrow
-- SECURITY DEFINER function that validates the token first and grants only what the invitation recorded.
DROP ROLE IF EXISTS inv_outsider;
CREATE ROLE inv_outsider NOLOGIN;
INSERT INTO users(id,email,email_verified_at) VALUES
 ('a0000000-0000-0000-0000-000000000001','sys@x.test', now()),
 ('a0000000-0000-0000-0000-000000000002','admin-a@x.test', now()),
 ('a0000000-0000-0000-0000-000000000003','admin-b@x.test', now()),
 ('a0000000-0000-0000-0000-000000000004','learner-a@x.test', now()),
 ('a0000000-0000-0000-0000-000000000005','invitee@x.test', now()),
 ('a0000000-0000-0000-0000-000000000006','stranger@x.test', now()),
 ('a0000000-0000-0000-0000-000000000007','unverified@x.test', NULL),
 ('a0000000-0000-0000-0000-000000000008','author@x.test', now()),
 ('a0000000-0000-0000-0000-000000000009','admin-e@x.test', now());
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','Center B','CENTER'),
 ('eeeeeeee-0000-0000-0000-00000000000e','Enterprise E','ENTERPRISE');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000002'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000004'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','a0000000-0000-0000-0000-000000000003'),
 ('eeeeeeee-0000-0000-0000-00000000000e','a0000000-0000-0000-0000-000000000009');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('a0000000-0000-0000-0000-000000000001',NULL,NULL,'SYS_ADMIN'),
 ('a0000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN'),
 ('a0000000-0000-0000-0000-000000000003','bbbbbbbb-0000-0000-0000-00000000000b',NULL,'ORG_ADMIN'),
 ('a0000000-0000-0000-0000-000000000008',NULL,'IELTS','AUTHOR'),
 ('a0000000-0000-0000-0000-000000000009','eeeeeeee-0000-0000-0000-00000000000e',NULL,'ORG_ADMIN');

SET ROLE e9x_app;

-- ---- who may write invitations -------------------------------------------------------------------------------
-- the real SYS_ADMIN invites an internal author (org NULL) and an org admin
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000001',false), set_config('app.org_id','',false),
       set_config('app.e9x_staff','on',false), set_config('app.org_admin','',false);
INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at) VALUES
 (NULL,'unverified@x.test','[{"role":"REVIEWER","exam":"IELTS"}]', sha256('tok-unverified'::bytea),'a0000000-0000-0000-0000-000000000001', now()+interval '7 days'),
 (NULL,'invitee@x.test','[{"role":"AUTHOR","exam":"IELTS"}]', sha256('tok-author'::bytea),'a0000000-0000-0000-0000-000000000001', now()+interval '7 days'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','invitee@x.test','[{"role":"ORG_ADMIN","exam":null}]', sha256('tok-orgadmin'::bytea),'a0000000-0000-0000-0000-000000000001', now()+interval '7 days');
DO $$
BEGIN
  -- recorded as somebody else: refused
  BEGIN
    INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at)
     VALUES (NULL,'x@x.test','[{"role":"AUTHOR","exam":"IELTS"}]', sha256('t1'::bytea),'a0000000-0000-0000-0000-000000000002', now()+interval '7 days');
    RAISE EXCEPTION 'invited_by must be the caller';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  -- shape rules
  BEGIN
    INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at)
     VALUES (NULL,'x@x.test','[]', sha256('t2'::bytea),'a0000000-0000-0000-0000-000000000001', now()+interval '7 days');
    RAISE EXCEPTION 'empty grants must fail';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at)
     VALUES (NULL,'x@x.test','[{"role":"AUTHOR","exam":null}]', sha256('t3'::bytea),'a0000000-0000-0000-0000-000000000001', now()+interval '7 days');
    RAISE EXCEPTION 'an AUTHOR without an exam must fail';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at)
     VALUES (NULL,'x@x.test','[{"role":"GOD","exam":null}]', sha256('t4'::bytea),'a0000000-0000-0000-0000-000000000001', now()+interval '7 days');
    RAISE EXCEPTION 'an unknown role must fail';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at)
     VALUES (NULL,'X@X.test','[{"role":"AUTHOR","exam":"IELTS"}]', sha256('t5'::bytea),'a0000000-0000-0000-0000-000000000001', now()+interval '7 days');
    RAISE EXCEPTION 'the email must be stored lower case';
  EXCEPTION WHEN check_violation THEN NULL; END;
  -- org-scoped combinations that cannot exist as grants cannot be invited either
  BEGIN
    INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at)
     VALUES ('aaaaaaaa-0000-0000-0000-00000000000a','x@x.test','[{"role":"SYS_ADMIN","exam":null}]', sha256('t6'::bytea),'a0000000-0000-0000-0000-000000000001', now()+interval '7 days');
    RAISE EXCEPTION 'SYS_ADMIN is English9x only';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at)
     VALUES (NULL,'x@x.test','[{"role":"ORG_ADMIN","exam":null}]', sha256('t7'::bytea),'a0000000-0000-0000-0000-000000000001', now()+interval '7 days');
    RAISE EXCEPTION 'ORG_ADMIN needs an organisation';
  EXCEPTION WHEN check_violation THEN NULL; END;
  -- ENTERPRISE has no AUTHOR / REVIEWER / TEACHER, not even as an invitation
  BEGIN
    INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at)
     VALUES ('eeeeeeee-0000-0000-0000-00000000000e','x@x.test','[{"role":"TEACHER","exam":"IELTS"}]', sha256('t8'::bytea),'a0000000-0000-0000-0000-000000000001', now()+interval '7 days');
    RAISE EXCEPTION 'ENTERPRISE cannot invite a TEACHER';
  EXCEPTION WHEN check_violation THEN NULL; END;
  INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at)
   VALUES ('eeeeeeee-0000-0000-0000-00000000000e','x@x.test','[{"role":"LEARNER","exam":null}]', sha256('t9'::bytea),'a0000000-0000-0000-0000-000000000001', now()+interval '7 days');
  -- the digest is unique
  BEGIN
    INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at)
     VALUES (NULL,'x@x.test','[{"role":"AUTHOR","exam":"IELTS"}]', sha256('tok-author'::bytea),'a0000000-0000-0000-0000-000000000001', now()+interval '7 days');
    RAISE EXCEPTION 'duplicate token digest';
  EXCEPTION WHEN unique_violation THEN NULL; END;
END $$;

-- an AUTHOR with a legitimate staff flag cannot invite anybody
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000008',false);
DO $$
BEGIN
  BEGIN
    INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at)
     VALUES (NULL,'y@x.test','[{"role":"AUTHOR","exam":"IELTS"}]', sha256('t10'::bytea),'a0000000-0000-0000-0000-000000000008', now()+interval '7 days');
    RAISE EXCEPTION 'an AUTHOR must not invite';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  ASSERT (SELECT count(*) FROM invitations) = 0, 'an AUTHOR sees no invitations';
END $$;

-- the ORG_ADMIN of A: invites into A only; sees A's invitations only; no token digest column
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000002',false), set_config('app.e9x_staff','',false),
       set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false), set_config('app.org_admin','on',false);
INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at)
 VALUES ('aaaaaaaa-0000-0000-0000-00000000000a','learner2@x.test','[{"role":"LEARNER","exam":null}]', sha256('tok-learner'::bytea),'a0000000-0000-0000-0000-000000000002', now()+interval '7 days');
DO $$
DECLARE n int;
BEGIN
  BEGIN
    INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at)
     VALUES ('bbbbbbbb-0000-0000-0000-00000000000b','z@x.test','[{"role":"LEARNER","exam":null}]', sha256('t11'::bytea),'a0000000-0000-0000-0000-000000000002', now()+interval '7 days');
    RAISE EXCEPTION 'an org admin must not invite into another organisation';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at)
     VALUES (NULL,'z@x.test','[{"role":"AUTHOR","exam":"IELTS"}]', sha256('t12'::bytea),'a0000000-0000-0000-0000-000000000002', now()+interval '7 days');
    RAISE EXCEPTION 'an org admin must not invite English9x staff';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  ASSERT (SELECT count(*) FROM invitations) = 2, 'only the invitations of A (the sys-admin one for A and their own)';
  ASSERT (SELECT count(*) FROM invitations WHERE org_id IS NULL) = 0, 'the English9x invitations are invisible';
  BEGIN
    PERFORM token_hash FROM invitations;
    RAISE EXCEPTION 'the token digest column must not be readable';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
DO $$
DECLARE n int;
BEGIN
  UPDATE invitations SET revoked_at = now() WHERE email = 'learner2@x.test';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 1, 'an org admin revokes an invitation of their organisation';
  UPDATE invitations SET revoked_at = NULL WHERE email = 'learner2@x.test';
  BEGIN
    UPDATE invitations SET grants = '[{"role":"ORG_ADMIN","exam":null}]' WHERE email = 'learner2@x.test';
    RAISE EXCEPTION 'the recorded grants must not be editable';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    UPDATE invitations SET email = 'me@x.test' WHERE email = 'learner2@x.test';
    RAISE EXCEPTION 'the recorded email must not be editable';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    UPDATE invitations SET accepted_at = now() WHERE email = 'learner2@x.test';
    RAISE EXCEPTION 'accepting is the function''s job only';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    DELETE FROM invitations WHERE email = 'learner2@x.test';
    RAISE EXCEPTION 'invitations are never deleted by the request role';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
-- the ORG_ADMIN of B cannot revoke A's
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000003',false),
       set_config('app.org_id','bbbbbbbb-0000-0000-0000-00000000000b',false);
DO $$
DECLARE n int;
BEGIN
  UPDATE invitations SET revoked_at = now() WHERE email = 'learner2@x.test';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'another organisation cannot touch the invitation';
  ASSERT (SELECT count(*) FROM invitations) = 0;
END $$;

-- ---- hardening of the definer function ---------------------------------------------------------------------------
DO $$
DECLARE f record; n int := 0;
BEGIN
  FOR f IN SELECT p.oid, p.proname, p.prosecdef, p.proconfig, p.pronargs FROM pg_proc p JOIN pg_namespace s ON s.oid = p.pronamespace
            WHERE s.nspname = 'public' AND p.proname LIKE 'org\_%' LOOP
    n := n + 1;
    ASSERT f.prosecdef, f.proname || ' must be SECURITY DEFINER';
    ASSERT f.proconfig IS NOT NULL AND 'search_path=pg_catalog, public, pg_temp' = ANY (f.proconfig),
           f.proname || ' must pin search_path with pg_temp last';
    ASSERT has_function_privilege('e9x_app', f.oid, 'EXECUTE'), f.proname || ' executable by e9x_app';
    ASSERT NOT has_function_privilege('inv_outsider', f.oid, 'EXECUTE'), f.proname || ' must not be executable by others';
    ASSERT f.pronargs = 2, f.proname || ' takes the token digest and the time only: no role, org or user argument';
  END LOOP;
  ASSERT n = 2, 'expected org_invitation_accept and org_join_code_redeem, found ' || n;
END $$;

-- ---- accepting ---------------------------------------------------------------------------------------------------
DO $$ BEGIN PERFORM set_config('app.user_id','a0000000-0000-0000-0000-000000000005',false);
              PERFORM set_config('app.org_id','',false); PERFORM set_config('app.e9x_staff','',false); PERFORM set_config('app.org_admin','',false); END $$;
DO $$
DECLARE r record;
BEGIN
  -- forged token
  SELECT * INTO r FROM org_invitation_accept(sha256('forged'::bytea), now());
  ASSERT r.r_outcome = 'INVALID', 'a forged token is invalid, got ' || r.r_outcome;
  ASSERT NOT EXISTS (SELECT 1 FROM org_memberships WHERE user_id = 'a0000000-0000-0000-0000-000000000005');
  -- somebody else's email: not consumed, nothing granted
  PERFORM set_config('app.user_id','a0000000-0000-0000-0000-000000000006',false);
  SELECT * INTO r FROM org_invitation_accept(sha256('tok-author'::bytea), now());
  ASSERT r.r_outcome = 'EMAIL_MISMATCH', 'a different signed-in email is refused, got ' || r.r_outcome;
  ASSERT NOT EXISTS (SELECT 1 FROM role_grants WHERE user_id = 'a0000000-0000-0000-0000-000000000006');
  -- an unverified mailbox: refused even with the matching address
  PERFORM set_config('app.user_id','a0000000-0000-0000-0000-000000000007',false);
  SELECT * INTO r FROM org_invitation_accept(sha256('tok-unverified'::bytea), now());
  ASSERT r.r_outcome = 'EMAIL_NOT_VERIFIED', 'an unverified mailbox must verify first, got ' || r.r_outcome;
  ASSERT NOT EXISTS (SELECT 1 FROM role_grants WHERE user_id = 'a0000000-0000-0000-0000-000000000007');
  SELECT * INTO r FROM org_invitation_accept(sha256('tok-author'::bytea), now());
  ASSERT r.r_outcome = 'EMAIL_MISMATCH', 'unverified@ is not the address of tok-author';
  -- no user context at all
  PERFORM set_config('app.user_id','',false);
  SELECT * INTO r FROM org_invitation_accept(sha256('tok-author'::bytea), now());
  ASSERT r.r_outcome = 'INVALID', 'no signed-in user: invalid, got ' || r.r_outcome;
  -- expired (the time is the caller's argument but the row's expiry decides)
  PERFORM set_config('app.user_id','a0000000-0000-0000-0000-000000000005',false);
  SELECT * INTO r FROM org_invitation_accept(sha256('tok-author'::bytea), now() + interval '8 days');
  ASSERT r.r_outcome = 'INVALID', 'expired, got ' || r.r_outcome;
  -- the real thing: exactly what was recorded
  SELECT * INTO r FROM org_invitation_accept(sha256('tok-author'::bytea), now());
  ASSERT r.r_outcome = 'ACCEPTED', 'accepted, got ' || r.r_outcome;
  ASSERT r.r_org_id IS NULL, 'an English9x invitation has no organisation';
  ASSERT (SELECT array_agg(role || ':' || coalesce(exam_code,'-') || ':' || coalesce(org_id::text,'-')) FROM role_grants
           WHERE user_id = 'a0000000-0000-0000-0000-000000000005') = ARRAY['AUTHOR:IELTS:-'], 'exactly the recorded grant';
  ASSERT NOT EXISTS (SELECT 1 FROM org_memberships WHERE user_id = 'a0000000-0000-0000-0000-000000000005'), 'no membership for an English9x grant';
  -- reused
  SELECT * INTO r FROM org_invitation_accept(sha256('tok-author'::bytea), now());
  ASSERT r.r_outcome = 'INVALID', 'a used token is invalid, got ' || r.r_outcome;
  ASSERT (SELECT count(*) FROM role_grants WHERE user_id = 'a0000000-0000-0000-0000-000000000005') = 1;
END $$;

-- the org invitation: membership and the recorded role come together
DO $$
DECLARE r record;
BEGIN
  SELECT * INTO r FROM org_invitation_accept(sha256('tok-orgadmin'::bytea), now());
  ASSERT r.r_outcome = 'ACCEPTED' AND r.r_org_id = 'aaaaaaaa-0000-0000-0000-00000000000a', 'org invitation accepted';
  ASSERT EXISTS (SELECT 1 FROM org_memberships WHERE user_id = 'a0000000-0000-0000-0000-000000000005' AND org_id = 'aaaaaaaa-0000-0000-0000-00000000000a'), 'membership row written with the grant';
  ASSERT EXISTS (SELECT 1 FROM role_grants WHERE user_id = 'a0000000-0000-0000-0000-000000000005' AND org_id = 'aaaaaaaa-0000-0000-0000-00000000000a' AND role = 'ORG_ADMIN');
  ASSERT (SELECT count(*) FROM role_grants WHERE user_id = 'a0000000-0000-0000-0000-000000000005') = 2, 'nothing else was granted';
END $$;

-- the function does not become a back door to other people's data
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM invitations) = 0, 'the invitee still sees no invitation rows';
END $$;

-- revoked, inviter demoted, enterprise defence in depth (crafted as the owner, past the insert-time rules)
RESET ROLE;
INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at) VALUES
 (NULL,'stranger@x.test','[{"role":"REVIEWER","exam":"TOEIC"}]', sha256('tok-revoked'::bytea),'a0000000-0000-0000-0000-000000000001', now()+interval '7 days'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','stranger@x.test','[{"role":"LEARNER","exam":null}]', sha256('tok-demoted'::bytea),'a0000000-0000-0000-0000-000000000008', now()+interval '7 days'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','stranger@x.test','[{"role":"LEARNER","exam":null}]', sha256('tok-b'::bytea),'a0000000-0000-0000-0000-000000000003', now()+interval '7 days');
UPDATE invitations SET revoked_at = now() WHERE token_hash = sha256('tok-revoked'::bytea);
ALTER TABLE invitations DISABLE TRIGGER USER;
INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at)
 VALUES ('eeeeeeee-0000-0000-0000-00000000000e','stranger@x.test','[{"role":"TEACHER","exam":"IELTS"}]', sha256('tok-enterprise'::bytea),'a0000000-0000-0000-0000-000000000001', now()+interval '7 days');
ALTER TABLE invitations ENABLE TRIGGER USER;
SET ROLE e9x_app;
DO $$ BEGIN PERFORM set_config('app.user_id','a0000000-0000-0000-0000-000000000006',false); END $$;
DO $$
DECLARE r record;
BEGIN
  SELECT * INTO r FROM org_invitation_accept(sha256('tok-revoked'::bytea), now());
  ASSERT r.r_outcome = 'INVALID', 'revoked, got ' || r.r_outcome;
  -- the inviter (an AUTHOR) never had the authority to invite into A: the invitation is void
  SELECT * INTO r FROM org_invitation_accept(sha256('tok-demoted'::bytea), now());
  ASSERT r.r_outcome = 'INVALID', 'an inviter without authority, got ' || r.r_outcome;
  -- ORG_ADMIN of B invited into B: fine
  SELECT * INTO r FROM org_invitation_accept(sha256('tok-b'::bytea), now());
  ASSERT r.r_outcome = 'ACCEPTED', 'accepted, got ' || r.r_outcome;
  -- ENTERPRISE: even if such an invitation existed, granting is refused by the grant guard and nothing is written
  BEGIN
    SELECT * INTO r FROM org_invitation_accept(sha256('tok-enterprise'::bytea), now());
    RAISE EXCEPTION 'an ENTERPRISE teacher grant must fail, got %', r.r_outcome;
  EXCEPTION WHEN check_violation THEN NULL; END;
  ASSERT NOT EXISTS (SELECT 1 FROM org_memberships WHERE user_id = 'a0000000-0000-0000-0000-000000000006' AND org_id = 'eeeeeeee-0000-0000-0000-00000000000e'),
         'the failed accept rolled back its membership';
END $$;

-- a demoted inviter: the admin of A loses ORG_ADMIN after inviting
RESET ROLE;
INSERT INTO invitations(org_id,email,grants,token_hash,invited_by,expires_at)
 VALUES ('aaaaaaaa-0000-0000-0000-00000000000a','stranger@x.test','[{"role":"LEARNER","exam":null}]', sha256('tok-later'::bytea),'a0000000-0000-0000-0000-000000000002', now()+interval '7 days');
DELETE FROM role_grants WHERE user_id = 'a0000000-0000-0000-0000-000000000002';
SET ROLE e9x_app;
DO $$
DECLARE r record;
BEGIN
  PERFORM set_config('app.user_id','a0000000-0000-0000-0000-000000000006',false);
  SELECT * INTO r FROM org_invitation_accept(sha256('tok-later'::bytea), now());
  ASSERT r.r_outcome = 'INVALID', 'an invitation from somebody who is no longer admin is void, got ' || r.r_outcome;
END $$;
RESET ROLE;
DROP ROLE inv_outsider;
