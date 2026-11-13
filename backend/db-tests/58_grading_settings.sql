-- P6.5 branch A — centre grading configuration write path (V202611170002): org_grading_sides is now writable, NARROWLY.
--   * Only the ORG_ADMIN of a CENTER (app.org_admin = 'on' AND an ORG_ADMIN grant AND app.org_id = that org) may set or change the side, and only for a CENTER
--     (WITH CHECK type = 'CENTER'); an ENTERPRISE never gets a row, so its learners are always English9x's (the service also answers 400 before this, IT).
--   * The grant is column-scoped (org_id, side / side) and never DELETE.
INSERT INTO organizations(id,name,type) VALUES
 ('58aaaaaa-0000-0000-0000-00000000000a','GS Center A','CENTER'),
 ('58bbbbbb-0000-0000-0000-00000000000b','GS Center B','CENTER'),
 ('58eeeeee-0000-0000-0000-00000000000e','GS Enterprise','ENTERPRISE');
INSERT INTO users(id,email) VALUES
 ('58000000-0000-0000-0000-000000000001','gs-admin-a@x.test'),
 ('58000000-0000-0000-0000-000000000002','gs-member-a@x.test'),
 ('58000000-0000-0000-0000-000000000003','gs-admin-e@x.test');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('58aaaaaa-0000-0000-0000-00000000000a','58000000-0000-0000-0000-000000000001'),
 ('58aaaaaa-0000-0000-0000-00000000000a','58000000-0000-0000-0000-000000000002'),
 ('58eeeeee-0000-0000-0000-00000000000e','58000000-0000-0000-0000-000000000003');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('58000000-0000-0000-0000-000000000001','58aaaaaa-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN'),
 ('58000000-0000-0000-0000-000000000003','58eeeeee-0000-0000-0000-00000000000e',NULL,'ORG_ADMIN');

CREATE FUNCTION public.t_gs(stmt text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  EXECUTE stmt; GET DIAGNOSTICS n = ROW_COUNT;
  RETURN 'rows:' || n;
EXCEPTION WHEN OTHERS THEN RETURN 'err:' || SQLSTATE;
END $$;
GRANT EXECUTE ON FUNCTION public.t_gs(text) TO e9x_app;

DO $$
BEGIN
  ASSERT has_column_privilege('e9x_app','org_grading_sides','side','INSERT')
     AND has_column_privilege('e9x_app','org_grading_sides','org_id','INSERT')
     AND has_column_privilege('e9x_app','org_grading_sides','side','UPDATE'), 'P6.5 opened the narrow column-scoped write door';
  ASSERT NOT has_table_privilege('e9x_app','org_grading_sides','DELETE'), 'the side is never deleted';
  ASSERT NOT has_column_privilege('e9x_app','org_grading_sides','org_id','UPDATE'), 'the organisation of a side row never changes';
END $$;

-- ---- the ORG_ADMIN of CENTER A, in A's context -------------------------------------------------------------------------------------
SET ROLE e9x_app;
SELECT set_config('app.user_id','58000000-0000-0000-0000-000000000001',false), set_config('app.org_id','58aaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','on',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  ASSERT public.t_gs($q$INSERT INTO org_grading_sides(org_id,side) VALUES ('58aaaaaa-0000-0000-0000-00000000000a','CENTER')$q$) = 'rows:1', 'the admin sets its own centre''s side';
  ASSERT public.t_gs($q$UPDATE org_grading_sides SET side = 'ENGLISH9X', updated_at = now() WHERE org_id = '58aaaaaa-0000-0000-0000-00000000000a'$q$) = 'rows:1', 'and changes it';
  ASSERT public.t_gs($q$INSERT INTO org_grading_sides(org_id,side) VALUES ('58bbbbbb-0000-0000-0000-00000000000b','CENTER')$q$) = 'err:42501', 'never another centre''s';
  ASSERT public.t_gs($q$INSERT INTO org_grading_sides(org_id,side) VALUES ('58eeeeee-0000-0000-0000-00000000000e','CENTER')$q$) = 'err:42501', 'and not out of context';
  ASSERT public.t_gs($q$DELETE FROM org_grading_sides$q$) = 'err:42501', 'never deleted';
  ASSERT (SELECT side FROM org_grading_sides WHERE org_id = '58aaaaaa-0000-0000-0000-00000000000a') = 'ENGLISH9X', 'it reads back its own row';
END $$;

-- ---- a plain member of A (no org_admin flag) ---------------------------------------------------------------------------------------
SELECT set_config('app.user_id','58000000-0000-0000-0000-000000000002',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT public.t_gs($q$UPDATE org_grading_sides SET side = 'CENTER' WHERE org_id = '58aaaaaa-0000-0000-0000-00000000000a'$q$) = 'rows:0', 'a member does not see the write policy (no rows)';
  ASSERT public.t_gs($q$INSERT INTO org_grading_sides(org_id,side) VALUES ('58bbbbbb-0000-0000-0000-00000000000b','CENTER')$q$) = 'err:42501', 'and a member is not the admin, so a fresh centre is refused too';
END $$;

-- ---- the ORG_ADMIN of the ENTERPRISE, in its context: the database refuses a side row (type <> CENTER) ------------------------------
SELECT set_config('app.user_id','58000000-0000-0000-0000-000000000003',false), set_config('app.org_id','58eeeeee-0000-0000-0000-00000000000e',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  ASSERT public.t_gs($q$INSERT INTO org_grading_sides(org_id,side) VALUES ('58eeeeee-0000-0000-0000-00000000000e','ENGLISH9X')$q$) = 'err:42501', 'an enterprise never gets a side row';
  ASSERT public.t_gs($q$INSERT INTO org_grading_sides(org_id,side) VALUES ('58eeeeee-0000-0000-0000-00000000000e','CENTER')$q$) = 'err:42501', 'centre or not';
  ASSERT (SELECT count(*) FROM org_grading_sides WHERE org_id = '58eeeeee-0000-0000-0000-00000000000e') = 0, 'nothing was written';
END $$;
RESET ROLE;
