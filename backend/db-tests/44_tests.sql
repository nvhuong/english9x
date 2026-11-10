-- P2.6 tests (the builder of exams, F13) and the TEST scope of score tables and grading policies (V202611120001, V202611120002).
--  * A test version is versioned content like a question group: the AUTHOR of the exam writes a DRAFT as themselves and edits only what they created; a
--    REVIEWER decides; nothing changes a version that is not DRAFT / CHANGES_REQUESTED; one open version per test.
--  * Its parts and groups have their own guards: no INSERT, UPDATE or DELETE unless the parent version is DRAFT / CHANGES_REQUESTED, and a part holds only
--    groups of the test's owner and sections of the test's exam. The guards bind the owner of the tables and a superuser too.
--  * A learner reads a PUBLISHED / ARCHIVED version in the owner's context (the exam room, the grader's lookup), never a draft; writes nothing.
--  * TEST scope: a row needs a real test of the same owner and exam; the writers are the administrator of the test's owner only (SYS_ADMIN for an English9x
--    test, ORG_ADMIN for an organisation's), never the exam's author, reviewer or a learner; a TEST grading policy is frozen once its test is published.
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS'),('TOEIC','TOEIC') ON CONFLICT DO NOTHING;
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','Center B','CENTER'),
 ('cccccccc-0000-0000-0000-00000000000c','Enterprise C','ENTERPRISE');
