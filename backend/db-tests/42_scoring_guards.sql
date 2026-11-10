-- P2.5 score tables and grading skills: the guards of V202611110005 (policies and the lifecycle trigger are P2.1's, tested in 37_catalog.sql).
--  * a version leaves IN_REVIEW for PUBLISHED / CHANGES_REQUESTED only while its open review exists (e9x_guard_version_decision);
--  * one open version per table and per skill;
--  * an AUTHOR edits or deletes only a version they created.
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS') ON CONFLICT DO NOTHING;
INSERT INTO users(id,email) VALUES
 ('d6000000-0000-0000-0000-000000000001','author-e9@x.test'),
 ('d6000000-0000-0000-0000-000000000002','reviewer-e9@x.test'),
 ('d6000000-0000-0000-0000-000000000003','coauthor-e9@x.test');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('d6000000-0000-0000-0000-000000000001',NULL,'IELTS','AUTHOR'),
 ('d6000000-0000-0000-0000-000000000002',NULL,'IELTS','REVIEWER'),
 ('d6000000-0000-0000-0000-000000000003',NULL,'IELTS','AUTHOR');
INSERT INTO score_tables(id,owner_org_id,exam_code,skill,scope) VALUES
 ('56000000-0000-0000-0000-000000000001',NULL,'IELTS','LISTENING','EXAM');
INSERT INTO score_table_versions(id,score_table_id,owner_org_id,exam_code,version_no,status,entries,created_by) VALUES
 ('57000000-0000-0000-0000-000000000001','56000000-0000-0000-0000-000000000001',NULL,'IELTS',1,'IN_REVIEW','[{"raw":0,"scaled":0}]','d6000000-0000-0000-0000-000000000001');
INSERT INTO grading_skills(id,owner_org_id,exam_code,question_type_code) VALUES
 ('66000000-0000-0000-0000-000000000001',NULL,'IELTS','IELTS_W_TASK2');
INSERT INTO grading_skill_versions(id,grading_skill_id,owner_org_id,exam_code,version_no,status,rubric,created_by) VALUES
 ('67000000-0000-0000-0000-000000000001','66000000-0000-0000-0000-000000000001',NULL,'IELTS',1,'IN_REVIEW','r','d6000000-0000-0000-0000-000000000001');

CREATE FUNCTION public.t_attempt(stmt text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  EXECUTE stmt; GET DIAGNOSTICS n = ROW_COUNT;
  RETURN 'rows:' || n;
EXCEPTION WHEN OTHERS THEN RETURN 'err:' || SQLSTATE;
END $$;
GRANT EXECUTE ON FUNCTION public.t_attempt(text) TO e9x_app;

SET ROLE e9x_app;
-- ---- the reviewer: no decision without the open review
SELECT set_config('app.user_id','d6000000-0000-0000-0000-000000000002',false), set_config('app.org_id','',false), set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  ASSERT public.t_attempt('UPDATE score_table_versions SET status = ''PUBLISHED'' WHERE id = ''57000000-0000-0000-0000-000000000001''') = 'err:23000',
         'a score table version cannot be published with no review behind it';
  ASSERT public.t_attempt('UPDATE score_table_versions SET status = ''CHANGES_REQUESTED'' WHERE id = ''57000000-0000-0000-0000-000000000001''') = 'err:23000',
         'nor sent back';
  ASSERT public.t_attempt('UPDATE grading_skill_versions SET status = ''PUBLISHED'' WHERE id = ''67000000-0000-0000-0000-000000000001''') = 'err:23000',
         'a grading skill version neither';
END $$;
RESET ROLE;
INSERT INTO review_requests(content_type,version_id,owner_org_id,exam_code,author_id,submitted_by) VALUES
 ('score_table','57000000-0000-0000-0000-000000000001',NULL,'IELTS','d6000000-0000-0000-0000-000000000001','d6000000-0000-0000-0000-000000000001'),
 ('grading_skill','67000000-0000-0000-0000-000000000001',NULL,'IELTS','d6000000-0000-0000-0000-000000000001','d6000000-0000-0000-0000-000000000001');
SET ROLE e9x_app;
SELECT set_config('app.user_id','d6000000-0000-0000-0000-000000000002',false), set_config('app.org_id','',false), set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  ASSERT public.t_attempt('UPDATE score_table_versions SET status = ''PUBLISHED'' WHERE id = ''57000000-0000-0000-0000-000000000001''') = 'rows:1', 'with its open review the reviewer publishes the table';
  ASSERT public.t_attempt('UPDATE grading_skill_versions SET status = ''PUBLISHED'' WHERE id = ''67000000-0000-0000-0000-000000000001''') = 'rows:1', 'and the skill';
  ASSERT public.t_attempt('UPDATE score_table_versions SET status = ''ARCHIVED'' WHERE id = ''57000000-0000-0000-0000-000000000001''') = 'rows:1', 'a published version is archived without a review';
END $$;

-- ---- the authors: one open version, and only your own
SELECT set_config('app.user_id','d6000000-0000-0000-0000-000000000001',false);
DO $$
BEGIN
  ASSERT public.t_attempt('INSERT INTO score_table_versions(score_table_id,owner_org_id,exam_code,version_no,entries,created_by) VALUES (''56000000-0000-0000-0000-000000000001'',NULL,''IELTS'',2,''[]'',''d6000000-0000-0000-0000-000000000001'')') = 'rows:1', 'the author starts a draft';
  ASSERT public.t_attempt('INSERT INTO score_table_versions(score_table_id,owner_org_id,exam_code,version_no,entries,created_by) VALUES (''56000000-0000-0000-0000-000000000001'',NULL,''IELTS'',3,''[]'',''d6000000-0000-0000-0000-000000000001'')') = 'err:23505', 'but not a second open version of the same table';
  ASSERT public.t_attempt('INSERT INTO grading_skill_versions(grading_skill_id,owner_org_id,exam_code,version_no,created_by) VALUES (''66000000-0000-0000-0000-000000000001'',NULL,''IELTS'',2,''d6000000-0000-0000-0000-000000000001'')') = 'rows:1', 'the same for a skill: a draft';
  ASSERT public.t_attempt('INSERT INTO grading_skill_versions(grading_skill_id,owner_org_id,exam_code,version_no,created_by) VALUES (''66000000-0000-0000-0000-000000000001'',NULL,''IELTS'',3,''d6000000-0000-0000-0000-000000000001'')') = 'err:23505', 'and not a second one';
  ASSERT public.t_attempt('UPDATE score_table_versions SET entries = ''[{"raw":0,"scaled":1}]'' WHERE version_no = 2') = 'rows:1', 'the author edits their own draft';
  ASSERT public.t_attempt('UPDATE grading_skill_versions SET rubric = ''mine'' WHERE version_no = 2') = 'rows:1', 'and their own skill draft';
END $$;
SELECT set_config('app.user_id','d6000000-0000-0000-0000-000000000003',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM score_table_versions WHERE version_no = 2) = 1, 'the colleague reads the draft';
  ASSERT public.t_attempt('UPDATE score_table_versions SET entries = ''[]'' WHERE version_no = 2') = 'rows:0', 'but edits nothing of it';
  ASSERT public.t_attempt('DELETE FROM score_table_versions WHERE version_no = 2') = 'rows:0', 'deletes nothing of it';
  ASSERT public.t_attempt('UPDATE grading_skill_versions SET rubric = ''x'' WHERE version_no = 2') = 'rows:0', 'neither the skill draft';
  ASSERT public.t_attempt('DELETE FROM grading_skill_versions WHERE version_no = 2') = 'rows:0', 'nor deletes it';
  ASSERT public.t_attempt('UPDATE score_table_versions SET status = ''IN_REVIEW'' WHERE version_no = 2') = 'rows:0', 'nor submits it';
END $$;
RESET ROLE;
