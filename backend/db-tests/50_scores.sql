-- P4.1 automatic scoring: scores, attempt_gradings and the grader's reach (V202611130004).
--  * The grader reads the answer keys of PUBLISHED English9x questions and of the attempt's own organisation, and the SUBMITTED attempt of the person AND
--    organisation in its session, and nothing else of anybody: no other learner, no IN_PROGRESS attempt, no draft, no other organisation, no table without a grant.
--  * Only the grader writes a score; the learner reads their own; a score is never changed or deleted.
--  * exam_attempts_select is narrowed to the request role (it was PUBLIC, i.e. open to the grader by organisation).
--  * The helper functions of the policies are executable by the grader, and by nobody else who did not have them.
INSERT INTO exams(code,name) VALUES ('TOEIC','TOEIC') ON CONFLICT DO NOTHING;
INSERT INTO users(id,email) VALUES
 ('f5000000-0000-0000-0000-000000000001','sc-learner1@x.test'),
 ('f5000000-0000-0000-0000-000000000002','sc-learner2@x.test'),
 ('f5000000-0000-0000-0000-000000000003','sc-author@x.test');
INSERT INTO organizations(id,name,type) VALUES
 ('f5aaaaaa-0000-0000-0000-00000000000a','Score Center A','CENTER'),
 ('f5bbbbbb-0000-0000-0000-00000000000b','Score Center B','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('f5aaaaaa-0000-0000-0000-00000000000a','f5000000-0000-0000-0000-000000000001'),
 ('f5aaaaaa-0000-0000-0000-00000000000a','f5000000-0000-0000-0000-000000000002');

CREATE FUNCTION public.t_attempt(stmt text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  EXECUTE stmt; GET DIAGNOSTICS n = ROW_COUNT;
  RETURN 'rows:' || n;
EXCEPTION WHEN OTHERS THEN RETURN 'err:' || SQLSTATE;
END $$;
GRANT EXECUTE ON FUNCTION public.t_attempt(text) TO e9x_app, e9x_grader;

-- Questions: kv1 English9x PUBLISHED, kv2 org A PUBLISHED, kv3 org B PUBLISHED, kv4 org A DRAFT.
INSERT INTO questions(id,owner_org_id,exam_code,type_code,created_by) VALUES
 ('f5100000-0000-0000-0000-000000000001',NULL,'TOEIC','TOEIC_P5_INCOMPLETE_SENTENCE','f5000000-0000-0000-0000-000000000003'),
 ('f5100000-0000-0000-0000-000000000002','f5aaaaaa-0000-0000-0000-00000000000a','TOEIC','TOEIC_P5_INCOMPLETE_SENTENCE','f5000000-0000-0000-0000-000000000003'),
 ('f5100000-0000-0000-0000-000000000003','f5bbbbbb-0000-0000-0000-00000000000b','TOEIC','TOEIC_P5_INCOMPLETE_SENTENCE','f5000000-0000-0000-0000-000000000003'),
 ('f5100000-0000-0000-0000-000000000004','f5aaaaaa-0000-0000-0000-00000000000a','TOEIC','TOEIC_P5_INCOMPLETE_SENTENCE','f5000000-0000-0000-0000-000000000003');
INSERT INTO question_versions(id,question_id,owner_org_id,exam_code,type_code,version_no,status,content,answer_key,created_by) VALUES
 ('f5200000-0000-0000-0000-000000000001','f5100000-0000-0000-0000-000000000001',NULL,'TOEIC','TOEIC_P5_INCOMPLETE_SENTENCE',1,'PUBLISHED','{"stem":"e"}','{"slots":[["A"]]}','f5000000-0000-0000-0000-000000000003'),
 ('f5200000-0000-0000-0000-000000000002','f5100000-0000-0000-0000-000000000002','f5aaaaaa-0000-0000-0000-00000000000a','TOEIC','TOEIC_P5_INCOMPLETE_SENTENCE',1,'PUBLISHED','{"stem":"a"}','{"slots":[["B"]]}','f5000000-0000-0000-0000-000000000003'),
 ('f5200000-0000-0000-0000-000000000003','f5100000-0000-0000-0000-000000000003','f5bbbbbb-0000-0000-0000-00000000000b','TOEIC','TOEIC_P5_INCOMPLETE_SENTENCE',1,'PUBLISHED','{"stem":"b"}','{"slots":[["C"]]}','f5000000-0000-0000-0000-000000000003'),
 ('f5200000-0000-0000-0000-000000000004','f5100000-0000-0000-0000-000000000004','f5aaaaaa-0000-0000-0000-00000000000a','TOEIC','TOEIC_P5_INCOMPLETE_SENTENCE',1,'DRAFT','{"stem":"d"}','{"slots":[["D"]]}','f5000000-0000-0000-0000-000000000003');

-- Attempts (as the owner): 1 = learner 1 in org A, SUBMITTED; 2 = learner 1 personal, SUBMITTED; 3 = learner 1 in org A, IN_PROGRESS; 4 = learner 2 personal, SUBMITTED
INSERT INTO exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,submitted_at,interrupted,submit_reason) VALUES
 ('f5300000-0000-0000-0000-000000000001','f5000000-0000-0000-0000-000000000001','f5aaaaaa-0000-0000-0000-00000000000a',gen_random_uuid(),'SUBMITTED','TOEIC',2,now(),true,'RESUME_LIMIT'),
 ('f5300000-0000-0000-0000-000000000002','f5000000-0000-0000-0000-000000000001',NULL,gen_random_uuid(),'SUBMITTED','TOEIC',2,now(),false,'LEARNER'),
 ('f5300000-0000-0000-0000-000000000004','f5000000-0000-0000-0000-000000000002',NULL,gen_random_uuid(),'SUBMITTED','TOEIC',2,now(),false,'LEARNER');
INSERT INTO exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,deadline_at) VALUES
 ('f5300000-0000-0000-0000-000000000003','f5000000-0000-0000-0000-000000000001','f5aaaaaa-0000-0000-0000-00000000000a',gen_random_uuid(),'IN_PROGRESS','TOEIC',2,now()+interval '1 hour');
