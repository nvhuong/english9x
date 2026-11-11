-- P6.1: class_members (RLS), the redeem function recording the class, members_remove (who may, the last admin, what it cleans).
INSERT INTO users(id,email,email_verified_at) VALUES
 ('a0000000-0000-0000-0000-000000000002','admin-a@x.test', now()),
 ('a0000000-0000-0000-0000-000000000003','learner-a@x.test', now()),
 ('a0000000-0000-0000-0000-000000000004','newbie@x.test', now()),
 ('a0000000-0000-0000-0000-000000000005','newbie2@x.test', now()),
 ('a0000000-0000-0000-0000-000000000006','admin-b@x.test', now()),
 ('a0000000-0000-0000-0000-000000000007','peer-a@x.test', now());
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','Center B','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000002'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000003'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000007'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','a0000000-0000-0000-0000-000000000006');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('a0000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN'),
 ('a0000000-0000-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'LEARNER'),
 ('a0000000-0000-0000-0000-000000000007','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'LEARNER'),
 ('a0000000-0000-0000-0000-000000000006','bbbbbbbb-0000-0000-0000-00000000000b',NULL,'ORG_ADMIN');
INSERT INTO classes(id,owner_org_id,name) VALUES ('c1a55000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','Lop A');
INSERT INTO join_codes(org_id,class_id,code_hash,created_by,expires_at,max_uses) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','c1a55000-0000-0000-0000-00000000000a', sha256('CLASSCOD'::bytea), 'a0000000-0000-0000-0000-000000000002', now()+interval '7 days', 1);
INSERT INTO class_members(class_id,org_id,user_id) VALUES ('c1a55000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000003');

SET ROLE e9x_app;

-- redeeming the last use: the class is recorded, the code id comes back, the second person is told INVALID
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000004',false), set_config('app.org_id','',false), set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
DECLARE r record;
BEGIN
  SELECT * INTO r FROM org_join_code_redeem(sha256('CLASSCOD'::bytea), now());
  ASSERT r.r_outcome = 'JOINED' AND r.r_class_id = 'c1a55000-0000-0000-0000-00000000000a' AND r.r_code_id IS NOT NULL, 'joined with the class';
END $$;
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000005',false);
DO $$
DECLARE r record;
BEGIN
  SELECT * INTO r FROM org_join_code_redeem(sha256('CLASSCOD'::bytea), now());
  ASSERT r.r_outcome = 'INVALID', 'the last use is gone';
END $$;

-- class_members: the person sees their own rows only; no write privilege at all for the request role
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000004',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM class_members WHERE user_id <> 'a0000000-0000-0000-0000-000000000004') = 0, 'nobody else''s rows';
  BEGIN
    INSERT INTO class_members(class_id,org_id,user_id) VALUES ('c1a55000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000005');
    RAISE EXCEPTION 'e9x_app must not write class_members';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
-- A's admin sees the class members of A; B's admin none
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000002',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false), set_config('app.org_admin','on',false);
DO $$ BEGIN ASSERT (SELECT count(*) FROM class_members) = 2, 'A admin sees A''s two class members'; END $$;
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000006',false), set_config('app.org_id','bbbbbbbb-0000-0000-0000-00000000000b',false);
DO $$ BEGIN ASSERT (SELECT count(*) FROM class_members) = 0, 'B admin sees none'; END $$;

-- members_remove
DO $$
DECLARE r record;
BEGIN
  SELECT * INTO r FROM members_remove('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000003', now());
  ASSERT r.r_outcome = 'FORBIDDEN', 'another organisation''s admin';
END $$;
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000007',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false), set_config('app.org_admin','',false);
DO $$
DECLARE r record;
BEGIN
  SELECT * INTO r FROM members_remove('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000003', now());
  ASSERT r.r_outcome = 'FORBIDDEN', 'a plain member removing someone else';
END $$;
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000002',false), set_config('app.org_admin','on',false);
DO $$
DECLARE r record;
BEGIN
  SELECT * INTO r FROM members_remove('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000002', now());
  ASSERT r.r_outcome = 'LAST_ADMIN', 'the only admin stays';
  SELECT * INTO r FROM members_remove('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000003', now());
  ASSERT r.r_outcome = 'REMOVED' AND r.r_class_ids = ARRAY['c1a55000-0000-0000-0000-00000000000a']::uuid[] AND r.r_roles = ARRAY['LEARNER'], 'removed with class and roles reported';
  SELECT * INTO r FROM members_remove('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000003', now());
  ASSERT r.r_outcome = 'NOT_FOUND', 'twice';
  ASSERT NOT EXISTS (SELECT 1 FROM org_memberships WHERE user_id = 'a0000000-0000-0000-0000-000000000003'), 'membership gone';
  ASSERT NOT EXISTS (SELECT 1 FROM role_grants WHERE user_id = 'a0000000-0000-0000-0000-000000000003'), 'grants gone';
END $$;
-- a learner leaves by themselves, in the personal context
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000007',false), set_config('app.org_id','',false), set_config('app.org_admin','',false);
DO $$
DECLARE r record;
BEGIN
  SELECT * INTO r FROM members_remove('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000007', now());
  ASSERT r.r_outcome = 'REMOVED', 'leaving';
END $$;

-- e-mail visibility (ruled): an ORG_ADMIN sees the e-mail of members of their own organisation, a LEARNER does not see another member's
RESET ROLE;
INSERT INTO org_memberships(org_id,user_id) VALUES ('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000004') ON CONFLICT DO NOTHING;
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('a0000000-0000-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'LEARNER') ON CONFLICT DO NOTHING;
SET ROLE e9x_app;
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000004',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false), set_config('app.org_admin','',false), set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM users WHERE id <> 'a0000000-0000-0000-0000-000000000004') = 0, 'a LEARNER reads no other member''s e-mail';
  ASSERT (SELECT email FROM users WHERE id = 'a0000000-0000-0000-0000-000000000004') = 'newbie@x.test', 'but their own';
END $$;
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000002',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM users WHERE email = 'newbie@x.test') = 1, 'the ORG_ADMIN of the organisation reads its members'' e-mail';
  ASSERT (SELECT count(*) FROM users WHERE email = 'admin-b@x.test') = 0, 'but not another organisation''s';
END $$;
