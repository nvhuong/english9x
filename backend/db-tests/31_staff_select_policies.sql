-- P1.5 fix (review I1): the staff flag alone never reads other people's rows. AUTHOR, REVIEWER and TEACHER carry that flag
-- too (RlsSessionConfigurer), so org_memberships, role_grants and users answer: your own rows, the current organisation's
-- rows, or everything for a real SYS_ADMIN (flag AND grant). Run by RlsIT against a schema Flyway migrated as the
-- non-superuser e9x_owner, which is what proves the definer helper really skips the policy it is called from.
INSERT INTO users(id,email) VALUES
 ('a0000000-0000-0000-0000-000000000001','sys@x.test'),
 ('a0000000-0000-0000-0000-000000000002','author@x.test'),
 ('a0000000-0000-0000-0000-000000000003','reviewer@x.test'),
 ('a0000000-0000-0000-0000-000000000004','teacher@x.test'),
 ('a0000000-0000-0000-0000-000000000005','admin-a@x.test'),
 ('a0000000-0000-0000-0000-000000000006','member-b@x.test'),
 ('a0000000-0000-0000-0000-000000000007','nobody@x.test');
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','Center B','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000005'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','a0000000-0000-0000-0000-000000000006');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('a0000000-0000-0000-0000-000000000001',NULL,NULL,'SYS_ADMIN'),
 ('a0000000-0000-0000-0000-000000000002',NULL,'IELTS','AUTHOR'),
 ('a0000000-0000-0000-0000-000000000003',NULL,'IELTS','REVIEWER'),
 ('a0000000-0000-0000-0000-000000000004',NULL,'IELTS','TEACHER'),
 ('a0000000-0000-0000-0000-000000000005','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN'),
 ('a0000000-0000-0000-0000-000000000006','bbbbbbbb-0000-0000-0000-00000000000b',NULL,'LEARNER');

-- ---- the helper: narrow, definer, and the tables it reads do not force RLS on their owner ------------------------------
DO $$
DECLARE f record;
BEGIN
  SELECT p.oid, p.prosecdef, p.proconfig, p.pronargs, p.proowner INTO f FROM pg_proc p WHERE p.proname = 'e9x_caller_is_sys_admin';
  ASSERT f.oid IS NOT NULL, 'the helper exists';
  ASSERT f.prosecdef, 'SECURITY DEFINER';
  ASSERT f.pronargs = 0, 'no argument: the caller comes from the session';
  ASSERT 'search_path=pg_catalog, public, pg_temp' = ANY (f.proconfig), 'pinned search path, pg_temp last';
  ASSERT has_function_privilege('e9x_app', f.oid, 'EXECUTE'), 'e9x_app evaluates the policies';
  ASSERT NOT EXISTS (SELECT 1 FROM aclexplode((SELECT proacl FROM pg_proc WHERE oid = f.oid)) a WHERE a.grantee = 0), 'not PUBLIC';
  ASSERT NOT has_function_privilege('e9x_grader', f.oid, 'EXECUTE'), 'the grader reads none of these tables';
  ASSERT NOT has_function_privilege('e9x_worker', f.oid, 'EXECUTE'), 'the worker reads none of these tables';
  ASSERT NOT has_table_privilege('e9x_grader', 'role_grants', 'SELECT') AND NOT has_table_privilege('e9x_worker', 'role_grants', 'SELECT');
  ASSERT NOT has_table_privilege('e9x_grader', 'org_memberships', 'SELECT') AND NOT has_table_privilege('e9x_worker', 'org_memberships', 'SELECT');
  ASSERT NOT has_table_privilege('e9x_grader', 'users', 'SELECT') AND NOT has_table_privilege('e9x_worker', 'users', 'SELECT');
  -- the definer skips RLS on role_grants only because it owns the table and the table does not FORCE RLS
  ASSERT (SELECT relowner FROM pg_class WHERE oid = 'role_grants'::regclass) = f.proowner, 'the helper is owned by the owner of role_grants';
  ASSERT NOT (SELECT relforcerowsecurity FROM pg_class WHERE oid = 'role_grants'::regclass),
         'FORCE ROW LEVEL SECURITY would send the helper back through the policy that calls it';
  -- no SELECT policy of the three tables still ends in the bare flag
  ASSERT NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename IN ('org_memberships','role_grants','users') AND cmd = 'SELECT'
                      AND qual LIKE '%e9x_is_staff%'), 'the bare staff flag authorises no read here';
END $$;

SET ROLE e9x_app;

