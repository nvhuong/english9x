-- P2.3 questions, question groups and their versions (features F09, F18).
--  * Reading: the AUTHOR / REVIEWER of THAT exam, in the row's own context, every status, answer key included. Nobody else.
--  * Writing: an AUTHOR inserts a DRAFT as themselves and edits only a version they created (the ledger-141 default: no co-editing); a REVIEWER
--    locks IN_REVIEW and PUBLISHED rows and moves IN_REVIEW to PUBLISHED / CHANGES_REQUESTED; nothing else changes a published version.
--  * The review of P1 final found a TEACHER setting a centre's PENDING draft to PUBLISHED and a LEARNER inserting a PUBLISHED organisation
--    question: both are refused below, by role and by exam.
--  * A child of a version (question_group_items) has its own guard: nothing is added, changed or removed unless the parent is DRAFT / CHANGES_REQUESTED.
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS'),('TOEIC','TOEIC') ON CONFLICT DO NOTHING;
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','Center B','CENTER');
INSERT INTO users(id,email) VALUES
 ('d4000000-0000-0000-0000-000000000001','author-a-ielts@x.test'),
 ('d4000000-0000-0000-0000-000000000002','reviewer-a-ielts@x.test'),
 ('d4000000-0000-0000-0000-000000000003','learner-a@x.test'),
 ('d4000000-0000-0000-0000-000000000004','author-b-ielts@x.test'),
 ('d4000000-0000-0000-0000-000000000005','author-e9-ielts@x.test'),
 ('d4000000-0000-0000-0000-000000000006','teacher-a-toeic@x.test'),
 ('d4000000-0000-0000-0000-000000000007','author-a-toeic@x.test'),
 ('d4000000-0000-0000-0000-000000000008','coauthor-a-ielts@x.test'),
 ('d4000000-0000-0000-0000-000000000009','orgadmin-a@x.test'),
 ('d4000000-0000-0000-0000-00000000000a','sysadmin@x.test'),
 ('d4000000-0000-0000-0000-00000000000b','author-reviewer-a-ielts@x.test'),
 ('d4000000-0000-0000-0000-00000000000c','reviewer-e9-ielts@x.test');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','d4000000-0000-0000-0000-000000000001'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','d4000000-0000-0000-0000-000000000002'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','d4000000-0000-0000-0000-000000000003'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','d4000000-0000-0000-0000-000000000006'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','d4000000-0000-0000-0000-000000000007'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','d4000000-0000-0000-0000-000000000008'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','d4000000-0000-0000-0000-000000000009'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','d4000000-0000-0000-0000-00000000000b'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','d4000000-0000-0000-0000-000000000004');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('d4000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','AUTHOR'),
 ('d4000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','REVIEWER'),
 ('d4000000-0000-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'LEARNER'),
 ('d4000000-0000-0000-0000-000000000004','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','AUTHOR'),
 ('d4000000-0000-0000-0000-000000000005',NULL,'IELTS','AUTHOR'),
 ('d4000000-0000-0000-0000-000000000006','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC','TEACHER'),
 ('d4000000-0000-0000-0000-000000000007','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC','AUTHOR'),
 ('d4000000-0000-0000-0000-000000000008','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','AUTHOR'),
 ('d4000000-0000-0000-0000-000000000009','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN'),
 ('d4000000-0000-0000-0000-00000000000a',NULL,NULL,'SYS_ADMIN'),
 ('d4000000-0000-0000-0000-00000000000b','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','AUTHOR'),
 ('d4000000-0000-0000-0000-00000000000b','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','REVIEWER'),
 ('d4000000-0000-0000-0000-00000000000c',NULL,'IELTS','REVIEWER');

-- fixtures as the superuser (any status may be inserted). Questions: A has q1 (v1 PUBLISHED + v2 DRAFT by the author), q2 (v1 IN_REVIEW);
-- B has q3 (v1 PUBLISHED); English9x has q4 (v1 PUBLISHED).
INSERT INTO questions(id,owner_org_id,exam_code,type_code,created_by) VALUES
 ('c4000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','IELTS_R_SHORT_ANSWER','d4000000-0000-0000-0000-000000000001'),
 ('c4000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','IELTS_R_SHORT_ANSWER','d4000000-0000-0000-0000-000000000001'),
 ('c4000000-0000-0000-0000-000000000003','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','IELTS_R_SHORT_ANSWER','d4000000-0000-0000-0000-000000000004'),
 ('c4000000-0000-0000-0000-000000000004',NULL,'IELTS','IELTS_R_SHORT_ANSWER','d4000000-0000-0000-0000-000000000005');
INSERT INTO question_versions(id,question_id,owner_org_id,exam_code,type_code,version_no,status,content,answer_key,created_by) VALUES
 ('f4000000-0000-0000-0000-000000000011','c4000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','IELTS_R_SHORT_ANSWER',1,'PUBLISHED','{"stem":"a1"}','{"slots":[["K"]]}','d4000000-0000-0000-0000-000000000001'),
 ('f4000000-0000-0000-0000-000000000012','c4000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','IELTS_R_SHORT_ANSWER',2,'DRAFT','{"stem":"a2"}','{"slots":[["K"]]}','d4000000-0000-0000-0000-000000000001'),
 ('f4000000-0000-0000-0000-000000000021','c4000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','IELTS_R_SHORT_ANSWER',1,'IN_REVIEW','{"stem":"r1"}','{"slots":[["K"]]}','d4000000-0000-0000-0000-000000000001'),
 ('f4000000-0000-0000-0000-000000000031','c4000000-0000-0000-0000-000000000003','bbbbbbbb-0000-0000-0000-00000000000b','IELTS','IELTS_R_SHORT_ANSWER',1,'PUBLISHED','{"stem":"b1"}','{"slots":[["K"]]}','d4000000-0000-0000-0000-000000000004'),
 ('f4000000-0000-0000-0000-000000000041','c4000000-0000-0000-0000-000000000004',NULL,'IELTS','IELTS_R_SHORT_ANSWER',1,'PUBLISHED','{"stem":"e1"}','{"slots":[["K"]]}','d4000000-0000-0000-0000-000000000005');

-- the open review of q2's version (what the workflow service has when it decides): the decision trigger asks for it
INSERT INTO review_requests(content_type,version_id,owner_org_id,exam_code,author_id,submitted_by) VALUES
 ('question','f4000000-0000-0000-0000-000000000021','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','d4000000-0000-0000-0000-000000000001','d4000000-0000-0000-0000-000000000001');

CREATE FUNCTION public.t_attempt(stmt text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  EXECUTE stmt; GET DIAGNOSTICS n = ROW_COUNT;
  RETURN 'rows:' || n;
EXCEPTION WHEN OTHERS THEN RETURN 'err:' || SQLSTATE;
END $$;
GRANT EXECUTE ON FUNCTION public.t_attempt(text) TO e9x_app, e9x_grader;
-- "refused": no row changed, or a row-level-security / privilege error, or the lifecycle guard (a BEFORE trigger, which PostgreSQL runs before the
-- WITH CHECK of the policies, so the guard may answer first)
CREATE FUNCTION public.t_refused(stmt text) RETURNS boolean LANGUAGE sql AS $$ SELECT public.t_attempt(stmt) IN ('rows:0', 'err:42501', 'err:23000') $$;
GRANT EXECUTE ON FUNCTION public.t_refused(text) TO e9x_app, e9x_grader;

-- the tables
DO $$
BEGIN
  ASSERT (SELECT bool_and(relrowsecurity) FROM pg_class WHERE relname IN ('questions','question_groups','question_versions','question_group_versions','question_group_items')),
         'every question table has row level security';
  ASSERT (SELECT count(*) FROM pg_trigger WHERE tgname IN ('question_versions_lifecycle','question_group_versions_lifecycle')) = 2, 'the version tables attach e9x_guard_version_lifecycle';
  ASSERT public.t_attempt('INSERT INTO question_versions(question_id,owner_org_id,exam_code,type_code,version_no,content,answer_key,created_by) VALUES (''c4000000-0000-0000-0000-000000000004'',NULL,''IELTS'',''IELTS_R_SHORT_ANSWER'',9,''[]'',''{}'',''d4000000-0000-0000-0000-000000000005'')') = 'err:23514', 'content is an object';
  -- the type belongs to the exam of the header: a TOEIC type under an IELTS question, and a version whose type differs from its header
  ASSERT public.t_attempt('INSERT INTO questions(owner_org_id,exam_code,type_code,created_by) VALUES (NULL,''IELTS'',''TOEIC_P5_INCOMPLETE_SENTENCE'',''d4000000-0000-0000-0000-000000000005'')') = 'err:23503', 'a type of another exam';
  ASSERT public.t_attempt('INSERT INTO question_versions(question_id,owner_org_id,exam_code,type_code,version_no,content,answer_key,created_by) VALUES (''c4000000-0000-0000-0000-000000000004'',NULL,''IELTS'',''IELTS_W_TASK2'',9,''{}'',''{}'',''d4000000-0000-0000-0000-000000000005'')') = 'err:23503', 'a version carries its header''s type';
  -- one open version per question: q1 already has a draft
  ASSERT public.t_attempt('INSERT INTO question_versions(question_id,owner_org_id,exam_code,type_code,version_no,content,answer_key,created_by) VALUES (''c4000000-0000-0000-0000-000000000001'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''IELTS_R_SHORT_ANSWER'',3,''{}'',''{}'',''d4000000-0000-0000-0000-000000000001'')') = 'err:23505', 'one open version per question';
END $$;

-- ---- the author of the exam, in the owner's context -------------------------------------------------------------------------------
SET ROLE e9x_app;
SELECT set_config('app.user_id','d4000000-0000-0000-0000-000000000001',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(id ORDER BY id) FROM questions) = ARRAY['c4000000-0000-0000-0000-000000000001'::uuid,'c4000000-0000-0000-0000-000000000002'::uuid],
         'the author of org A sees org A''s questions and nothing else (not B''s, not English9x''s)';
  ASSERT (SELECT count(*) FROM question_versions) = 3, 'and their versions, every status';
  ASSERT (SELECT answer_key->'slots'->0->>0 FROM question_versions WHERE id = 'f4000000-0000-0000-0000-000000000012') = 'K', 'with the answer key';
  -- writes: header + draft version as themselves
  ASSERT public.t_attempt('INSERT INTO questions(id,owner_org_id,exam_code,type_code,created_by) VALUES (''c4000000-0000-0000-0000-0000000000a1'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''IELTS_R_SHORT_ANSWER'',''d4000000-0000-0000-0000-000000000001'')') = 'rows:1', 'the author inserts a question';
  ASSERT public.t_attempt('INSERT INTO question_versions(question_id,owner_org_id,exam_code,type_code,version_no,content,answer_key,created_by) VALUES (''c4000000-0000-0000-0000-0000000000a1'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''IELTS_R_SHORT_ANSWER'',1,''{"stem":"n"}'',''{"slots":[["K"]]}'',''d4000000-0000-0000-0000-000000000001'')') = 'rows:1', 'and its first draft';
  ASSERT public.t_attempt('INSERT INTO questions(owner_org_id,exam_code,type_code,created_by) VALUES (''bbbbbbbb-0000-0000-0000-00000000000b'',''IELTS'',''IELTS_R_SHORT_ANSWER'',''d4000000-0000-0000-0000-000000000001'')') = 'err:42501', 'not into another organisation';
  ASSERT public.t_attempt('INSERT INTO questions(owner_org_id,exam_code,type_code,created_by) VALUES (NULL,''IELTS'',''IELTS_R_SHORT_ANSWER'',''d4000000-0000-0000-0000-000000000001'')') = 'err:42501', 'not into English9x from an organisation context';
  ASSERT public.t_attempt('INSERT INTO questions(owner_org_id,exam_code,type_code,created_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''TOEIC'',''TOEIC_P5_INCOMPLETE_SENTENCE'',''d4000000-0000-0000-0000-000000000001'')') = 'err:42501', 'not for an exam the person is no author of';
  ASSERT public.t_attempt('INSERT INTO questions(owner_org_id,exam_code,type_code,created_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''IELTS_R_SHORT_ANSWER'',''d4000000-0000-0000-0000-000000000008'')') = 'err:42501', 'not as somebody else';
  ASSERT public.t_refused('INSERT INTO question_versions(question_id,owner_org_id,exam_code,type_code,version_no,status,content,answer_key,created_by) VALUES (''c4000000-0000-0000-0000-000000000002'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''IELTS_R_SHORT_ANSWER'',2,''PUBLISHED'',''{}'',''{}'',''d4000000-0000-0000-0000-000000000001'')'), 'a version cannot be inserted already PUBLISHED';
  -- editing: the own draft yes; the published version never; no DELETE of anything
  ASSERT public.t_attempt('UPDATE question_versions SET content = ''{"stem":"edited"}'' WHERE id = ''f4000000-0000-0000-0000-000000000012''') = 'rows:1', 'the author edits their own draft';
  ASSERT public.t_refused('UPDATE question_versions SET content = ''{"stem":"x"}'' WHERE id = ''f4000000-0000-0000-0000-000000000011'''), 'but not the published version';
  ASSERT public.t_refused('UPDATE question_versions SET content = ''{"stem":"x"}'' WHERE id = ''f4000000-0000-0000-0000-000000000021'''), 'nor one under review';
  ASSERT public.t_refused('UPDATE question_versions SET status = ''PUBLISHED'' WHERE id = ''f4000000-0000-0000-0000-000000000012'''), 'the author cannot publish their own draft';
  ASSERT public.t_attempt('UPDATE question_versions SET answer_key = ''{}'', owner_org_id = NULL WHERE id = ''f4000000-0000-0000-0000-000000000012''') = 'err:42501', 'identity columns have no UPDATE privilege';
  ASSERT public.t_attempt('DELETE FROM question_versions') = 'err:42501', 'no DELETE';
  ASSERT public.t_attempt('UPDATE questions SET exam_code = ''TOEIC''') = 'err:42501', 'a header never changes';
  ASSERT public.t_attempt('UPDATE question_versions SET status = ''IN_REVIEW'' WHERE id = ''f4000000-0000-0000-0000-000000000012''') = 'rows:1', 'the author submits their draft (the workflow service does it, the policy allows it)';
  ASSERT public.t_attempt('UPDATE question_versions SET content = ''{"stem":"after submit"}'' WHERE id = ''f4000000-0000-0000-0000-000000000012''') = 'rows:0', 'and cannot touch it afterwards';
END $$;

-- ---- a colleague AUTHOR of the same exam does not edit, submit or shadow the draft of the creator (ledger 141 default) ------------------
SELECT set_config('app.user_id','d4000000-0000-0000-0000-000000000008',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM question_versions) = 4, 'the colleague reads the versions (an author of the exam)';
  ASSERT public.t_attempt('UPDATE question_versions SET content = ''{"stem":"mine now"}'' WHERE id = ''f4000000-0000-0000-0000-000000000021''') = 'rows:0', 'but cannot edit another author''s version';
  ASSERT public.t_attempt('INSERT INTO question_versions(question_id,owner_org_id,exam_code,type_code,version_no,content,answer_key,created_by) VALUES (''c4000000-0000-0000-0000-000000000001'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''IELTS_R_SHORT_ANSWER'',9,''{}'',''{}'',''d4000000-0000-0000-0000-000000000008'')') = 'err:23505', 'nor open a rival version while one is open';
  ASSERT public.t_refused('UPDATE question_versions SET status = ''IN_REVIEW'' WHERE id = ''f4000000-0000-0000-0000-000000000011'''), 'nor submit anybody''s version';
END $$;

-- ---- the REVIEWER of the exam: reads, locks, decides; edits nothing ---------------------------------------------------------------
SELECT set_config('app.user_id','d4000000-0000-0000-0000-000000000002',false);
DO $$
DECLARE locked int;
BEGIN
  ASSERT (SELECT answer_key->'slots'->0->>0 FROM question_versions WHERE id = 'f4000000-0000-0000-0000-000000000021') = 'K', 'the reviewer reads the key under review';
  SELECT count(*) INTO locked FROM (SELECT id FROM question_versions WHERE id = 'f4000000-0000-0000-0000-000000000021' FOR UPDATE) l;
  ASSERT locked = 1, 'and locks the version (SELECT ... FOR UPDATE, the ReviewableType contract)';
  SELECT count(*) INTO locked FROM (SELECT id FROM question_versions WHERE id = 'f4000000-0000-0000-0000-000000000011' FOR SHARE) l;
  ASSERT locked = 1, 'locks a published version for share (a dependency)';
  ASSERT public.t_attempt('INSERT INTO questions(owner_org_id,exam_code,type_code,created_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''IELTS_R_SHORT_ANSWER'',''d4000000-0000-0000-0000-000000000002'')') = 'err:42501', 'a reviewer who is no author writes nothing';
  ASSERT public.t_attempt('UPDATE question_versions SET content = ''{"stem":"x"}'' WHERE id = ''f4000000-0000-0000-0000-000000000021''') = 'err:23000' ,'review: content of a version under review is frozen even for the reviewer';
  ASSERT public.t_attempt('UPDATE question_versions SET content = ''{"stem":"x"}'' WHERE id = ''f4000000-0000-0000-0000-000000000011''') = 'err:23000', 'and so is a published one';
  ASSERT public.t_refused('UPDATE question_versions SET status = ''DRAFT'' WHERE id = ''f4000000-0000-0000-0000-000000000021'''), 'cannot send it back to draft';
  -- the draft the author submitted above has no review request behind it: a plain UPDATE does not publish it, whoever the reviewer is
  ASSERT public.t_attempt('UPDATE question_versions SET status = ''PUBLISHED'' WHERE id = ''f4000000-0000-0000-0000-000000000012''') = 'err:23000', 'no decision without an open review';
  ASSERT public.t_attempt('UPDATE question_versions SET status = ''CHANGES_REQUESTED'' WHERE id = ''f4000000-0000-0000-0000-000000000012''') = 'err:23000', 'nor a return to the author';
  ASSERT public.t_attempt('UPDATE question_versions SET status = ''PUBLISHED'' WHERE id = ''f4000000-0000-0000-0000-000000000021''') = 'rows:1', 'decides: IN_REVIEW to PUBLISHED, with its open review';
  ASSERT public.t_attempt('UPDATE question_versions SET status = ''ARCHIVED'' WHERE id = ''f4000000-0000-0000-0000-000000000011''') = 'rows:1', 'archives a published version';
END $$;

-- ---- every other role, and the author in the wrong place: nothing at all -----------------------------------------------------------
DO $$
DECLARE who text;
BEGIN
  FOREACH who IN ARRAY ARRAY['d4000000-0000-0000-0000-000000000003', 'd4000000-0000-0000-0000-000000000006', 'd4000000-0000-0000-0000-000000000007',
                             'd4000000-0000-0000-0000-000000000009'] LOOP
    PERFORM set_config('app.user_id', who, false);
    ASSERT (SELECT count(*) FROM question_versions) = 0, 'no version rows for ' || who;
    ASSERT (SELECT count(*) FROM questions) = 0, 'no headers for ' || who;
    ASSERT (SELECT count(*) FROM question_groups) = 0, 'no groups for ' || who;
    ASSERT public.t_attempt('UPDATE question_versions SET status = ''PUBLISHED''') = 'rows:0', 'publishes nothing: ' || who;
    ASSERT public.t_attempt('INSERT INTO questions(owner_org_id,exam_code,type_code,created_by) VALUES (''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''IELTS_R_SHORT_ANSWER'',''' || who || ''')') = 'err:42501', 'inserts nothing for IELTS: ' || who;
    ASSERT public.t_refused('INSERT INTO question_versions(question_id,owner_org_id,exam_code,type_code,version_no,status,content,answer_key,created_by) VALUES (''c4000000-0000-0000-0000-000000000001'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''IELTS_R_SHORT_ANSWER'',7,''PUBLISHED'',''{}'',''{}'',''' || who || ''')'), 'a PUBLISHED version cannot be inserted by ' || who;
  END LOOP;
END $$;
-- the author of IELTS in another organisation's context, and the same person in the personal context
SELECT set_config('app.user_id','d4000000-0000-0000-0000-000000000001',false), set_config('app.org_id','bbbbbbbb-0000-0000-0000-00000000000b',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM question_versions) = 0, 'the context counts: org A''s author in org B''s context reads nothing';
END $$;
SELECT set_config('app.org_id','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM question_versions) = 0, 'and in the personal context reads nothing';
END $$;
-- org B's author: B only
SELECT set_config('app.user_id','d4000000-0000-0000-0000-000000000004',false), set_config('app.org_id','bbbbbbbb-0000-0000-0000-00000000000b',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(id) FROM question_versions) = ARRAY['f4000000-0000-0000-0000-000000000031'::uuid], 'org B''s author sees only B''s version';
  ASSERT public.t_attempt('UPDATE question_versions SET content = ''{}'' WHERE id = ''f4000000-0000-0000-0000-000000000012''') = 'rows:0', 'and edits none of A''s';
END $$;
-- English9x: its author in the English9x context only
SELECT set_config('app.user_id','d4000000-0000-0000-0000-000000000005',false), set_config('app.org_id','',false), set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(id) FROM question_versions) = ARRAY['f4000000-0000-0000-0000-000000000041'::uuid], 'English9x''s author sees English9x''s version';
END $$;
SELECT set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM question_versions WHERE owner_org_id IS NULL) = 0, 'English9x rows are not read in an organisation''s context';
END $$;
-- a SYS_ADMIN with no AUTHOR / REVIEWER grant reads no key
SELECT set_config('app.user_id','d4000000-0000-0000-0000-00000000000a',false), set_config('app.org_id','',false), set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM question_versions) = 0, 'a SYS_ADMIN is no author or reviewer: no answer key';
END $$;