INSERT INTO attempt_sections(attempt_id,position,user_id,section_code,skill,duration_sec,starts_at) VALUES
 ('f5300000-0000-0000-0000-000000000001',0,'f5000000-0000-0000-0000-000000000001','P5','READING',1200,now()),
 ('f5300000-0000-0000-0000-000000000002',0,'f5000000-0000-0000-0000-000000000001','P5','READING',1200,now()),
 ('f5300000-0000-0000-0000-000000000003',0,'f5000000-0000-0000-0000-000000000001','P5','READING',1200,now()),
 ('f5300000-0000-0000-0000-000000000004',0,'f5000000-0000-0000-0000-000000000002','P5','READING',1200,now());
INSERT INTO attempt_questions(attempt_id,question_id,user_id,question_version_id,section_code) VALUES
 ('f5300000-0000-0000-0000-000000000001','f5100000-0000-0000-0000-000000000002','f5000000-0000-0000-0000-000000000001','f5200000-0000-0000-0000-000000000002','P5'),
 ('f5300000-0000-0000-0000-000000000002','f5100000-0000-0000-0000-000000000001','f5000000-0000-0000-0000-000000000001','f5200000-0000-0000-0000-000000000001','P5'),
 ('f5300000-0000-0000-0000-000000000003','f5100000-0000-0000-0000-000000000002','f5000000-0000-0000-0000-000000000001','f5200000-0000-0000-0000-000000000002','P5'),
 ('f5300000-0000-0000-0000-000000000004','f5100000-0000-0000-0000-000000000001','f5000000-0000-0000-0000-000000000002','f5200000-0000-0000-0000-000000000001','P5');
INSERT INTO responses(attempt_id,question_id,user_id,answer,answered_at,saved_at) VALUES
 ('f5300000-0000-0000-0000-000000000001','f5100000-0000-0000-0000-000000000002','f5000000-0000-0000-0000-000000000001','"B"',now(),now()),
 ('f5300000-0000-0000-0000-000000000002','f5100000-0000-0000-0000-000000000001','f5000000-0000-0000-0000-000000000001','"A"',now(),now()),
 ('f5300000-0000-0000-0000-000000000003','f5100000-0000-0000-0000-000000000002','f5000000-0000-0000-0000-000000000001','"B"',now(),now()),
 ('f5300000-0000-0000-0000-000000000004','f5100000-0000-0000-0000-000000000001','f5000000-0000-0000-0000-000000000002','"A"',now(),now());