-- ---- AUTHOR / REVIEWER / TEACHER with the flag on, and a flag with no grant at all: own rows only -----------------------
CREATE FUNCTION pg_temp.assert_own_rows_only(p_who uuid, p_label text, p_memberships int, p_grants int) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
  PERFORM set_config('app.user_id', p_who::text, false), set_config('app.org_id', '', false),
          set_config('app.e9x_staff', 'on', false), set_config('app.org_admin', '', false);
  ASSERT (SELECT array_agg(id) FROM users) = ARRAY[p_who], p_label || ' sees only their own user row';
  ASSERT (SELECT count(*) FROM org_memberships) = p_memberships AND NOT EXISTS (SELECT 1 FROM org_memberships WHERE user_id <> p_who),
         p_label || ' sees only their own memberships';
  ASSERT (SELECT count(*) FROM role_grants) = p_grants AND NOT EXISTS (SELECT 1 FROM role_grants WHERE user_id <> p_who),
         p_label || ' sees only their own grants';
  ASSERT NOT e9x_caller_is_sys_admin(), p_label || ' is not a SYS_ADMIN';
END $$;
SELECT pg_temp.assert_own_rows_only('a0000000-0000-0000-0000-000000000002', 'an AUTHOR', 0, 1);
SELECT pg_temp.assert_own_rows_only('a0000000-0000-0000-0000-000000000003', 'a REVIEWER', 0, 1);
SELECT pg_temp.assert_own_rows_only('a0000000-0000-0000-0000-000000000004', 'a TEACHER', 0, 1);
SELECT pg_temp.assert_own_rows_only('a0000000-0000-0000-0000-000000000007', 'the flag with no grant at all', 0, 0);
SELECT pg_temp.assert_own_rows_only('a0000000-0000-0000-0000-000000000006', 'a forged flag on an organisation member', 1, 1);

-- ---- a real SYS_ADMIN sees everything; the grant without the flag does not ---------------------------------------------
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000001',false), set_config('app.org_id','',false),
       set_config('app.e9x_staff','on',false), set_config('app.org_admin','',false);
DO $$ BEGIN
  ASSERT e9x_caller_is_sys_admin(), 'flag AND grant';
  ASSERT e9x_is_sys_admin(), 'the older helper still agrees';
  ASSERT (SELECT count(*) FROM users) = 7, 'a SYS_ADMIN sees every user';
  ASSERT (SELECT count(*) FROM org_memberships) = 2, 'a SYS_ADMIN sees every membership';
  ASSERT (SELECT count(*) FROM role_grants) = 6, 'a SYS_ADMIN sees every grant';
END $$;
SELECT set_config('app.e9x_staff','',false);
DO $$ BEGIN
  ASSERT NOT e9x_caller_is_sys_admin(), 'the grant alone is not enough: two independent facts';
  ASSERT (SELECT count(*) FROM users) = 1 AND (SELECT count(*) FROM org_memberships) = 0 AND (SELECT count(*) FROM role_grants) = 1,
         'without the flag a SYS_ADMIN grant reads its own rows only';
END $$;
-- nobody bound at all: nothing, and no error
SELECT set_config('app.user_id','',false), set_config('app.e9x_staff','on',false);
DO $$ BEGIN
  ASSERT NOT e9x_caller_is_sys_admin(), 'no user, no SYS_ADMIN';
  ASSERT (SELECT count(*) FROM users) = 0 AND (SELECT count(*) FROM role_grants) = 0;
END $$;

-- ---- own-row and current-org clauses are unchanged ------------------------------------------------------------------------
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000005',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','on',false);
DO $$ BEGIN
  ASSERT (SELECT array_agg(email) FROM users) = ARRAY['admin-a@x.test'], 'the current organisation, nobody else';
  ASSERT (SELECT count(*) FROM org_memberships) = 1 AND (SELECT count(*) FROM role_grants) = 1;
END $$;
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000006',false), set_config('app.org_id','bbbbbbbb-0000-0000-0000-00000000000b',false),
       set_config('app.org_admin','',false);
DO $$ BEGIN
  ASSERT (SELECT array_agg(email) FROM users) = ARRAY['member-b@x.test'];
  ASSERT (SELECT count(*) FROM org_memberships) = 1 AND (SELECT count(*) FROM role_grants) = 1;
END $$;
-- staff with an organisation context: that organisation and their own rows, still not everybody
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000002',false), set_config('app.org_id','bbbbbbbb-0000-0000-0000-00000000000b',false),
       set_config('app.e9x_staff','on',false);
DO $$ BEGIN
  ASSERT (SELECT count(*) FROM org_memberships) = 1 AND (SELECT count(*) FROM role_grants) = 2, 'own grant plus the current org, nothing of org A';
  ASSERT NOT EXISTS (SELECT 1 FROM role_grants WHERE org_id = 'aaaaaaaa-0000-0000-0000-00000000000a');
END $$;
