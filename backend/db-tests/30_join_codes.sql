-- P1.5 creates the join_codes table and the narrow redeem function; the use-flow (endpoint, UI, rate limits) is P6.1 (F44).
INSERT INTO users(id,email,email_verified_at) VALUES
 ('a0000000-0000-0000-0000-000000000001','sys@x.test', now()),
 ('a0000000-0000-0000-0000-000000000002','admin-a@x.test', now()),
 ('a0000000-0000-0000-0000-000000000003','learner-a@x.test', now()),
 ('a0000000-0000-0000-0000-000000000004','newbie@x.test', now()),
 ('a0000000-0000-0000-0000-000000000005','newbie2@x.test', now()),
 ('a0000000-0000-0000-0000-000000000006','admin-b@x.test', now());
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','Center B','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000002'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000003'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','a0000000-0000-0000-0000-000000000006');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('a0000000-0000-0000-0000-000000000001',NULL,NULL,'SYS_ADMIN'),
 ('a0000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN'),
 ('a0000000-0000-0000-0000-000000000006','bbbbbbbb-0000-0000-0000-00000000000b',NULL,'ORG_ADMIN');

SET ROLE e9x_app;

-- creating: the ORG_ADMIN of A, for A only; a learner never
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000002',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','on',false);
INSERT INTO join_codes(org_id,code_hash,created_by,expires_at,max_uses) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a', sha256('code-ok'::bytea), 'a0000000-0000-0000-0000-000000000002', now()+interval '7 days', 1),
 ('aaaaaaaa-0000-0000-0000-00000000000a', sha256('code-old'::bytea), 'a0000000-0000-0000-0000-000000000002', now()-interval '1 day', NULL);
DO $$
DECLARE n int;
BEGIN
  BEGIN
    INSERT INTO join_codes(org_id,code_hash,created_by,expires_at) VALUES
     ('bbbbbbbb-0000-0000-0000-00000000000b', sha256('code-b'::bytea), 'a0000000-0000-0000-0000-000000000002', now()+interval '7 days');
    RAISE EXCEPTION 'an org admin must not create codes for another organisation';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  ASSERT (SELECT count(*) FROM join_codes) = 2;
  BEGIN
    PERFORM code_hash FROM join_codes;
    RAISE EXCEPTION 'the code digest must not be readable';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    UPDATE join_codes SET max_uses = 1000000;
    RAISE EXCEPTION 'limits are not editable';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  UPDATE join_codes SET revoked_at = now() WHERE expires_at < now();
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 1, 'revoking is allowed';
  BEGIN
    UPDATE join_codes SET used_count = 0;
    RAISE EXCEPTION 'the counter is the function''s';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000003',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  BEGIN
    INSERT INTO join_codes(org_id,code_hash,created_by,expires_at) VALUES
     ('aaaaaaaa-0000-0000-0000-00000000000a', sha256('code-x'::bytea), 'a0000000-0000-0000-0000-000000000003', now()+interval '7 days');
    RAISE EXCEPTION 'a learner must not create codes';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  ASSERT (SELECT count(*) FROM join_codes) = 0, 'a plain member sees no codes';
END $$;
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000006',false), set_config('app.org_id','bbbbbbbb-0000-0000-0000-00000000000b',false),
       set_config('app.org_admin','on',false);
DO $$ BEGIN ASSERT (SELECT count(*) FROM join_codes) = 0, 'the admin of B sees none of A''s'; END $$;

-- redeeming
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000004',false), set_config('app.org_id','',false), set_config('app.org_admin','',false);
DO $$
DECLARE r record;
BEGIN
  SELECT * INTO r FROM org_join_code_redeem(sha256('nope'::bytea), now());
  ASSERT r.r_outcome = 'INVALID', 'forged code, got ' || r.r_outcome;
  SELECT * INTO r FROM org_join_code_redeem(sha256('code-old'::bytea), now());
  ASSERT r.r_outcome = 'INVALID', 'expired / revoked code, got ' || r.r_outcome;
  SELECT * INTO r FROM org_join_code_redeem(sha256('code-ok'::bytea), now());
  ASSERT r.r_outcome = 'JOINED' AND r.r_org_id = 'aaaaaaaa-0000-0000-0000-00000000000a', 'joined, got ' || r.r_outcome;
  ASSERT (SELECT array_agg(role) FROM role_grants WHERE user_id = 'a0000000-0000-0000-0000-000000000004') = ARRAY['LEARNER'], 'a join code grants LEARNER and nothing else';
  ASSERT EXISTS (SELECT 1 FROM org_memberships WHERE user_id = 'a0000000-0000-0000-0000-000000000004' AND org_id = 'aaaaaaaa-0000-0000-0000-00000000000a');
  -- max_uses = 1 is used up
  PERFORM set_config('app.user_id','a0000000-0000-0000-0000-000000000005',false);
  SELECT * INTO r FROM org_join_code_redeem(sha256('code-ok'::bytea), now());
  ASSERT r.r_outcome = 'INVALID', 'a used-up code, got ' || r.r_outcome;
  ASSERT NOT EXISTS (SELECT 1 FROM org_memberships WHERE user_id = 'a0000000-0000-0000-0000-000000000005');
  -- no user
  PERFORM set_config('app.user_id','',false);
  SELECT * INTO r FROM org_join_code_redeem(sha256('code-ok'::bytea), now());
  ASSERT r.r_outcome = 'INVALID';
END $$;