-- ---- the roles -----------------------------------------------------------------------------------------------------------------------
DO $$
DECLARE r pg_roles%ROWTYPE;
BEGIN
  SELECT * INTO r FROM pg_roles WHERE rolname = 'e9x_grader_login';
  ASSERT FOUND, 'e9x_grader_login must exist';
  ASSERT NOT r.rolsuper AND NOT r.rolbypassrls AND NOT r.rolcreaterole AND NOT r.rolcreatedb, 'the grader login is an ordinary role';
  ASSERT pg_has_role('e9x_grader_login', 'e9x_grader', 'MEMBER'), 'a member of e9x_grader';
  ASSERT NOT pg_has_role('e9x_grader_login', 'e9x_app', 'MEMBER') AND NOT pg_has_role('e9x_grader_login', 'e9x_worker', 'MEMBER'), 'and of neither the request role nor the worker';
  ASSERT NOT pg_has_role('e9x_app_login', 'e9x_grader', 'MEMBER') AND NOT pg_has_role('e9x_worker_login', 'e9x_grader', 'MEMBER'), 'neither the request login nor the worker login is the grader';
  ASSERT (SELECT bool_and(relrowsecurity) FROM pg_class WHERE relname IN ('scores','attempt_gradings')), 'row level security on both tables';
  ASSERT (SELECT roles FROM pg_policies WHERE policyname = 'exam_attempts_select') = '{e9x_app}', 'exam_attempts_select is the request role''s only';
  -- the grader holds a grant only on what the scoring needs
  ASSERT NOT has_table_privilege('e9x_grader', 'users', 'SELECT') AND NOT has_table_privilege('e9x_grader', 'role_grants', 'SELECT')
     AND NOT has_table_privilege('e9x_grader', 'entitlements', 'SELECT') AND NOT has_table_privilege('e9x_grader', 'jobs', 'SELECT')
     AND NOT has_table_privilege('e9x_grader', 'event_publication', 'SELECT') AND NOT has_table_privilege('e9x_grader', 'audio_plays', 'SELECT'),
     'the grader reads no account, grant, entitlement, job, event or play';
  ASSERT NOT has_table_privilege('e9x_grader', 'exam_attempts', 'UPDATE') AND NOT has_table_privilege('e9x_grader', 'exam_attempts', 'INSERT')
     AND NOT has_table_privilege('e9x_grader', 'responses', 'INSERT') AND NOT has_table_privilege('e9x_grader', 'question_versions', 'UPDATE')
     AND NOT has_table_privilege('e9x_grader', 'scores', 'UPDATE') AND NOT has_table_privilege('e9x_grader', 'scores', 'DELETE'), 'and writes only scores (insert) and the announcement';
  ASSERT NOT has_column_privilege('e9x_grader', 'exam_attempts', 'max_resumes', 'SELECT') AND NOT has_column_privilege('e9x_grader', 'exam_attempts', 'deadline_at', 'SELECT'),
     'of the attempt it reads the scoring columns only';
  ASSERT NOT has_table_privilege('e9x_app', 'scores', 'INSERT') AND NOT has_table_privilege('e9x_app', 'scores', 'UPDATE') AND NOT has_table_privilege('e9x_app', 'attempt_gradings', 'INSERT'),
     'the request role cannot write a score';
  -- the helper functions: the grader may call them (they answer about the person in the session), the worker may not
  ASSERT has_function_privilege('e9x_grader', 'e9x_caller_is_sys_admin()', 'EXECUTE') AND has_function_privilege('e9x_grader', 'e9x_workflow_in_context(uuid)', 'EXECUTE')
     AND has_function_privilege('e9x_grader', 'e9x_workflow_has_role(uuid,text,text[])', 'EXECUTE'), 'the grader executes the policy helpers';
  ASSERT has_function_privilege('e9x_app', 'e9x_caller_is_sys_admin()', 'EXECUTE'), 'the request role still does';
  ASSERT NOT has_function_privilege('e9x_worker', 'e9x_caller_is_sys_admin()', 'EXECUTE') AND NOT has_function_privilege('e9x_worker', 'e9x_workflow_has_role(uuid,text,text[])', 'EXECUTE'),
     'the worker still does not (REVOKE FROM PUBLIC stands)';