INSERT INTO users(id,email) VALUES
 ('e5000000-0000-0000-0000-000000000001','author-a-ielts@x.test'),
 ('e5000000-0000-0000-0000-000000000002','reviewer-a-ielts@x.test'),
 ('e5000000-0000-0000-0000-000000000003','learner-a@x.test'),
 ('e5000000-0000-0000-0000-000000000004','orgadmin-a@x.test'),
 ('e5000000-0000-0000-0000-000000000005','sysadmin@x.test'),
 ('e5000000-0000-0000-0000-000000000006','author-e9-ielts@x.test'),
 ('e5000000-0000-0000-0000-000000000007','orgadmin-b@x.test'),
 ('e5000000-0000-0000-0000-000000000008','author-b-ielts@x.test'),
 ('e5000000-0000-0000-0000-000000000009','orgadmin-c@x.test'),
 ('e5000000-0000-0000-0000-00000000000a','colleague-a-ielts@x.test'),
 ('e5000000-0000-0000-0000-00000000000b','author-a-toeic@x.test');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','e5000000-0000-0000-0000-000000000001'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','e5000000-0000-0000-0000-000000000002'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','e5000000-0000-0000-0000-000000000003'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','e5000000-0000-0000-0000-000000000004'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','e5000000-0000-0000-0000-00000000000a'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','e5000000-0000-0000-0000-00000000000b'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','e5000000-0000-0000-0000-000000000007'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','e5000000-0000-0000-0000-000000000008'),
 ('cccccccc-0000-0000-0000-00000000000c','e5000000-0000-0000-0000-000000000009');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('e5000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','AUTHOR'),
 ('e5000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','REVIEWER'),
 ('e5000000-0000-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'LEARNER'),
 ('e5000000-0000-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN'),
 ('e5000000-0000-0000-0000-000000000005',NULL,NULL,'SYS_ADMIN'),
 ('e5000000-0000-0000-0000-000000000006',NULL,'IELTS','AUTHOR'),
 ('e5000000-0000-0000-0000-000000000007','bbbbbbbb-0000-0000-0000-00000000000b',NULL,'ORG_ADMIN'),
 ('e5000000-0000-0000-0000-000000000008','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','AUTHOR'),
 ('e5000000-0000-0000-0000-000000000009','cccccccc-0000-0000-0000-00000000000c',NULL,'ORG_ADMIN'),
 ('e5000000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','AUTHOR'),
 ('e5000000-0000-0000-0000-00000000000b','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC','AUTHOR');

-- ---- fixtures as the owner of the tables ------------------------------------------------------------------------------------------
-- question groups (PUBLISHED unless said): gA1, gA2 (a DRAFT) of org A; gB1 of org B; gE1 of English9x. One question each (a group needs no member to exist).
INSERT INTO question_groups(id,owner_org_id,exam_code,created_by) VALUES
 ('91000000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','e5000000-0000-0000-0000-000000000001'),
 ('91000000-0000-0000-0000-0000000000a2','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','e5000000-0000-0000-0000-000000000001'),
 ('91000000-0000-0000-0000-0000000000b1','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','e5000000-0000-0000-0000-000000000008'),
 ('91000000-0000-0000-0000-0000000000e1',NULL,'IELTS','e5000000-0000-0000-0000-000000000006'),
 ('91000000-0000-0000-0000-0000000000a3','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC','e5000000-0000-0000-0000-00000000000b');
INSERT INTO question_group_versions(id,group_id,owner_org_id,exam_code,version_no,status,content,created_by) VALUES
 ('92000000-0000-0000-0000-0000000000a1','91000000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-00000000000a','IELTS',1,'DRAFT','{"title":"gA1"}','e5000000-0000-0000-0000-000000000001'),
 ('92000000-0000-0000-0000-0000000000a2','91000000-0000-0000-0000-0000000000a2','aaaaaaaa-0000-0000-0000-00000000000a','IELTS',1,'DRAFT','{"title":"gA2"}','e5000000-0000-0000-0000-000000000001'),
 ('92000000-0000-0000-0000-0000000000b1','91000000-0000-0000-0000-0000000000b1','bbbbbbbb-0000-0000-0000-00000000000b','IELTS',1,'DRAFT','{"title":"gB1"}','e5000000-0000-0000-0000-000000000008'),
 ('92000000-0000-0000-0000-0000000000e1','91000000-0000-0000-0000-0000000000e1',NULL,'IELTS',1,'DRAFT','{"title":"gE1"}','e5000000-0000-0000-0000-000000000006'),
 ('92000000-0000-0000-0000-0000000000a3','91000000-0000-0000-0000-0000000000a3','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC',1,'DRAFT','{"title":"gA3"}','e5000000-0000-0000-0000-00000000000b');
UPDATE question_group_versions SET status = 'IN_REVIEW' WHERE id IN ('92000000-0000-0000-0000-0000000000a1','92000000-0000-0000-0000-0000000000b1','92000000-0000-0000-0000-0000000000e1','92000000-0000-0000-0000-0000000000a3');
UPDATE question_group_versions SET status = 'PUBLISHED' WHERE id IN ('92000000-0000-0000-0000-0000000000a1','92000000-0000-0000-0000-0000000000b1','92000000-0000-0000-0000-0000000000e1','92000000-0000-0000-0000-0000000000a3');

-- tests. tA (org A, IELTS AC): v1 PUBLISHED, v2 DRAFT (author A). tR (org A): v1 IN_REVIEW. tD (org A): v1 DRAFT only. tB (org B): v1 PUBLISHED.
-- tE (English9x): v1 PUBLISHED. tC (enterprise C): v1 DRAFT only. tT (org A, TOEIC): v1 DRAFT.
INSERT INTO tests(id,owner_org_id,exam_code,variant,created_by) VALUES
 ('a1000000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','AC','e5000000-0000-0000-0000-000000000001'),
 ('a1000000-0000-0000-0000-0000000000a2','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','GT','e5000000-0000-0000-0000-000000000001'),
 ('a1000000-0000-0000-0000-0000000000d1','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','AC','e5000000-0000-0000-0000-000000000001'),
 ('a1000000-0000-0000-0000-00000000000b','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','AC','e5000000-0000-0000-0000-000000000008'),
 ('a1000000-0000-0000-0000-0000000000e9',NULL,'IELTS','AC','e5000000-0000-0000-0000-000000000006'),
 ('a1000000-0000-0000-0000-00000000000c','cccccccc-0000-0000-0000-00000000000c','IELTS','AC','e5000000-0000-0000-0000-000000000001'),
 ('a1000000-0000-0000-0000-0000000000f1','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC',NULL,'e5000000-0000-0000-0000-00000000000b');
INSERT INTO test_versions(id,test_id,owner_org_id,exam_code,variant,version_no,status,title,created_by) VALUES
 ('a2000000-0000-0000-0000-0000000000a1','a1000000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','AC',1,'DRAFT','tA v1','e5000000-0000-0000-0000-000000000001'),
 ('a2000000-0000-0000-0000-0000000000a3','a1000000-0000-0000-0000-0000000000a2','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','GT',1,'DRAFT','tR v1','e5000000-0000-0000-0000-000000000001'),
 ('a2000000-0000-0000-0000-0000000000d1','a1000000-0000-0000-0000-0000000000d1','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','AC',1,'DRAFT','tD v1','e5000000-0000-0000-0000-000000000001'),
 ('a2000000-0000-0000-0000-0000000000b1','a1000000-0000-0000-0000-00000000000b','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','AC',1,'DRAFT','tB v1','e5000000-0000-0000-0000-000000000008'),
 ('a2000000-0000-0000-0000-0000000000e1','a1000000-0000-0000-0000-0000000000e9',NULL,'IELTS','AC',1,'DRAFT','tE v1','e5000000-0000-0000-0000-000000000006'),
 ('a2000000-0000-0000-0000-0000000000c1','a1000000-0000-0000-0000-00000000000c','cccccccc-0000-0000-0000-00000000000c','IELTS','AC',1,'DRAFT','tC v1','e5000000-0000-0000-0000-000000000001'),
 ('a2000000-0000-0000-0000-0000000000f1','a1000000-0000-0000-0000-0000000000f1','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC',NULL,1,'DRAFT','tT v1','e5000000-0000-0000-0000-00000000000b');
-- parts and groups while the versions are still drafts (the guard of the children allows nothing later)
INSERT INTO test_parts(test_version_id,position,section_code,duration_sec,owner_org_id,exam_code) VALUES
 ('a2000000-0000-0000-0000-0000000000a1',1,'LISTENING',1800,'aaaaaaaa-0000-0000-0000-00000000000a','IELTS'),
 ('a2000000-0000-0000-0000-0000000000a3',1,'READING',3600,'aaaaaaaa-0000-0000-0000-00000000000a','IELTS'),
 ('a2000000-0000-0000-0000-0000000000b1',1,'LISTENING',1800,'bbbbbbbb-0000-0000-0000-00000000000b','IELTS'),
 ('a2000000-0000-0000-0000-0000000000e1',1,'LISTENING',1800,NULL,'IELTS');
INSERT INTO test_part_groups(test_version_id,part_position,position,group_version_id,owner_org_id,exam_code) VALUES
 ('a2000000-0000-0000-0000-0000000000a1',1,1,'92000000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-00000000000a','IELTS'),
 ('a2000000-0000-0000-0000-0000000000a3',1,1,'92000000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-00000000000a','IELTS'),
 ('a2000000-0000-0000-0000-0000000000b1',1,1,'92000000-0000-0000-0000-0000000000b1','bbbbbbbb-0000-0000-0000-00000000000b','IELTS'),
 ('a2000000-0000-0000-0000-0000000000e1',1,1,'92000000-0000-0000-0000-0000000000e1',NULL,'IELTS');
-- states: tA v1 PUBLISHED, tR v1 IN_REVIEW (with its open review), tB v1 and tE v1 PUBLISHED
UPDATE test_versions SET status = 'IN_REVIEW' WHERE id IN ('a2000000-0000-0000-0000-0000000000a1','a2000000-0000-0000-0000-0000000000a3','a2000000-0000-0000-0000-0000000000b1','a2000000-0000-0000-0000-0000000000e1');
UPDATE test_versions SET status = 'PUBLISHED' WHERE id IN ('a2000000-0000-0000-0000-0000000000a1','a2000000-0000-0000-0000-0000000000b1','a2000000-0000-0000-0000-0000000000e1');
-- tA v2: a draft of the author's, made once v1 is out of the way (one open version per test)
INSERT INTO test_versions(id,test_id,owner_org_id,exam_code,variant,version_no,status,title,created_by) VALUES
 ('a2000000-0000-0000-0000-0000000000a2','a1000000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','AC',2,'DRAFT','tA v2','e5000000-0000-0000-0000-000000000001');
INSERT INTO test_parts(test_version_id,position,section_code,duration_sec,owner_org_id,exam_code) VALUES
 ('a2000000-0000-0000-0000-0000000000a2',1,'LISTENING',1800,'aaaaaaaa-0000-0000-0000-00000000000a','IELTS');
INSERT INTO test_part_groups(test_version_id,part_position,position,group_version_id,owner_org_id,exam_code) VALUES
 ('a2000000-0000-0000-0000-0000000000a2',1,1,'92000000-0000-0000-0000-0000000000a1','aaaaaaaa-0000-0000-0000-00000000000a','IELTS');
INSERT INTO review_requests(content_type,version_id,owner_org_id,exam_code,author_id,submitted_by) VALUES
 ('test','a2000000-0000-0000-0000-0000000000a3','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','e5000000-0000-0000-0000-000000000001','e5000000-0000-0000-0000-000000000001');

CREATE FUNCTION public.t_attempt(stmt text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  EXECUTE stmt; GET DIAGNOSTICS n = ROW_COUNT;
  RETURN 'rows:' || n;
EXCEPTION WHEN OTHERS THEN RETURN 'err:' || SQLSTATE;
END $$;
GRANT EXECUTE ON FUNCTION public.t_attempt(text) TO e9x_app, e9x_grader;
CREATE FUNCTION public.t_msg(stmt text) RETURNS text LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE stmt;
  RETURN 'ok';
EXCEPTION WHEN OTHERS THEN RETURN SQLSTATE || ' ' || SQLERRM;
END $$;
GRANT EXECUTE ON FUNCTION public.t_msg(text) TO e9x_app, e9x_grader;
-- "refused": no row changed, or a row-level-security / privilege error, or a guard (a BEFORE trigger runs before the WITH CHECK of a policy)
CREATE FUNCTION public.t_refused(stmt text) RETURNS boolean LANGUAGE sql AS $$ SELECT public.t_attempt(stmt) IN ('rows:0', 'err:42501', 'err:23000') $$;
GRANT EXECUTE ON FUNCTION public.t_refused(text) TO e9x_app, e9x_grader;

-- ---- the tables and the guards of the children, as the owner (a guard binds the owner of the tables and a superuser too) --------------
DO $$
BEGIN
  ASSERT (SELECT bool_and(relrowsecurity) FROM pg_class WHERE relname IN ('tests','test_versions','test_parts','test_part_groups')), 'every test table has row level security';
  ASSERT (SELECT count(*) FROM pg_trigger WHERE tgname IN ('test_versions_lifecycle','test_versions_decision','test_parts_guard','test_part_groups_guard')) = 4, 'lifecycle, decision and the two child guards';
  -- the parent is PUBLISHED (tA v1): nothing of its parts is added, changed or removed
  ASSERT public.t_msg('INSERT INTO test_parts(test_version_id,position,section_code,duration_sec,owner_org_id,exam_code) VALUES (''a2000000-0000-0000-0000-0000000000a1'',2,''READING'',3600,''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') LIKE '23000%cannot change%',
         'a part cannot be added to a PUBLISHED version';
  ASSERT public.t_msg('UPDATE test_parts SET duration_sec = 1 WHERE test_version_id = ''a2000000-0000-0000-0000-0000000000a1''') LIKE '23000%cannot change%', 'nor changed';
  ASSERT public.t_msg('DELETE FROM test_parts WHERE test_version_id = ''a2000000-0000-0000-0000-0000000000a1''') LIKE '23000%cannot change%', 'nor removed';
  ASSERT public.t_msg('INSERT INTO test_part_groups(test_version_id,part_position,position,group_version_id,owner_org_id,exam_code) VALUES (''a2000000-0000-0000-0000-0000000000a1'',1,2,''92000000-0000-0000-0000-0000000000a2'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') LIKE '23000%cannot change%',
         'a group cannot be added to a PUBLISHED version';
  ASSERT public.t_msg('UPDATE test_part_groups SET position = 2 WHERE test_version_id = ''a2000000-0000-0000-0000-0000000000a1''') LIKE '23000%cannot change%', 'nor moved';
  ASSERT public.t_msg('DELETE FROM test_part_groups WHERE test_version_id = ''a2000000-0000-0000-0000-0000000000a1''') LIKE '23000%cannot change%', 'nor removed';
  -- under review (tR v1): the same
  ASSERT public.t_msg('DELETE FROM test_part_groups WHERE test_version_id = ''a2000000-0000-0000-0000-0000000000a3''') LIKE '23000%cannot change%', 'a version IN_REVIEW is frozen too';
  ASSERT public.t_msg('UPDATE test_parts SET duration_sec = 2 WHERE test_version_id = ''a2000000-0000-0000-0000-0000000000a3''') LIKE '23000%cannot change%';
  ASSERT public.t_msg('INSERT INTO test_parts(test_version_id,position,section_code,duration_sec,owner_org_id,exam_code) VALUES (''a2000000-0000-0000-0000-0000000000a3'',2,''LISTENING'',60,''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') LIKE '23000%cannot change%';
  -- a DRAFT parent (tA v2) is editable; the rest of the rules of a part
  ASSERT public.t_msg('INSERT INTO test_parts(test_version_id,position,section_code,duration_sec,owner_org_id,exam_code) VALUES (''a2000000-0000-0000-0000-0000000000a2'',2,''READING'',3600,''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') = 'ok', 'a part of a DRAFT';
  ASSERT public.t_msg('INSERT INTO test_parts(test_version_id,position,section_code,duration_sec,owner_org_id,exam_code) VALUES (''a2000000-0000-0000-0000-0000000000a2'',3,''READING'',3600,''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') LIKE '23505%', 'one part per section';
  ASSERT public.t_msg('INSERT INTO test_parts(test_version_id,position,section_code,duration_sec,owner_org_id,exam_code) VALUES (''a2000000-0000-0000-0000-0000000000a2'',3,''P5'',3600,''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') LIKE '23503%', 'a section of another exam is refused (composite key)';
  ASSERT public.t_msg('INSERT INTO test_parts(test_version_id,position,section_code,duration_sec,owner_org_id,exam_code) VALUES (''a2000000-0000-0000-0000-0000000000a2'',3,''WRITING'',3600,NULL,''IELTS'')') LIKE '23000%carries the owner%', 'a part carries the owner of its version';
  ASSERT public.t_msg('INSERT INTO test_parts(test_version_id,position,section_code,duration_sec,owner_org_id,exam_code) VALUES (''a2000000-0000-0000-0000-0000000000a2'',3,''WRITING'',0,''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') LIKE '23514%', 'a time limit is positive';
  ASSERT public.t_msg('INSERT INTO test_part_groups(test_version_id,part_position,position,group_version_id,owner_org_id,exam_code) VALUES (''a2000000-0000-0000-0000-0000000000a2'',2,1,''92000000-0000-0000-0000-0000000000b1'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') LIKE '23000%own owner%',
         'a test holds groups of its own owner: org B''s group in org A''s test';
  ASSERT public.t_msg('INSERT INTO test_part_groups(test_version_id,part_position,position,group_version_id,owner_org_id,exam_code) VALUES (''a2000000-0000-0000-0000-0000000000a2'',2,1,''92000000-0000-0000-0000-0000000000e1'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') LIKE '23000%own owner%', 'and English9x''s group too';
  ASSERT public.t_msg('INSERT INTO test_part_groups(test_version_id,part_position,position,group_version_id,owner_org_id,exam_code) VALUES (''a2000000-0000-0000-0000-0000000000a2'',2,1,''92000000-0000-0000-0000-0000000000a3'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') LIKE '23503%', 'a group of another exam (composite key)';
  ASSERT public.t_msg('INSERT INTO test_part_groups(test_version_id,part_position,position,group_version_id,owner_org_id,exam_code) VALUES (''a2000000-0000-0000-0000-0000000000a2'',2,1,''92000000-0000-0000-0000-0000000000a1'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') LIKE '23505%', 'a group version once per test';
  ASSERT public.t_msg('INSERT INTO test_part_groups(test_version_id,part_position,position,group_version_id,owner_org_id,exam_code) VALUES (''a2000000-0000-0000-0000-0000000000a2'',9,1,''92000000-0000-0000-0000-0000000000a2'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') LIKE '23503%', 'a group needs its part';
  ASSERT public.t_msg('INSERT INTO test_part_groups(test_version_id,part_position,position,group_version_id,owner_org_id,exam_code) VALUES (''a2000000-0000-0000-0000-0000000000a2'',2,1,''92000000-0000-0000-0000-0000000000a2'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') = 'ok', 'a DRAFT group may be listed (publication is what needs it PUBLISHED: the workflow dependency)';
END $$;
-- the two rows just added to tA v2 are not needed by what follows
DELETE FROM test_part_groups WHERE test_version_id = 'a2000000-0000-0000-0000-0000000000a2' AND part_position = 2;
DELETE FROM test_parts WHERE test_version_id = 'a2000000-0000-0000-0000-0000000000a2' AND position = 2;

-- ---- the AUTHOR of IELTS in org A -------------------------------------------------------------------------------------------------
SET ROLE e9x_app;
SELECT set_config('app.user_id','e5000000-0000-0000-0000-000000000001',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM tests) = 3, 'the author reads org A''s IELTS tests (tA, tR, tD) and nothing of B''s, English9x''s, the enterprise''s or the TOEIC one: ' || (SELECT count(*) FROM tests);
  ASSERT (SELECT count(*) FROM test_versions WHERE owner_org_id = 'aaaaaaaa-0000-0000-0000-00000000000a') = 4, 'their versions, every status';
  ASSERT (SELECT count(*) FROM test_versions WHERE owner_org_id IS DISTINCT FROM 'aaaaaaaa-0000-0000-0000-00000000000a') = 1, 'plus English9x''s published one: the public rule, in this context';
  -- (the TOEIC test of org A is hidden: the author holds IELTS only)
  ASSERT (SELECT count(*) FROM test_versions WHERE exam_code = 'TOEIC') = 0, 'not a TOEIC draft';
  ASSERT (SELECT count(*) FROM test_parts WHERE test_version_id = 'a2000000-0000-0000-0000-0000000000a2') = 1, 'the parts of their draft';
  -- writing as themselves, in their own context
  ASSERT public.t_attempt('INSERT INTO tests(id,owner_org_id,exam_code,variant,created_by) VALUES (''a1000000-0000-0000-0000-0000000000aa'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''GT'',''e5000000-0000-0000-0000-000000000001'')') = 'rows:1', 'the author makes a test';
  ASSERT public.t_attempt('INSERT INTO test_versions(id,test_id,owner_org_id,exam_code,variant,version_no,title,created_by) VALUES (''a2000000-0000-0000-0000-0000000000aa'',''a1000000-0000-0000-0000-0000000000aa'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''GT'',1,''new'',''e5000000-0000-0000-0000-000000000001'')') = 'rows:1', 'and its first draft';
  ASSERT public.t_attempt('INSERT INTO test_parts(test_version_id,position,section_code,duration_sec,owner_org_id,exam_code) VALUES (''a2000000-0000-0000-0000-0000000000aa'',1,''LISTENING'',1800,''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') = 'rows:1', 'a part of it';
  ASSERT public.t_attempt('INSERT INTO test_part_groups(test_version_id,part_position,position,group_version_id,owner_org_id,exam_code) VALUES (''a2000000-0000-0000-0000-0000000000aa'',1,1,''92000000-0000-0000-0000-0000000000a1'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') = 'rows:1', 'a group in it';
  ASSERT public.t_attempt('DELETE FROM test_part_groups WHERE test_version_id = ''a2000000-0000-0000-0000-0000000000aa''') = 'rows:1', 'and replaces it (DELETE, then INSERT)';
  ASSERT public.t_attempt('INSERT INTO tests(owner_org_id,exam_code,created_by) VALUES (''bbbbbbbb-0000-0000-0000-00000000000b'',''IELTS'',''e5000000-0000-0000-0000-000000000001'')') = 'err:42501', 'not into another organisation';
  ASSERT public.t_attempt('INSERT INTO tests(owner_org_id,exam_code,created_by) VALUES (NULL,''IELTS'',''e5000000-0000-0000-0000-000000000001'')') = 'err:42501', 'not into English9x from an organisation context';
  ASSERT public.t_attempt('INSERT INTO tests(owner_org_id,exam_code,created_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''TOEIC'',''e5000000-0000-0000-0000-000000000001'')') = 'err:42501', 'not for an exam the person is no author of';
  ASSERT public.t_attempt('INSERT INTO tests(owner_org_id,exam_code,created_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''e5000000-0000-0000-0000-00000000000a'')') = 'err:42501', 'not as somebody else';
  ASSERT public.t_attempt('INSERT INTO test_versions(test_id,owner_org_id,exam_code,variant,version_no,title,created_by) VALUES (''a1000000-0000-0000-0000-00000000000a'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''AC'',3,''x'',''e5000000-0000-0000-0000-000000000001'')') = 'err:23505', 'one open version per test: tA already has a draft';
  ASSERT public.t_attempt('INSERT INTO test_versions(test_id,owner_org_id,exam_code,variant,version_no,title,created_by) VALUES (''a1000000-0000-0000-0000-0000000000d1'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''GT'',2,''x'',''e5000000-0000-0000-0000-000000000001'')') = 'err:42501', 'a version carries the variant of its test';
  ASSERT public.t_refused('INSERT INTO test_versions(test_id,owner_org_id,exam_code,variant,version_no,status,title,created_by) VALUES (''a1000000-0000-0000-0000-0000000000a2'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''GT'',2,''PUBLISHED'',''x'',''e5000000-0000-0000-0000-000000000001'')'), 'a version cannot be inserted already PUBLISHED';
  -- editing: the own draft yes; published and in-review never; the status moves only as the lifecycle allows; nothing is deletable
  ASSERT public.t_attempt('UPDATE test_versions SET title = ''edited'', max_resumes = 3 WHERE id = ''a2000000-0000-0000-0000-0000000000a2''') = 'rows:1', 'the author edits their own draft';
  ASSERT public.t_refused('UPDATE test_versions SET title = ''x'' WHERE id = ''a2000000-0000-0000-0000-0000000000a1'''), 'but not the published version';
  ASSERT public.t_refused('UPDATE test_versions SET title = ''x'' WHERE id = ''a2000000-0000-0000-0000-0000000000a3'''), 'nor one under review';
  ASSERT public.t_refused('UPDATE test_versions SET status = ''PUBLISHED'' WHERE id = ''a2000000-0000-0000-0000-0000000000a2'''), 'the author cannot publish their own draft';
  ASSERT public.t_attempt('UPDATE test_versions SET max_resumes = 99 WHERE id = ''a2000000-0000-0000-0000-0000000000a2''') = 'err:23514', 'a sensible limit on resumes';
  ASSERT public.t_attempt('UPDATE test_versions SET owner_org_id = NULL, exam_code = ''TOEIC'', variant = ''GT'' WHERE id = ''a2000000-0000-0000-0000-0000000000a2''') = 'err:42501', 'identity columns have no UPDATE privilege';
  ASSERT public.t_attempt('DELETE FROM test_versions') = 'err:42501', 'no DELETE of versions';
  ASSERT public.t_attempt('DELETE FROM tests') = 'err:42501', 'no DELETE of tests';
  ASSERT public.t_attempt('UPDATE tests SET exam_code = ''TOEIC''') = 'err:42501', 'a header never changes';
  -- the children of versions that are not editable, through the policies
  ASSERT public.t_refused('DELETE FROM test_part_groups WHERE test_version_id = ''a2000000-0000-0000-0000-0000000000a1'''), 'the author cannot remove a group of the PUBLISHED version';
  ASSERT public.t_refused('DELETE FROM test_parts WHERE test_version_id = ''a2000000-0000-0000-0000-0000000000a3'''), 'nor a part of the one under review';
  ASSERT public.t_refused('INSERT INTO test_parts(test_version_id,position,section_code,duration_sec,owner_org_id,exam_code) VALUES (''a2000000-0000-0000-0000-0000000000a1'',3,''WRITING'',60,''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')'), 'nor add a part to the PUBLISHED version';
  ASSERT public.t_attempt('UPDATE test_parts SET duration_sec = 1') = 'err:42501', 'parts are never updated by the application';
  -- TEST scope: the author of the exam is NOT a writer (the administrator of the owner is)
  ASSERT public.t_attempt('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''LISTENING'',''TEST'',''a1000000-0000-0000-0000-00000000000a'')') = 'err:42501', 'the author writes no TEST score table';
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,test_id,mode,updated_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''TEST'',''a1000000-0000-0000-0000-0000000000d1'',''AI'',''e5000000-0000-0000-0000-000000000001'')') = 'err:42501', 'nor a TEST grading policy';
END $$;

-- ---- the colleague author (another AUTHOR of IELTS in org A): reads, edits nothing of the first author's -----------------------------
SELECT set_config('app.user_id','e5000000-0000-0000-0000-00000000000a',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM test_versions WHERE id = 'a2000000-0000-0000-0000-0000000000a2') = 1, 'a colleague reads the draft';
  ASSERT public.t_attempt('UPDATE test_versions SET title = ''taken over'' WHERE id = ''a2000000-0000-0000-0000-0000000000a2''') = 'rows:0', 'but cannot take it over';
  ASSERT public.t_refused('DELETE FROM test_parts WHERE test_version_id = ''a2000000-0000-0000-0000-0000000000a2'''), 'nor remove its parts';
  ASSERT public.t_refused('INSERT INTO test_parts(test_version_id,position,section_code,duration_sec,owner_org_id,exam_code) VALUES (''a2000000-0000-0000-0000-0000000000a2'',2,''WRITING'',60,''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')'), 'nor add one';
END $$;

-- ---- the REVIEWER of IELTS in org A -----------------------------------------------------------------------------------------------
SELECT set_config('app.user_id','e5000000-0000-0000-0000-000000000002',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM test_versions WHERE status = 'IN_REVIEW' AND owner_org_id = 'aaaaaaaa-0000-0000-0000-00000000000a') = 1, 'the reviewer reads what is under review';
  ASSERT public.t_attempt('UPDATE test_versions SET status = ''PUBLISHED'' WHERE id = ''a2000000-0000-0000-0000-0000000000a3''') = 'rows:1', 'and publishes it (its open review exists)';
  ASSERT public.t_attempt('UPDATE test_versions SET status = ''ARCHIVED'' WHERE id = ''a2000000-0000-0000-0000-0000000000a1''') = 'rows:1', 'and archives a published version';
  ASSERT public.t_attempt('INSERT INTO test_versions(test_id,owner_org_id,exam_code,variant,version_no,title,created_by) VALUES (''a1000000-0000-0000-0000-0000000000d1'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''AC'',2,''x'',''e5000000-0000-0000-0000-000000000002'')') = 'err:42501', 'a reviewer writes no draft';
  ASSERT public.t_attempt('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''LISTENING'',''TEST'',''a1000000-0000-0000-0000-00000000000a'')') = 'err:42501', 'a reviewer writes no TEST score table';
END $$;
-- put the states back for the rest of the file (as the owner): tR v1 is IN_REVIEW again is not possible, so it stays PUBLISHED; tA v1 stays ARCHIVED
RESET ROLE;

-- ---- a LEARNER of org A -----------------------------------------------------------------------------------------------------------
SET ROLE e9x_app;
SELECT set_config('app.user_id','e5000000-0000-0000-0000-000000000003',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM tests) = 0, 'a learner reads no test header';
  ASSERT (SELECT array_agg(title ORDER BY title) FROM test_versions) = ARRAY['tA v1','tE v1','tR v1'], 'a learner reads PUBLISHED / ARCHIVED versions of the context and of English9x: '
    || (SELECT array_agg(title ORDER BY title)::text FROM test_versions);
  ASSERT (SELECT count(*) FROM test_versions WHERE status NOT IN ('PUBLISHED','ARCHIVED')) = 0, 'never a draft';
  ASSERT (SELECT count(*) FROM test_versions WHERE title = 'tB v1') = 0, 'never another organisation''s';
  ASSERT (SELECT test_id FROM test_versions WHERE id = 'a2000000-0000-0000-0000-0000000000a1') = 'a1000000-0000-0000-0000-00000000000a', 'the lookup of the grader''s resolver works for an ARCHIVED version';
  ASSERT (SELECT count(*) FROM test_versions WHERE id = 'a2000000-0000-0000-0000-0000000000a2') = 0, 'and finds nothing for a draft';
  ASSERT (SELECT count(*) FROM test_parts) = 3 AND (SELECT count(*) FROM test_part_groups) = 3, 'the parts and groups of those versions, and no others';
  ASSERT (SELECT count(*) FROM test_parts WHERE test_version_id = 'a2000000-0000-0000-0000-0000000000a2') = 0, 'not those of a draft';
  ASSERT public.t_attempt('INSERT INTO tests(owner_org_id,exam_code,created_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''e5000000-0000-0000-0000-000000000003'')') = 'err:42501', 'a learner makes no test';
  ASSERT public.t_attempt('UPDATE test_versions SET title = ''x''') = 'rows:0', 'nor edits one';
  ASSERT public.t_attempt('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''LISTENING'',''TEST'',''a1000000-0000-0000-0000-00000000000a'')') = 'err:42501', 'a learner writes no TEST score table';
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,test_id,mode,updated_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''TEST'',''a1000000-0000-0000-0000-0000000000d1'',''AI'',''e5000000-0000-0000-0000-000000000003'')') = 'err:42501', 'nor a TEST grading policy';
END $$;

-- ---- the grader login: the lookup of the resolver, nothing else -------------------------------------------------------------------
RESET ROLE;
SET ROLE e9x_grader;
SELECT set_config('app.user_id','e5000000-0000-0000-0000-000000000003',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(title ORDER BY title) FROM test_versions) = ARRAY['tA v1','tE v1','tR v1'], 'the grader finds the published and archived versions of the context and English9x';
  ASSERT public.t_attempt('INSERT INTO tests(owner_org_id,exam_code,created_by) VALUES (NULL,''IELTS'',''e5000000-0000-0000-0000-000000000006'')') = 'err:42501', 'and writes nothing';
END $$;
RESET ROLE;

-- ---- TEST scope: the administrator of the owner (and only them) -------------------------------------------------------------------
SET ROLE e9x_app;
SELECT set_config('app.user_id','e5000000-0000-0000-0000-000000000004',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT public.t_attempt('INSERT INTO score_tables(id,owner_org_id,exam_code,skill,scope,test_id) VALUES (''51000000-0000-0000-0000-0000000000f1'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''LISTENING'',''TEST'',''a1000000-0000-0000-0000-00000000000a'')') = 'rows:1',
         'the ORG_ADMIN of org A makes the override of org A''s test';
  ASSERT public.t_attempt('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''LISTENING'',''TEST'',''a1000000-0000-0000-0000-00000000000a'')') = 'err:23505', 'once per skill';
  ASSERT public.t_msg('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''LISTENING'',''TEST'',''a1000000-0000-0000-0000-00000000000b'')') LIKE '23514%carries the owner and the exam of its test%',
         'not for the test of org B (owner match)';
  ASSERT public.t_msg('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''READING_AC'',''TEST'',''a1000000-0000-0000-0000-0000000000e9'')') LIKE '23514%',
         'not for the test of English9x either (the row''s owner is A, the test''s is English9x)';
  ASSERT public.t_msg('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''TOEIC'',''READING'',''TEST'',''a1000000-0000-0000-0000-00000000000a'')') LIKE '23514%',
         'nor with the exam of another exam than its test''s';
  ASSERT public.t_attempt('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (NULL,''IELTS'',''LISTENING'',''TEST'',''a1000000-0000-0000-0000-0000000000e9'')') = 'err:42501', 'an organisation''s administrator writes no English9x override (not in its context)';
  ASSERT public.t_attempt('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''READING_GT'',''EXAM'')') IN ('err:42501', 'err:23514'), 'an exam default is no organisation''s (and the ORG_ADMIN is no author)';
  -- grading policy of a test that is not published yet (tD): the ORG_ADMIN sets it
  ASSERT public.t_attempt('INSERT INTO grading_policies(id,owner_org_id,exam_code,scope,test_id,mode,updated_by) VALUES (''63000000-0000-0000-0000-0000000000f1'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''TEST'',''a1000000-0000-0000-0000-0000000000d1'',''BOTH'',''e5000000-0000-0000-0000-000000000004'')') = 'rows:1',
         'the ORG_ADMIN sets the grading mode of a test of the organisation';
  ASSERT public.t_attempt('UPDATE grading_policies SET mode = ''TEACHER'', updated_by = ''e5000000-0000-0000-0000-000000000004'' WHERE id = ''63000000-0000-0000-0000-0000000000f1''') = 'rows:1', 'and changes it while the test is not published';
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,test_id,mode,updated_by) VALUES (''bbbbbbbb-0000-0000-0000-00000000000b'',''IELTS'',''TEST'',''a1000000-0000-0000-0000-00000000000b'',''AI'',''e5000000-0000-0000-0000-000000000004'')') = 'err:42501', 'not for the test of org B';
  -- a published test: the policy is frozen (insert, update, delete): tA
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,test_id,mode,updated_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''TEST'',''a1000000-0000-0000-0000-00000000000a'',''AI'',''e5000000-0000-0000-0000-000000000004'')') = 'err:23000', 'no policy is set for a test that has a published version';
END $$;
SELECT set_config('app.user_id','e5000000-0000-0000-0000-000000000007',false), set_config('app.org_id','bbbbbbbb-0000-0000-0000-00000000000b',false);
DO $$
BEGIN
  ASSERT public.t_msg('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (''bbbbbbbb-0000-0000-0000-00000000000b'',''IELTS'',''LISTENING'',''TEST'',''a1000000-0000-0000-0000-00000000000a'')') LIKE '23514%',
         'the ORG_ADMIN of org B cannot hang a table on org A''s test';
  ASSERT public.t_attempt('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''READING_AC'',''TEST'',''a1000000-0000-0000-0000-00000000000a'')') = 'err:42501', 'nor in org A''s name (not their context)';
  ASSERT (SELECT count(*) FROM score_tables WHERE test_id = 'a1000000-0000-0000-0000-00000000000a') = 0, 'and reads nothing of org A''s';
END $$;
-- the administrator of English9x: the exam's author and the administrator of an organisation are no writers of an English9x test
SELECT set_config('app.user_id','e5000000-0000-0000-0000-000000000005',false), set_config('app.org_id','',false), set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  ASSERT public.t_attempt('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (NULL,''IELTS'',''LISTENING'',''TEST'',''a1000000-0000-0000-0000-0000000000e9'')') = 'rows:1', 'the SYS_ADMIN makes the override of an English9x test';
  ASSERT public.t_msg('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (NULL,''IELTS'',''READING_AC'',''TEST'',''a1000000-0000-0000-0000-00000000000a'')') LIKE '23514%', 'not for the test of an organisation (owner match)';
  ASSERT public.t_attempt('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''READING_AC'',''TEST'',''a1000000-0000-0000-0000-00000000000a'')') = 'err:42501', 'nor in the name of an organisation';
END $$;
SELECT set_config('app.user_id','e5000000-0000-0000-0000-000000000006',false);
DO $$
BEGIN
  ASSERT public.t_attempt('INSERT INTO score_tables(owner_org_id,exam_code,skill,scope,test_id) VALUES (NULL,''IELTS'',''READING_AC'',''TEST'',''a1000000-0000-0000-0000-0000000000e9'')') = 'err:42501', 'the AUTHOR of English9x is no writer either';
  ASSERT public.t_refused('INSERT INTO grading_policies(owner_org_id,exam_code,scope,test_id,mode,updated_by) VALUES (NULL,''IELTS'',''TEST'',''a1000000-0000-0000-0000-0000000000e9'',''AI'',''e5000000-0000-0000-0000-000000000006'')'), 'nor of a TEST policy (refused by the freeze of a published test or by RLS)';
END $$;
SELECT set_config('app.user_id','e5000000-0000-0000-0000-000000000005',false);
DO $$
BEGIN
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,test_id,mode,updated_by) VALUES (NULL,''IELTS'',''TEST'',''a1000000-0000-0000-0000-0000000000e9'',''BOTH'',''e5000000-0000-0000-0000-000000000005'')') = 'err:23000', 'the SYS_ADMIN may not set the mode of a published English9x test (frozen)';
END $$;
-- an ENTERPRISE organisation has no teachers: the policy of its test can only be AI
SELECT set_config('app.user_id','e5000000-0000-0000-0000-000000000009',false), set_config('app.org_id','cccccccc-0000-0000-0000-00000000000c',false), set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,test_id,mode,updated_by) VALUES (''cccccccc-0000-0000-0000-00000000000c'',''IELTS'',''TEST'',''a1000000-0000-0000-0000-00000000000c'',''BOTH'',''e5000000-0000-0000-0000-000000000009'')') = 'err:42501', 'an enterprise test cannot be graded by teachers';
  ASSERT public.t_attempt('INSERT INTO grading_policies(owner_org_id,exam_code,scope,test_id,mode,updated_by) VALUES (''cccccccc-0000-0000-0000-00000000000c'',''IELTS'',''TEST'',''a1000000-0000-0000-0000-00000000000c'',''AI'',''e5000000-0000-0000-0000-000000000009'')') = 'rows:1', 'but by AI';
END $$;
RESET ROLE;

-- ---- the policy of a test is frozen once the test is published (the owner and fixtures are exempt; the application is not) -------------------------
-- tD's policy (set above by the ORG_ADMIN) is changeable while tD has no published version; publish it, and it is not.
UPDATE test_versions SET status = 'IN_REVIEW' WHERE id = 'a2000000-0000-0000-0000-0000000000d1';
UPDATE test_versions SET status = 'PUBLISHED' WHERE id = 'a2000000-0000-0000-0000-0000000000d1';
SET ROLE e9x_app;
SELECT set_config('app.user_id','e5000000-0000-0000-0000-000000000004',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT public.t_attempt('UPDATE grading_policies SET mode = ''AI'', updated_by = ''e5000000-0000-0000-0000-000000000004'' WHERE id = ''63000000-0000-0000-0000-0000000000f1''') = 'err:23000', 'the mode of a published test cannot change';
  ASSERT public.t_attempt('DELETE FROM grading_policies WHERE id = ''63000000-0000-0000-0000-0000000000f1''') = 'err:23000', 'nor be removed';
  ASSERT (SELECT mode FROM grading_policies WHERE id = '63000000-0000-0000-0000-0000000000f1') = 'TEACHER', 'it is what it was';
END $$;
RESET ROLE;
