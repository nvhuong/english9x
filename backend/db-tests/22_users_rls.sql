-- P1.4: Row-Level Security on users. SELECT: yourself, a member of the current organisation, or a real SYS_ADMIN (P1.5).
-- UPDATE: yourself, display_name / locale only. INSERT / DELETE: never by the request role.
INSERT INTO organizations(id,name,type) VALUES
 ('a1a1a1a1-0000-0000-0000-00000000000a','A','CENTER'),
 ('b1b1b1b1-0000-0000-0000-00000000000b','B','CENTER');
INSERT INTO users(id,email,display_name,password_hash) VALUES
 ('00000001-0000-0000-0000-000000000001','u1@x.test','one','$2a$10$secrethashsecrethashsecrethashsecrethashsecrethashsecret'),
 ('00000002-0000-0000-0000-000000000002','u2@x.test','two',NULL),
 ('00000003-0000-0000-0000-000000000003','u3@x.test','three',NULL),
 ('00000004-0000-0000-0000-000000000004','u4@x.test','four',NULL),
 ('00000005-0000-0000-0000-000000000005','staff@x.test','staff',NULL);
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('a1a1a1a1-0000-0000-0000-00000000000a','00000001-0000-0000-0000-000000000001'),
 ('a1a1a1a1-0000-0000-0000-00000000000a','00000002-0000-0000-0000-000000000002'),
 ('b1b1b1b1-0000-0000-0000-00000000000b','00000003-0000-0000-0000-000000000003');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS') ON CONFLICT DO NOTHING;
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('00000005-0000-0000-0000-000000000005',NULL,'IELTS','AUTHOR');

SET ROLE e9x_app;

-- no context: nobody
DO $$ BEGIN ASSERT (SELECT count(*) FROM users) = 0, 'no context -> no users'; END $$;

-- a user with no organisation context sees only themself
SELECT set_config('app.user_id','00000004-0000-0000-0000-000000000004',false);
DO $$ BEGIN
  ASSERT (SELECT count(*) FROM users) = 1 AND (SELECT email FROM users) = 'u4@x.test', 'self only';
END $$;

-- u1 without an org context still sees only themself (org mates are visible only inside the org context)
SELECT set_config('app.user_id','00000001-0000-0000-0000-000000000001',false);
DO $$ BEGIN ASSERT (SELECT count(*) FROM users) = 1, 'u1 personal context sees only self'; END $$;

-- u1 in org A: self and the other member of A, never B's member or a stranger
SELECT set_config('app.org_id','a1a1a1a1-0000-0000-0000-00000000000a',false);
DO $$ BEGIN
  ASSERT (SELECT count(*) FROM users) = 2, 'org A context sees the 2 members';
  ASSERT EXISTS (SELECT 1 FROM users WHERE email = 'u2@x.test'), 'sees the org mate';
  ASSERT NOT EXISTS (SELECT 1 FROM users WHERE email IN ('u3@x.test','u4@x.test','staff@x.test')), 'must not see org B member / strangers';
END $$;

-- UPDATE: self yes; another user (even a visible org mate) no; protected columns never
DO $$
DECLARE n int;
BEGIN
  UPDATE users SET display_name = 'uno' WHERE id = '00000001-0000-0000-0000-000000000001';
  GET DIAGNOSTICS n = ROW_COUNT; ASSERT n = 1, 'update self';
  UPDATE users SET display_name = 'hacked' WHERE id = '00000002-0000-0000-0000-000000000002';
  GET DIAGNOSTICS n = ROW_COUNT; ASSERT n = 0, 'must not update an org mate';
  UPDATE users SET locale = 'en' WHERE id = '00000001-0000-0000-0000-000000000001';
  GET DIAGNOSTICS n = ROW_COUNT; ASSERT n = 1, 'locale is self-editable';
  BEGIN
    UPDATE users SET email = 'new@x.test' WHERE id = '00000001-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'email must not be updatable by the request role';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    UPDATE users SET password_hash = 'x' WHERE id = '00000001-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'password_hash must not be updatable by the request role';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    UPDATE users SET email_verified_at = now() WHERE id = '00000001-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'email_verified_at must not be updatable by the request role';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- the hash is unreadable even for your own row
DO $$ BEGIN
  BEGIN
    PERFORM password_hash FROM users WHERE id = '00000001-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'password_hash must not be readable';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    PERFORM consent_at FROM users;
    RAISE EXCEPTION 'consent_at is not granted';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- INSERT only through auth_create_user: not directly, not with the old "registration flag" either
SELECT set_config('app.registration','on',false);
DO $$ BEGIN
  BEGIN
    INSERT INTO users(email) VALUES ('sneaky@x.test');
    RAISE EXCEPTION 'direct insert must fail';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    DELETE FROM users WHERE id = '00000001-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'delete must fail';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
SELECT set_config('app.registration','',false);

-- a visible-but-foreign context: a user acting as org B sees B's members only, not A's
SELECT set_config('app.user_id','00000003-0000-0000-0000-000000000003',false);
SELECT set_config('app.org_id','b1b1b1b1-0000-0000-0000-00000000000b',false);
DO $$ BEGIN
  ASSERT (SELECT count(*) FROM users) = 1 AND (SELECT email FROM users) = 'u3@x.test', 'org B sees only its own member';
END $$;

-- the staff flag alone (P1.5, review I1: every AUTHOR / REVIEWER / TEACHER carries it) reads only the person's own row;
-- a real SYS_ADMIN sees every user: 31_staff_select_policies.sql
SELECT set_config('app.user_id','00000005-0000-0000-0000-000000000005',false);
SELECT set_config('app.org_id','',false);
SELECT set_config('app.e9x_staff','on',false);
DO $$ BEGIN ASSERT (SELECT array_agg(email) FROM users) = ARRAY['staff@x.test'], 'the flag alone reads the own row only'; END $$;
-- ...but staff still cannot update someone else
DO $$
DECLARE n int;
BEGIN
  UPDATE users SET display_name = 'x' WHERE id = '00000004-0000-0000-0000-000000000004';
  GET DIAGNOSTICS n = ROW_COUNT; ASSERT n = 0, 'staff cannot update other users';
END $$;