END $$;

-- ---- the grader in the context of attempt 1 (learner 1, org A) ---------------------------------------------------------------------------
SET ROLE e9x_grader;
SELECT set_config('app.user_id','f5000000-0000-0000-0000-000000000001',false), set_config('app.org_id','f5aaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(id) FROM exam_attempts) = ARRAY['f5300000-0000-0000-0000-000000000001'::uuid],
         'only the SUBMITTED attempt of that person in that organisation (not their personal one, not the one in progress, not another learner''s)';
  ASSERT (SELECT count(*) FROM attempt_sections) = 1 AND (SELECT count(*) FROM attempt_questions) = 1 AND (SELECT count(*) FROM responses) = 1, 'and what hangs on it, and nothing else';
  ASSERT (SELECT array_agg(id ORDER BY id) FROM question_versions) = ARRAY['f5200000-0000-0000-0000-000000000001'::uuid,'f5200000-0000-0000-0000-000000000002'::uuid],
         'the PUBLISHED keys of English9x and of org A: not org B''s, not a draft';
  ASSERT (SELECT answer_key->'slots'->0->>0 FROM question_versions WHERE id = 'f5200000-0000-0000-0000-000000000002') = 'B', 'with the answer key';
  ASSERT public.t_attempt('SELECT max_resumes FROM exam_attempts') = 'err:42501', 'the columns of the exam room are not its';
  ASSERT public.t_attempt('SELECT count(*) FROM users') = 'err:42501' AND public.t_attempt('SELECT count(*) FROM role_grants') = 'err:42501', 'no accounts, no grants';
  ASSERT public.t_attempt('UPDATE exam_attempts SET interrupted = false') = 'err:42501', 'writes no attempt';
  ASSERT public.t_attempt('UPDATE responses SET answer = ''"Z"''') = 'err:42501', 'no answer';
  ASSERT public.t_attempt('DELETE FROM responses') = 'err:42501', 'deletes nothing';
  ASSERT public.t_attempt('UPDATE question_versions SET answer_key = ''{}''') = 'err:42501', 'does not touch a key';
  ASSERT e9x_caller_is_sys_admin() = false, 'the helper runs for the grader and says no';
  -- scores: for the SUBMITTED attempt of the session only
  ASSERT public.t_attempt('INSERT INTO scores(attempt_id,user_id,owner_org_id,skill,kind,source,raw,raw_max,scaled) VALUES (''f5300000-0000-0000-0000-000000000001'',''f5000000-0000-0000-0000-000000000001'',''f5aaaaaa-0000-0000-0000-00000000000a'',''READING'',''OFFICIAL'',''AUTO'',1,1,495)') = 'rows:1', 'the grader writes the score';
  ASSERT public.t_attempt('INSERT INTO scores(attempt_id,user_id,owner_org_id,skill,kind,source,raw,raw_max,scaled) VALUES (''f5300000-0000-0000-0000-000000000001'',''f5000000-0000-0000-0000-000000000001'',''f5aaaaaa-0000-0000-0000-00000000000a'',''READING'',''OFFICIAL'',''AUTO'',0,1,5)') = 'err:23505', 'once per skill and kind';
  ASSERT public.t_attempt('INSERT INTO scores(attempt_id,user_id,owner_org_id,skill,kind,source,raw,raw_max) VALUES (''f5300000-0000-0000-0000-000000000001'',''f5000000-0000-0000-0000-000000000001'',''f5aaaaaa-0000-0000-0000-00000000000a'',''LISTENING'',''OFFICIAL'',''AUTO'',0,1)') = 'rows:1', 'a score with no conversion yet (raw only)';
  ASSERT public.t_attempt('INSERT INTO scores(attempt_id,user_id,owner_org_id,skill,kind,source,raw,raw_max) VALUES (''f5300000-0000-0000-0000-000000000001'',''f5000000-0000-0000-0000-000000000001'',''f5aaaaaa-0000-0000-0000-00000000000a'',''WRITING'',''PRELIMINARY'',''AUTO'',0,1)') = 'err:23514', 'an automatic score is never preliminary';
  ASSERT public.t_attempt('INSERT INTO scores(attempt_id,user_id,owner_org_id,skill,kind,source,raw,raw_max) VALUES (''f5300000-0000-0000-0000-000000000001'',''f5000000-0000-0000-0000-000000000001'',''f5aaaaaa-0000-0000-0000-00000000000a'',''SPEAKING'',''OFFICIAL'',''AUTO'',2,1)') = 'err:23514', 'raw never exceeds the maximum';
  ASSERT public.t_attempt('INSERT INTO scores(attempt_id,user_id,owner_org_id,skill,kind,source,raw,raw_max) VALUES (''f5300000-0000-0000-0000-000000000003'',''f5000000-0000-0000-0000-000000000001'',''f5aaaaaa-0000-0000-0000-00000000000a'',''READING'',''OFFICIAL'',''AUTO'',1,1)') = 'err:42501', 'not for an attempt still in progress';
  ASSERT public.t_attempt('INSERT INTO scores(attempt_id,user_id,owner_org_id,skill,kind,source,raw,raw_max) VALUES (''f5300000-0000-0000-0000-000000000002'',''f5000000-0000-0000-0000-000000000001'',NULL,''READING'',''OFFICIAL'',''AUTO'',1,1)') = 'err:42501', 'not for the attempt of another context (personal)';
  ASSERT public.t_attempt('INSERT INTO scores(attempt_id,user_id,owner_org_id,skill,kind,source,raw,raw_max) VALUES (''f5300000-0000-0000-0000-000000000004'',''f5000000-0000-0000-0000-000000000002'',''f5aaaaaa-0000-0000-0000-00000000000a'',''READING'',''OFFICIAL'',''AUTO'',1,1)') = 'err:42501', 'not for another learner';
  ASSERT public.t_attempt('INSERT INTO scores(attempt_id,user_id,owner_org_id,skill,kind,source,raw,raw_max) VALUES (''f5300000-0000-0000-0000-000000000001'',''f5000000-0000-0000-0000-000000000002'',''f5aaaaaa-0000-0000-0000-00000000000a'',''SPEAKING'',''OFFICIAL'',''AUTO'',1,1)') = 'err:42501', 'nor with another learner''s name on the score';
  ASSERT public.t_attempt('UPDATE scores SET scaled = 5') = 'err:42501', 'a score is not changed';
  ASSERT public.t_attempt('DELETE FROM scores') = 'err:42501', 'nor deleted';
  -- attempt_gradings: once, with the interrupted flag; announced once
  ASSERT public.t_attempt('INSERT INTO attempt_gradings(attempt_id,user_id,owner_org_id,interrupted) VALUES (''f5300000-0000-0000-0000-000000000001'',''f5000000-0000-0000-0000-000000000001'',''f5aaaaaa-0000-0000-0000-00000000000a'',true)') = 'rows:1', 'the grading record';
  ASSERT public.t_attempt('INSERT INTO attempt_gradings(attempt_id,user_id,owner_org_id,interrupted) VALUES (''f5300000-0000-0000-0000-000000000001'',''f5000000-0000-0000-0000-000000000001'',''f5aaaaaa-0000-0000-0000-00000000000a'',true)') = 'err:23505', 'one per attempt (the key of idempotency)';
  ASSERT public.t_attempt('INSERT INTO attempt_gradings(attempt_id,user_id,owner_org_id,interrupted) VALUES (''f5300000-0000-0000-0000-000000000003'',''f5000000-0000-0000-0000-000000000001'',''f5aaaaaa-0000-0000-0000-00000000000a'',false)') = 'err:42501', 'not for an attempt in progress';
  ASSERT public.t_attempt('UPDATE attempt_gradings SET interrupted = false') = 'err:42501', 'the flag is not changed';
  ASSERT public.t_attempt('UPDATE attempt_gradings SET announced_at = now()') = 'rows:1', 'the announcement time is set';
  ASSERT public.t_attempt('UPDATE attempt_gradings SET announced_at = now()') = 'rows:0', 'once';
  ASSERT public.t_attempt('DELETE FROM attempt_gradings') = 'err:42501', 'the record is not deleted';
  ASSERT (SELECT count(*) FROM scores) = 2 AND (SELECT count(*) FROM attempt_gradings) = 1, 'the grader reads back its own rows of the session';
END $$;

-- the same grader in the personal context of learner 1, in the context of another learner in org A, and with nothing bound
SELECT set_config('app.org_id','',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(id) FROM exam_attempts) = ARRAY['f5300000-0000-0000-0000-000000000002'::uuid], 'the personal context reads the personal attempt only';
  ASSERT (SELECT array_agg(id) FROM question_versions) = ARRAY['f5200000-0000-0000-0000-000000000001'::uuid], 'and only English9x''s keys';
  ASSERT (SELECT count(*) FROM scores) = 0, 'and none of the scores of the organisation attempt';
END $$;
SELECT set_config('app.user_id','f5000000-0000-0000-0000-000000000002',false), set_config('app.org_id','f5aaaaaa-0000-0000-0000-00000000000a',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM exam_attempts) = 0 AND (SELECT count(*) FROM responses) = 0, 'learner 2 in org A has no submitted attempt there: nothing';
END $$;
SELECT set_config('app.user_id','',false), set_config('app.org_id','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM exam_attempts) = 0 AND (SELECT count(*) FROM attempt_questions) = 0 AND (SELECT count(*) FROM scores) = 0, 'nothing without a context';
  ASSERT (SELECT array_agg(id) FROM question_versions) = ARRAY['f5200000-0000-0000-0000-000000000001'::uuid], 'except the published English9x key';
END $$;
RESET ROLE;

-- ---- the request role: reads its own score, writes none ---------------------------------------------------------------------------
SET ROLE e9x_app;
SELECT set_config('app.user_id','f5000000-0000-0000-0000-000000000001',false), set_config('app.org_id','f5aaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM scores) = 2 AND (SELECT count(*) FROM attempt_gradings) = 1 AND (SELECT bool_and(interrupted) FROM attempt_gradings), 'the learner reads their own scores and the flag';
  ASSERT public.t_attempt('INSERT INTO scores(attempt_id,user_id,owner_org_id,skill,kind,source,raw,raw_max,scaled) VALUES (''f5300000-0000-0000-0000-000000000001'',''f5000000-0000-0000-0000-000000000001'',''f5aaaaaa-0000-0000-0000-00000000000a'',''SPEAKING'',''OFFICIAL'',''AUTO'',1,1,9)') = 'err:42501', 'a request cannot write a score';
  ASSERT public.t_attempt('UPDATE scores SET scaled = 495') = 'err:42501', 'nor raise one';
  ASSERT public.t_attempt('INSERT INTO attempt_gradings(attempt_id,user_id,owner_org_id,interrupted) VALUES (''f5300000-0000-0000-0000-000000000003'',''f5000000-0000-0000-0000-000000000001'',''f5aaaaaa-0000-0000-0000-00000000000a'',false)') = 'err:42501', 'nor a grading record';
  -- narrowing exam_attempts_select did not take the request role's own reads away
  ASSERT (SELECT count(*) FROM exam_attempts) >= 2, 'the request role still reads its attempts';
END $$;
SELECT set_config('app.user_id','f5000000-0000-0000-0000-000000000002',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM scores) = 0 AND (SELECT count(*) FROM attempt_gradings) = 0, 'another learner, even of the same organisation, reads none of it';
END $$;
RESET ROLE;

-- the owner is held to the same rule: a score never changes
DO $$
BEGIN
  ASSERT public.t_attempt('UPDATE scores SET scaled = 1') = 'err:42501', 'not even the owner changes a score';
  ASSERT public.t_attempt('DELETE FROM scores') = 'err:42501', 'or deletes one';
END $$;
