-- P6.5 branch C — the shared catalogue (V202611170003, F21, spec §14). The owner's request path, the English9x REVIEWER's decision, the REJECTED status and
-- who sees an APPROVED course. Rulings: (i) a SYS_ADMIN does not approve by being a SYS_ADMIN (needs the REVIEWER role); (ii) a decline is REJECTED, a real status.
INSERT INTO exams(code,name) VALUES ('TOEIC','TOEIC') ON CONFLICT DO NOTHING;
INSERT INTO organizations(id,name,type) VALUES
 ('59aaaaaa-0000-0000-0000-00000000000a','SC Center A','CENTER'),
 ('59bbbbbb-0000-0000-0000-00000000000b','SC Center B','CENTER');
INSERT INTO users(id,email) VALUES
 ('59000000-0000-0000-0000-000000000001','sc-author-a@x.test'),   -- AUTHOR of TOEIC at centre A (the owner)
 ('59000000-0000-0000-0000-000000000002','sc-reviewer-e@x.test'), -- English9x REVIEWER of TOEIC
 ('59000000-0000-0000-0000-000000000003','sc-sysadmin@x.test'),   -- English9x SYS_ADMIN (no REVIEWER)
 ('59000000-0000-0000-0000-000000000004','sc-b2c@x.test'),        -- a B2C learner
 ('59000000-0000-0000-0000-000000000005','sc-author-b@x.test');   -- AUTHOR of TOEIC at centre B (another centre)
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('59aaaaaa-0000-0000-0000-00000000000a','59000000-0000-0000-0000-000000000001'),
 ('59bbbbbb-0000-0000-0000-00000000000b','59000000-0000-0000-0000-000000000005');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('59000000-0000-0000-0000-000000000001','59aaaaaa-0000-0000-0000-00000000000a','TOEIC','AUTHOR'),
 ('59000000-0000-0000-0000-000000000002',NULL,'TOEIC','REVIEWER'),
 ('59000000-0000-0000-0000-000000000003',NULL,NULL,'SYS_ADMIN'),
 ('59000000-0000-0000-0000-000000000005','59bbbbbb-0000-0000-0000-00000000000b','TOEIC','AUTHOR');
-- a PUBLISHED course of centre A, not yet shared
INSERT INTO courses(id,owner_org_id,exam_code,code,title,status,shared_status) VALUES
 ('59cccccc-0000-0000-0000-00000000000c','59aaaaaa-0000-0000-0000-00000000000a','TOEIC','SCA001','A shared course','PUBLISHED','NONE');

