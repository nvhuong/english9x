-- P2.4 strategy lessons (feature F14), same rules as 40_questions.sql:
--  * reading: the AUTHOR / REVIEWER of THAT exam, in the row's own context, every status. A learner, a teacher, an org admin, the author of another
--    exam, the same person in another context: nothing.
--  * writing: an AUTHOR inserts a DRAFT as themselves and edits only a version they created; a REVIEWER locks and decides, only through an open review.
--  * the child table of question types has its own guard: nothing changes unless the parent version is DRAFT / CHANGES_REQUESTED.
--  * the learner's way in is e9x_strategy_lesson_exam_view (PUBLISHED / ARCHIVED only, English9x or the current organisation).
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS'),('TOEIC','TOEIC') ON CONFLICT DO NOTHING;
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','Center B','CENTER');
INSERT INTO users(id,email) VALUES
 ('d5000000-0000-0000-0000-000000000001','author-a-ielts@x.test'),
 ('d5000000-0000-0000-0000-000000000002','reviewer-a-ielts@x.test'),
 ('d5000000-0000-0000-0000-000000000003','learner-a@x.test'),
 ('d5000000-0000-0000-0000-000000000004','author-b-ielts@x.test'),
 ('d5000000-0000-0000-0000-000000000005','author-e9-ielts@x.test'),
 ('d5000000-0000-0000-0000-000000000006','teacher-a-ielts@x.test'),
 ('d5000000-0000-0000-0000-000000000007','author-a-toeic@x.test'),
 ('d5000000-0000-0000-0000-000000000008','coauthor-a-ielts@x.test'),
 ('d5000000-0000-0000-0000-000000000009','orgadmin-a@x.test');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','d5000000-0000-0000-0000-000000000001'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','d5000000-0000-0000-0000-000000000002'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','d5000000-0000-0000-0000-000000000003'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','d5000000-0000-0000-0000-000000000006'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','d5000000-0000-0000-0000-000000000007'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','d5000000-0000-0000-0000-000000000008'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','d5000000-0000-0000-0000-000000000009'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','d5000000-0000-0000-0000-000000000004');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('d5000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','AUTHOR'),
 ('d5000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','REVIEWER'),
 ('d5000000-0000-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'LEARNER'),
 ('d5000000-0000-0000-0000-000000000004','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','AUTHOR'),
 ('d5000000-0000-0000-0000-000000000005',NULL,'IELTS','AUTHOR'),
 ('d5000000-0000-0000-0000-000000000006','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','TEACHER'),
 ('d5000000-0000-0000-0000-000000000007','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC','AUTHOR'),
 ('d5000000-0000-0000-0000-000000000008','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','AUTHOR'),
 ('d5000000-0000-0000-0000-000000000009','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN');

-- Lessons: A has l1 (v1 PUBLISHED + v2 DRAFT by the author), l2 (v1 IN_REVIEW); B has l3 (v1 PUBLISHED); English9x has l4 (v1 PUBLISHED).
INSERT INTO strategy_lessons(id,owner_org_id,exam_code,created_by) VALUES
 ('c5000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','d5000000-0000-0000-0000-000000000001'),
 ('c5000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','d5000000-0000-0000-0000-000000000001'),
 ('c5000000-0000-0000-0000-000000000003','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','d5000000-0000-0000-0000-000000000004'),
 ('c5000000-0000-0000-0000-000000000004',NULL,'IELTS','d5000000-0000-0000-0000-000000000005');
INSERT INTO strategy_lesson_versions(id,lesson_id,owner_org_id,exam_code,version_no,status,title,blocks,created_by) VALUES
 ('f5000000-0000-0000-0000-000000000011','c5000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a','IELTS',1,'PUBLISHED','a1','[{"type":"paragraph","text":"p"}]','d5000000-0000-0000-0000-000000000001'),
 ('f5000000-0000-0000-0000-000000000012','c5000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a','IELTS',2,'DRAFT','a2','[]','d5000000-0000-0000-0000-000000000001'),
 ('f5000000-0000-0000-0000-000000000021','c5000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-00000000000a','IELTS',1,'IN_REVIEW','r1','[]','d5000000-0000-0000-0000-000000000001'),
 ('f5000000-0000-0000-0000-000000000031','c5000000-0000-0000-0000-000000000003','bbbbbbbb-0000-0000-0000-00000000000b','IELTS',1,'PUBLISHED','b1','[]','d5000000-0000-0000-0000-000000000004'),
 ('f5000000-0000-0000-0000-000000000041','c5000000-0000-0000-0000-000000000004',NULL,'IELTS',1,'PUBLISHED','e1','[]','d5000000-0000-0000-0000-000000000005');
-- fixtures of versions already decided: the types guard is switched off for the inserts only
ALTER TABLE strategy_lesson_types DISABLE TRIGGER strategy_lesson_types_guard_trg;
INSERT INTO strategy_lesson_types(version_id,type_code,owner_org_id,exam_code) VALUES
 ('f5000000-0000-0000-0000-000000000011','IELTS_R_SHORT_ANSWER','aaaaaaaa-0000-0000-0000-00000000000a','IELTS'),
 ('f5000000-0000-0000-0000-000000000012','IELTS_R_SHORT_ANSWER','aaaaaaaa-0000-0000-0000-00000000000a','IELTS'),
 ('f5000000-0000-0000-0000-000000000021','IELTS_R_SHORT_ANSWER','aaaaaaaa-0000-0000-0000-00000000000a','IELTS'),
 ('f5000000-0000-0000-0000-000000000031','IELTS_R_SHORT_ANSWER','bbbbbbbb-0000-0000-0000-00000000000b','IELTS'),
 ('f5000000-0000-0000-0000-000000000041','IELTS_R_SHORT_ANSWER',NULL,'IELTS');
ALTER TABLE strategy_lesson_types ENABLE TRIGGER strategy_lesson_types_guard_trg;
INSERT INTO review_requests(content_type,version_id,owner_org_id,exam_code,author_id,submitted_by) VALUES
 ('strategy_lesson','f5000000-0000-0000-0000-000000000021','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','d5000000-0000-0000-0000-000000000001','d5000000-0000-0000-0000-000000000001');

CREATE FUNCTION public.t_attempt(stmt text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  EXECUTE stmt; GET DIAGNOSTICS n = ROW_COUNT;
  RETURN 'rows:' || n;
EXCEPTION WHEN OTHERS THEN RETURN 'err:' || SQLSTATE;
END $$;
GRANT EXECUTE ON FUNCTION public.t_attempt(text) TO e9x_app;
CREATE FUNCTION public.t_refused(stmt text) RETURNS boolean LANGUAGE sql AS $$ SELECT public.t_attempt(stmt) IN ('rows:0', 'err:42501', 'err:23000') $$;
GRANT EXECUTE ON FUNCTION public.t_refused(text) TO e9x_app;

DO $$
BEGIN
  ASSERT (SELECT bool_and(relrowsecurity) FROM pg_class WHERE relname IN ('strategy_lessons','strategy_lesson_versions','strategy_lesson_types')), 'row level security on every lesson table';
  ASSERT (SELECT count(*) FROM pg_trigger WHERE tgname IN ('strategy_lesson_versions_lifecycle','strategy_lesson_versions_decision')) = 2, 'lifecycle and decision guards are attached';
  ASSERT public.t_attempt('INSERT INTO strategy_lesson_versions(lesson_id,owner_org_id,exam_code,version_no,title,blocks,created_by) VALUES (''c5000000-0000-0000-0000-000000000004'',NULL,''IELTS'',9,''x'',''{}'',''d5000000-0000-0000-0000-000000000005'')') = 'err:23514', 'blocks are an array';
  ASSERT public.t_attempt('INSERT INTO strategy_lesson_versions(lesson_id,owner_org_id,exam_code,version_no,title,blocks,created_by) VALUES (''c5000000-0000-0000-0000-000000000004'',NULL,''IELTS'',9,''  '',''[]'',''d5000000-0000-0000-0000-000000000005'')') = 'err:23514', 'a title is required';
  ASSERT public.t_attempt('INSERT INTO strategy_lesson_versions(lesson_id,owner_org_id,exam_code,version_no,title,blocks,created_by) VALUES (''c5000000-0000-0000-0000-000000000001'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',3,''x'',''[]'',''d5000000-0000-0000-0000-000000000001'')') = 'err:23505', 'one open version per lesson';
  ASSERT public.t_attempt('INSERT INTO strategy_lesson_types(version_id,type_code,owner_org_id,exam_code) VALUES (''f5000000-0000-0000-0000-000000000012'',''TOEIC_P5_INCOMPLETE_SENTENCE'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') = 'err:23503', 'a type of another exam is no type of the lesson';
END $$;

-- ---- the author of the exam, in the owner's context
SET ROLE e9x_app;
SELECT set_config('app.user_id','d5000000-0000-0000-0000-000000000001',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM strategy_lessons) = 2 AND (SELECT count(*) FROM strategy_lesson_versions) = 3 AND (SELECT count(*) FROM strategy_lesson_types) = 3,
         'the author of org A reads org A''s lessons, versions and types, every status, and nothing else';
  ASSERT public.t_attempt('INSERT INTO strategy_lessons(id,owner_org_id,exam_code,created_by) VALUES (''c5000000-0000-0000-0000-0000000000a1'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''d5000000-0000-0000-0000-000000000001'')') = 'rows:1', 'inserts a lesson';
  ASSERT public.t_attempt('INSERT INTO strategy_lesson_versions(id,lesson_id,owner_org_id,exam_code,version_no,title,blocks,created_by) VALUES (''f5000000-0000-0000-0000-0000000000a1'',''c5000000-0000-0000-0000-0000000000a1'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',1,''n'',''[]'',''d5000000-0000-0000-0000-000000000001'')') = 'rows:1', 'and its first draft';
  ASSERT public.t_attempt('INSERT INTO strategy_lesson_types(version_id,type_code,owner_org_id,exam_code) VALUES (''f5000000-0000-0000-0000-0000000000a1'',''IELTS_R_SHORT_ANSWER'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') = 'rows:1', 'and its type';
  ASSERT public.t_attempt('INSERT INTO strategy_lessons(owner_org_id,exam_code,created_by) VALUES (''bbbbbbbb-0000-0000-0000-00000000000b'',''IELTS'',''d5000000-0000-0000-0000-000000000001'')') = 'err:42501', 'not into another organisation';
  ASSERT public.t_attempt('INSERT INTO strategy_lessons(owner_org_id,exam_code,created_by) VALUES (NULL,''IELTS'',''d5000000-0000-0000-0000-000000000001'')') = 'err:42501', 'not into English9x from an organisation context';
  ASSERT public.t_attempt('INSERT INTO strategy_lessons(owner_org_id,exam_code,created_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''TOEIC'',''d5000000-0000-0000-0000-000000000001'')') = 'err:42501', 'not for an exam the person is no author of';
  ASSERT public.t_attempt('INSERT INTO strategy_lessons(owner_org_id,exam_code,created_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''d5000000-0000-0000-0000-000000000008'')') = 'err:42501', 'not as somebody else';
  ASSERT public.t_refused('INSERT INTO strategy_lesson_versions(lesson_id,owner_org_id,exam_code,version_no,status,title,blocks,created_by) VALUES (''c5000000-0000-0000-0000-000000000002'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',2,''PUBLISHED'',''x'',''[]'',''d5000000-0000-0000-0000-000000000001'')'), 'a version cannot be inserted PUBLISHED';
  ASSERT public.t_attempt('UPDATE strategy_lesson_versions SET title = ''edited'' WHERE id = ''f5000000-0000-0000-0000-000000000012''') = 'rows:1', 'edits their own draft';
  ASSERT public.t_refused('UPDATE strategy_lesson_versions SET title = ''x'' WHERE id = ''f5000000-0000-0000-0000-000000000011'''), 'not the published version';
  ASSERT public.t_refused('UPDATE strategy_lesson_versions SET title = ''x'' WHERE id = ''f5000000-0000-0000-0000-000000000021'''), 'not one under review';
  ASSERT public.t_refused('UPDATE strategy_lesson_versions SET status = ''PUBLISHED'' WHERE id = ''f5000000-0000-0000-0000-000000000012'''), 'cannot publish their own draft';
  ASSERT public.t_attempt('UPDATE strategy_lesson_versions SET owner_org_id = NULL WHERE id = ''f5000000-0000-0000-0000-000000000012''') = 'err:42501', 'identity columns have no UPDATE privilege';
  ASSERT public.t_attempt('DELETE FROM strategy_lesson_versions') = 'err:42501', 'no DELETE';
  -- the types of the own draft change; those of a published or reviewed version do not
  ASSERT public.t_attempt('DELETE FROM strategy_lesson_types WHERE version_id = ''f5000000-0000-0000-0000-000000000012''') = 'rows:1', 'draft: types removed';
  ASSERT public.t_attempt('INSERT INTO strategy_lesson_types(version_id,type_code,owner_org_id,exam_code) VALUES (''f5000000-0000-0000-0000-000000000012'',''IELTS_R_SHORT_ANSWER'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') = 'rows:1', 'draft: type added';
  ASSERT public.t_refused('INSERT INTO strategy_lesson_types(version_id,type_code,owner_org_id,exam_code) VALUES (''f5000000-0000-0000-0000-000000000012'',''IELTS_W_TASK2'',NULL,''IELTS'')'), 'the owner copy must be the parent''s (guard) and the context''s (policy)';
  ASSERT public.t_attempt('DELETE FROM strategy_lesson_types WHERE version_id = ''f5000000-0000-0000-0000-000000000011''') = 'err:23000', 'published: types cannot be removed';
  ASSERT public.t_attempt('INSERT INTO strategy_lesson_types(version_id,type_code,owner_org_id,exam_code) VALUES (''f5000000-0000-0000-0000-000000000011'',''IELTS_W_TASK2'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') = 'err:23000', 'published: type cannot be added';
  ASSERT public.t_attempt('DELETE FROM strategy_lesson_types WHERE version_id = ''f5000000-0000-0000-0000-000000000021''') = 'err:23000', 'in review: types cannot be removed';
  ASSERT public.t_attempt('UPDATE strategy_lesson_types SET type_code = ''IELTS_W_TASK2''') = 'err:42501', 'types have no UPDATE privilege';
  ASSERT public.t_attempt('UPDATE strategy_lesson_versions SET status = ''IN_REVIEW'' WHERE id = ''f5000000-0000-0000-0000-000000000012''') = 'rows:1', 'submits their draft';
  ASSERT public.t_attempt('INSERT INTO strategy_lesson_types(version_id,type_code,owner_org_id,exam_code) VALUES (''f5000000-0000-0000-0000-000000000012'',''IELTS_W_TASK2'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') = 'err:23000', 'and the types are frozen once submitted';
END $$;

-- ---- a colleague AUTHOR of the same exam reads, does not edit
SELECT set_config('app.user_id','d5000000-0000-0000-0000-000000000008',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM strategy_lesson_versions) = 4, 'the colleague reads the versions';
  ASSERT public.t_attempt('UPDATE strategy_lesson_versions SET title = ''mine'' WHERE id = ''f5000000-0000-0000-0000-000000000021''') = 'rows:0', 'but cannot edit another author''s version';
  ASSERT public.t_attempt('INSERT INTO strategy_lesson_versions(lesson_id,owner_org_id,exam_code,version_no,title,blocks,created_by) VALUES (''c5000000-0000-0000-0000-000000000001'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',9,''x'',''[]'',''d5000000-0000-0000-0000-000000000008'')') = 'err:23505', 'nor open a rival draft';
  ASSERT public.t_attempt('INSERT INTO strategy_lesson_types(version_id,type_code,owner_org_id,exam_code) VALUES (''f5000000-0000-0000-0000-0000000000a1'',''IELTS_W_TASK2'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') = 'err:42501', 'nor add types to a colleague''s draft';
END $$;

-- ---- the REVIEWER of the exam: reads, locks, decides through an open review; edits nothing
SELECT set_config('app.user_id','d5000000-0000-0000-0000-000000000002',false);
DO $$
DECLARE locked int;
BEGIN
  SELECT count(*) INTO locked FROM (SELECT id FROM strategy_lesson_versions WHERE id = 'f5000000-0000-0000-0000-000000000021' FOR UPDATE) l;
  ASSERT locked = 1, 'locks the version under review';
  SELECT count(*) INTO locked FROM (SELECT id FROM strategy_lesson_versions WHERE id = 'f5000000-0000-0000-0000-000000000011' FOR SHARE) l;
  ASSERT locked = 1, 'locks a published version for share';
  ASSERT public.t_attempt('INSERT INTO strategy_lessons(owner_org_id,exam_code,created_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''d5000000-0000-0000-0000-000000000002'')') = 'err:42501', 'a reviewer who is no author writes nothing';
  ASSERT public.t_attempt('UPDATE strategy_lesson_versions SET title = ''x'' WHERE id = ''f5000000-0000-0000-0000-000000000021''') = 'err:23000', 'content under review is frozen even for the reviewer';
  ASSERT public.t_attempt('UPDATE strategy_lesson_versions SET status = ''PUBLISHED'' WHERE id = ''f5000000-0000-0000-0000-000000000012''') = 'err:23000', 'no decision without an open review';
  ASSERT public.t_attempt('UPDATE strategy_lesson_versions SET status = ''PUBLISHED'' WHERE id = ''f5000000-0000-0000-0000-000000000021''') = 'rows:1', 'decides with its open review';
  ASSERT public.t_attempt('UPDATE strategy_lesson_versions SET status = ''ARCHIVED'' WHERE id = ''f5000000-0000-0000-0000-000000000011''') = 'rows:1', 'archives a published version';
END $$;

-- ---- every other role: nothing at all
DO $$
DECLARE who text;
BEGIN
  FOREACH who IN ARRAY ARRAY['d5000000-0000-0000-0000-000000000003', 'd5000000-0000-0000-0000-000000000006', 'd5000000-0000-0000-0000-000000000007',
                             'd5000000-0000-0000-0000-000000000009'] LOOP
    PERFORM set_config('app.user_id', who, false);
    ASSERT (SELECT count(*) FROM strategy_lesson_versions) = 0 AND (SELECT count(*) FROM strategy_lessons) = 0 AND (SELECT count(*) FROM strategy_lesson_types) = 0, 'no lesson rows for ' || who;
    ASSERT public.t_attempt('UPDATE strategy_lesson_versions SET status = ''PUBLISHED''') = 'rows:0', 'publishes nothing: ' || who;
    ASSERT public.t_attempt('INSERT INTO strategy_lessons(owner_org_id,exam_code,created_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''' || who || ''')') = 'err:42501', 'inserts nothing: ' || who;
  END LOOP;
END $$;
SELECT set_config('app.user_id','d5000000-0000-0000-0000-000000000001',false), set_config('app.org_id','bbbbbbbb-0000-0000-0000-00000000000b',false);
DO $$ BEGIN ASSERT (SELECT count(*) FROM strategy_lesson_versions) = 0, 'org A''s author in org B''s context reads nothing'; END $$;
SELECT set_config('app.org_id','',false);
DO $$ BEGIN ASSERT (SELECT count(*) FROM strategy_lesson_versions) = 0, 'and in the personal context nothing'; END $$;
SELECT set_config('app.user_id','d5000000-0000-0000-0000-000000000004',false), set_config('app.org_id','bbbbbbbb-0000-0000-0000-00000000000b',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(id) FROM strategy_lesson_versions) = ARRAY['f5000000-0000-0000-0000-000000000031'::uuid], 'org B''s author sees only B''s version';
  ASSERT public.t_attempt('UPDATE strategy_lesson_versions SET title = ''x'' WHERE id = ''f5000000-0000-0000-0000-0000000000a1''') = 'rows:0', 'and edits none of A''s';
END $$;
SELECT set_config('app.user_id','d5000000-0000-0000-0000-000000000005',false), set_config('app.org_id','',false), set_config('app.e9x_staff','on',false);
DO $$ BEGIN ASSERT (SELECT array_agg(id) FROM strategy_lesson_versions) = ARRAY['f5000000-0000-0000-0000-000000000041'::uuid], 'English9x''s author sees English9x''s version'; END $$;

-- ---- the learner's way in, and the status the workflow reads
SELECT set_config('app.user_id','d5000000-0000-0000-0000-000000000003',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false), set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM strategy_lesson_versions) = 0, 'the learner selects no version row';
  ASSERT (SELECT title FROM e9x_strategy_lesson_exam_view('f5000000-0000-0000-0000-000000000011')) = 'a1', 'but reads an archived lesson of the organisation (an attempt keeps its version)';
  ASSERT (SELECT question_type_codes FROM e9x_strategy_lesson_exam_view('f5000000-0000-0000-0000-000000000011')) = ARRAY['IELTS_R_SHORT_ANSWER'], 'with its types';
  ASSERT (SELECT count(*) FROM e9x_strategy_lesson_exam_view('f5000000-0000-0000-0000-000000000041')) = 1, 'and a published English9x one';
  ASSERT (SELECT count(*) FROM e9x_strategy_lesson_exam_view('f5000000-0000-0000-0000-000000000012')) = 0, 'but not a draft';
  ASSERT (SELECT count(*) FROM e9x_strategy_lesson_exam_view('f5000000-0000-0000-0000-000000000031')) = 0, 'nor another organisation''s';
  ASSERT (SELECT status FROM e9x_strategy_lesson_version_status('f5000000-0000-0000-0000-000000000041')) = 'PUBLISHED', 'the workflow sees English9x''s status';
  ASSERT (SELECT count(*) FROM e9x_strategy_lesson_version_status('f5000000-0000-0000-0000-000000000031')) = 0, 'not another organisation''s';
END $$;
RESET ROLE;