-- ---- the workflow's dependency status of content of another context ----------------------------------------------------------------
SELECT set_config('app.user_id','d4000000-0000-0000-0000-000000000001',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false), set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT (SELECT status FROM e9x_question_version_status('f4000000-0000-0000-0000-000000000041')) = 'PUBLISHED', 'an organisation sees that English9x''s version is PUBLISHED';
  ASSERT (SELECT count(*) FROM e9x_question_version_status('f4000000-0000-0000-0000-000000000031')) = 0, 'but not that of another organisation';
  ASSERT (SELECT count(*) FROM e9x_question_version_status('f4000000-0000-0000-0000-000000000012')) = 0, 'and not of a draft';
END $$;
RESET ROLE;

-- ---- the child table of a group version has its own guard -----------------------------------------------------------------------------
INSERT INTO question_groups(id,owner_org_id,exam_code,created_by) VALUES
 ('90000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','d4000000-0000-0000-0000-000000000001');
INSERT INTO question_group_versions(id,group_id,owner_org_id,exam_code,version_no,status,content,created_by) VALUES
 ('91000000-0000-0000-0000-000000000001','90000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a','IELTS',1,'DRAFT','{"title":"g"}','d4000000-0000-0000-0000-000000000001');
INSERT INTO question_group_items(group_version_id,position,question_version_id,owner_org_id,exam_code) VALUES
 ('91000000-0000-0000-0000-000000000001',1,'f4000000-0000-0000-0000-000000000021','aaaaaaaa-0000-0000-0000-00000000000a','IELTS');