CREATE FUNCTION public.t_sc(stmt text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  EXECUTE stmt; GET DIAGNOSTICS n = ROW_COUNT;
  RETURN 'rows:' || n;
EXCEPTION WHEN OTHERS THEN RETURN 'err:' || SQLSTATE;
END $$;
GRANT EXECUTE ON FUNCTION public.t_sc(text) TO e9x_app;

DO $$
BEGIN
  ASSERT has_function_privilege('e9x_app','shared_catalog_owner_name(uuid)','EXECUTE'), 'the owner-name definer is the request role''s';
  ASSERT (SELECT prosecdef AND proconfig::text LIKE '%search_path=pg_catalog, public, pg_temp%' FROM pg_proc WHERE proname = 'shared_catalog_owner_name'),
     'the definer pins its search_path';
  ASSERT NOT has_function_privilege('public','shared_catalog_owner_name(uuid)','EXECUTE'), 'PUBLIC was revoked';
END $$;

-- ---- the owner AUTHOR of centre A, in A's context --------------------------------------------------------------------------------------
SET ROLE e9x_app;
SELECT set_config('app.user_id','59000000-0000-0000-0000-000000000001',false), set_config('app.org_id','59aaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT public.t_sc($q$UPDATE courses SET shared_status = 'PENDING' WHERE id = '59cccccc-0000-0000-0000-00000000000c' AND shared_status = 'NONE'$q$) = 'rows:1', 'the owner offers it (NONE -> PENDING)';
  ASSERT public.t_sc($q$UPDATE courses SET shared_status = 'APPROVED' WHERE id = '59cccccc-0000-0000-0000-00000000000c'$q$) = 'err:42501', 'the owner cannot approve its own course';
  ASSERT public.t_sc($q$UPDATE courses SET shared_status = 'REJECTED' WHERE id = '59cccccc-0000-0000-0000-00000000000c'$q$) = 'err:42501', 'nor reject it';
  ASSERT public.t_sc($q$UPDATE courses SET shared_status = 'NONE' WHERE id = '59cccccc-0000-0000-0000-00000000000c' AND shared_status = 'PENDING'$q$) = 'rows:1', 'but may withdraw the request (PENDING -> NONE)';
END $$;

-- ---- the English9x REVIEWER of TOEIC, in the English9x context -----------------------------------------------------------------------
SELECT set_config('app.user_id','59000000-0000-0000-0000-000000000002',false), set_config('app.org_id','',false), set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  -- the reviewer cannot put a NONE course into PENDING (that is the owner's): the decide policy only sees PENDING/APPROVED rows, so nothing is updated
  ASSERT public.t_sc($q$UPDATE courses SET shared_status = 'PENDING' WHERE id = '59cccccc-0000-0000-0000-00000000000c'$q$) = 'rows:0', 'the reviewer does not request sharing';
END $$;
RESET ROLE;
UPDATE courses SET shared_status = 'PENDING' WHERE id = '59cccccc-0000-0000-0000-00000000000c';
SET ROLE e9x_app;
SELECT set_config('app.user_id','59000000-0000-0000-0000-000000000002',false), set_config('app.org_id','',false), set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE id = '59cccccc-0000-0000-0000-00000000000c') = 1, 'the reviewer sees the pending course (courses_select_shared_review)';
  ASSERT shared_catalog_owner_name('59aaaaaa-0000-0000-0000-00000000000a') = 'SC Center A', 'and the owning centre''s name';
  ASSERT shared_catalog_owner_name('59bbbbbb-0000-0000-0000-00000000000b') IS NULL, 'but not of a centre with nothing in the pool';
  ASSERT public.t_sc($q$UPDATE courses SET shared_status = 'APPROVED' WHERE id = '59cccccc-0000-0000-0000-00000000000c' AND shared_status = 'PENDING'$q$) = 'rows:1', 'the reviewer approves (PENDING -> APPROVED)';
  ASSERT public.t_sc($q$UPDATE courses SET shared_status = 'REJECTED' WHERE id = '59cccccc-0000-0000-0000-00000000000c'$q$) = 'rows:1', 'and may later remove it from the pool (APPROVED -> REJECTED)';
END $$;

-- ---- a SYS_ADMIN (no REVIEWER): ruling (i) ---------------------------------------------------------------------------------------------
RESET ROLE;
UPDATE courses SET shared_status = 'PENDING' WHERE id = '59cccccc-0000-0000-0000-00000000000c';
SET ROLE e9x_app;
SELECT set_config('app.user_id','59000000-0000-0000-0000-000000000003',false), set_config('app.org_id','',false), set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE id = '59cccccc-0000-0000-0000-00000000000c') = 0, 'a SYS_ADMIN does not even see the pending course (not a reviewer)';
  ASSERT public.t_sc($q$UPDATE courses SET shared_status = 'APPROVED' WHERE id = '59cccccc-0000-0000-0000-00000000000c'$q$) = 'rows:0', 'and cannot approve it';
END $$;

-- ---- who sees an APPROVED course: everyone; a REJECTED or PENDING one: nobody but the reviewer / owner --------------------------------
RESET ROLE;
UPDATE courses SET shared_status = 'APPROVED' WHERE id = '59cccccc-0000-0000-0000-00000000000c';
SET ROLE e9x_app;
-- a B2C learner (no org, no staff)
SELECT set_config('app.user_id','59000000-0000-0000-0000-000000000004',false), set_config('app.org_id','',false), set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE id = '59cccccc-0000-0000-0000-00000000000c') = 1, 'a B2C learner sees the APPROVED shared course';
END $$;
-- another centre's author
SELECT set_config('app.user_id','59000000-0000-0000-0000-000000000005',false), set_config('app.org_id','59bbbbbb-0000-0000-0000-00000000000b',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE id = '59cccccc-0000-0000-0000-00000000000c') = 1, 'another centre sees it too';
END $$;
RESET ROLE;
UPDATE courses SET shared_status = 'REJECTED' WHERE id = '59cccccc-0000-0000-0000-00000000000c';
SET ROLE e9x_app;
SELECT set_config('app.user_id','59000000-0000-0000-0000-000000000004',false), set_config('app.org_id','',false), set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE id = '59cccccc-0000-0000-0000-00000000000c') = 0, 'a REJECTED course is not public';
END $$;
RESET ROLE;