DO $$
BEGIN
  -- a DRAFT group version: members can be added, changed, removed (superuser: the guard applies to everybody)
  ASSERT public.t_attempt('INSERT INTO question_group_items(group_version_id,position,question_version_id,owner_org_id,exam_code) VALUES (''91000000-0000-0000-0000-000000000001'',2,''f4000000-0000-0000-0000-000000000012'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') = 'rows:1', 'draft: add';
  ASSERT public.t_attempt('UPDATE question_group_items SET position = 3 WHERE position = 2') = 'rows:1', 'draft: change';
  ASSERT public.t_attempt('DELETE FROM question_group_items WHERE position = 3') = 'rows:1', 'draft: remove';
  -- the member carries the owner and exam of the group version, and is of the group''s owner
  ASSERT public.t_attempt('INSERT INTO question_group_items(group_version_id,position,question_version_id,owner_org_id,exam_code) VALUES (''91000000-0000-0000-0000-000000000001'',2,''f4000000-0000-0000-0000-000000000031'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') = 'err:23000', 'a question of another owner is no member';
  ASSERT public.t_attempt('INSERT INTO question_group_items(group_version_id,position,question_version_id,owner_org_id,exam_code) VALUES (''91000000-0000-0000-0000-000000000001'',2,''f4000000-0000-0000-0000-000000000012'',NULL,''IELTS'')') = 'err:23000', 'the owner copy must match the parent';
  UPDATE question_group_versions SET status = 'IN_REVIEW' WHERE id = '91000000-0000-0000-0000-000000000001';
  ASSERT public.t_attempt('INSERT INTO question_group_items(group_version_id,position,question_version_id,owner_org_id,exam_code) VALUES (''91000000-0000-0000-0000-000000000001'',2,''f4000000-0000-0000-0000-000000000012'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') = 'err:23000', 'IN_REVIEW: add refused';
  ASSERT public.t_attempt('UPDATE question_group_items SET position = 5') = 'err:23000', 'IN_REVIEW: change refused';
  ASSERT public.t_attempt('DELETE FROM question_group_items') = 'err:23000', 'IN_REVIEW: remove refused';
  UPDATE question_group_versions SET status = 'PUBLISHED' WHERE id = '91000000-0000-0000-0000-000000000001';
  ASSERT public.t_attempt('INSERT INTO question_group_items(group_version_id,position,question_version_id,owner_org_id,exam_code) VALUES (''91000000-0000-0000-0000-000000000001'',2,''f4000000-0000-0000-0000-000000000012'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') = 'err:23000', 'PUBLISHED: add refused';
  ASSERT public.t_attempt('UPDATE question_group_items SET position = 5') = 'err:23000', 'PUBLISHED: change refused';
  ASSERT public.t_attempt('DELETE FROM question_group_items') = 'err:23000', 'PUBLISHED: remove refused';
  ASSERT (SELECT count(*) FROM question_group_items) = 1, 'the published group kept its member';
  -- CHANGES_REQUESTED is editable again
  UPDATE question_group_versions SET status = 'ARCHIVED' WHERE id = '91000000-0000-0000-0000-000000000001';
  ASSERT public.t_attempt('DELETE FROM question_group_items') = 'err:23000', 'ARCHIVED: remove refused';
END $$;
INSERT INTO question_group_versions(id,group_id,owner_org_id,exam_code,version_no,status,content,created_by) VALUES
 ('91000000-0000-0000-0000-000000000002','90000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a','IELTS',2,'DRAFT','{"title":"g2"}','d4000000-0000-0000-0000-000000000001');
INSERT INTO question_group_items(group_version_id,position,question_version_id,owner_org_id,exam_code) VALUES
 ('91000000-0000-0000-0000-000000000002',1,'f4000000-0000-0000-0000-000000000021','aaaaaaaa-0000-0000-0000-00000000000a','IELTS');
UPDATE question_group_versions SET status = 'IN_REVIEW' WHERE id = '91000000-0000-0000-0000-000000000002';
UPDATE question_group_versions SET status = 'CHANGES_REQUESTED' WHERE id = '91000000-0000-0000-0000-000000000002';
DO $$
BEGIN
  ASSERT public.t_attempt('DELETE FROM question_group_items WHERE group_version_id = ''91000000-0000-0000-0000-000000000002''') = 'rows:1', 'CHANGES_REQUESTED: editable again';
END $$;

-- the author edits the members of their own draft group version through the policies, never another author's and never a published one's
INSERT INTO question_group_versions(id,group_id,owner_org_id,exam_code,version_no,status,content,created_by) VALUES
 ('91000000-0000-0000-0000-000000000003','90000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a','IELTS',3,'ARCHIVED','{"title":"old"}','d4000000-0000-0000-0000-000000000008');
INSERT INTO question_groups(id,owner_org_id,exam_code,created_by) VALUES
 ('90000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-00000000000a','IELTS','d4000000-0000-0000-0000-000000000008');
INSERT INTO question_group_versions(id,group_id,owner_org_id,exam_code,version_no,status,content,created_by) VALUES
 ('91000000-0000-0000-0000-000000000004','90000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-00000000000a','IELTS',1,'DRAFT','{"title":"co"}','d4000000-0000-0000-0000-000000000008');
SET ROLE e9x_app;
SELECT set_config('app.user_id','d4000000-0000-0000-0000-000000000001',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT public.t_attempt('INSERT INTO question_group_items(group_version_id,position,question_version_id,owner_org_id,exam_code) VALUES (''91000000-0000-0000-0000-000000000002'',1,''f4000000-0000-0000-0000-000000000021'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') = 'rows:1', 'the creator edits the members of their draft group version';
  ASSERT public.t_attempt('INSERT INTO question_group_items(group_version_id,position,question_version_id,owner_org_id,exam_code) VALUES (''91000000-0000-0000-0000-000000000004'',1,''f4000000-0000-0000-0000-000000000021'',''aaaaaaaa-0000-0000-0000-00000000000a'',''IELTS'')') = 'err:42501', 'but not those of a colleague''s draft';
  ASSERT public.t_attempt('DELETE FROM question_group_items') = 'err:23000', 'a delete that reaches a row of a frozen version is refused by the guard';
  ASSERT public.t_attempt('UPDATE question_group_items SET position = 2') = 'err:42501', 'members have no UPDATE privilege at all';
  ASSERT (SELECT count(*) FROM question_group_versions WHERE solution = '{}') = 4, 'the group versions (solution side included) are read by the author';
END $$;
SELECT set_config('app.user_id','d4000000-0000-0000-0000-000000000003',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM question_group_items) = 0 AND (SELECT count(*) FROM question_group_versions) = 0, 'a learner sees no group member and no group version';
  ASSERT (SELECT count(*) FROM e9x_question_group_exam_view('91000000-0000-0000-0000-000000000004')) = 0, 'a draft group version has no learner view';
  ASSERT (SELECT question_version_ids FROM e9x_question_group_exam_view('91000000-0000-0000-0000-000000000001')) = ARRAY['f4000000-0000-0000-0000-000000000021'::uuid], 'a published (later archived) one lists its members in order, ids only';
END $$;
RESET ROLE;
