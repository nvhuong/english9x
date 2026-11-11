-- P3.3 exam backend (F29-F31): exam_attempts (extended), attempt_sections, attempt_questions, responses, audio_plays, speaking_recordings.
--  * Everything of an attempt is its learner's own (user_id = the person): another learner, a colleague of the same organisation and an organisation
--    administrator read and write none of it, whatever the context.
--  * A member who LEFT the organisation can still update (submit) their own IN_PROGRESS attempt in the personal context: the auto-submit job needs it.
--  * The identity of an attempt never changes, and a SUBMITTED attempt never changes again (trigger, also for the owner of the tables).
--  * Nothing reachable from the exam-room tables holds the solution side, and the application role cannot read the key columns of the questions table.
INSERT INTO exams(code,name) VALUES ('TOEIC','TOEIC') ON CONFLICT DO NOTHING;
INSERT INTO users(id,email) VALUES
 ('e7000000-0000-0000-0000-000000000001','ex-learner1@x.test'),
 ('e7000000-0000-0000-0000-000000000002','ex-learner2@x.test'),
 ('e7000000-0000-0000-0000-000000000003','ex-admin@x.test');
INSERT INTO organizations(id,name,type) VALUES ('e7aaaaaa-0000-0000-0000-00000000000a','Exam Center','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('e7aaaaaa-0000-0000-0000-00000000000a','e7000000-0000-0000-0000-000000000001'),
 ('e7aaaaaa-0000-0000-0000-00000000000a','e7000000-0000-0000-0000-000000000002'),
 ('e7aaaaaa-0000-0000-0000-00000000000a','e7000000-0000-0000-0000-000000000003');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('e7000000-0000-0000-0000-000000000003','e7aaaaaa-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN');

CREATE FUNCTION public.t_attempt(stmt text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  EXECUTE stmt; GET DIAGNOSTICS n = ROW_COUNT;
  RETURN 'rows:' || n;
EXCEPTION WHEN OTHERS THEN RETURN 'err:' || SQLSTATE;
END $$;
GRANT EXECUTE ON FUNCTION public.t_attempt(text) TO e9x_app;

-- fixtures as the owner: learner 1's attempt in the organisation (two sections, one question, one response), learner 2's personal attempt
INSERT INTO exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,deadline_at) VALUES
 ('e7100000-0000-0000-0000-000000000001','e7000000-0000-0000-0000-000000000001','e7aaaaaa-0000-0000-0000-00000000000a',gen_random_uuid(),'IN_PROGRESS','TOEIC',2,now()+interval '1 hour'),
 ('e7100000-0000-0000-0000-000000000002','e7000000-0000-0000-0000-000000000002',NULL,gen_random_uuid(),'IN_PROGRESS','TOEIC',2,now()+interval '1 hour');
INSERT INTO attempt_sections(attempt_id,position,user_id,section_code,skill,duration_sec,starts_at) VALUES
 ('e7100000-0000-0000-0000-000000000001',0,'e7000000-0000-0000-0000-000000000001','P1','LISTENING',600,now()),
 ('e7100000-0000-0000-0000-000000000001',1,'e7000000-0000-0000-0000-000000000001','P5','READING',1200,NULL);
INSERT INTO attempt_questions(attempt_id,question_id,user_id,question_version_id,section_code) VALUES
 ('e7100000-0000-0000-0000-000000000001','e7200000-0000-0000-0000-000000000001','e7000000-0000-0000-0000-000000000001',gen_random_uuid(),'P1');
INSERT INTO responses(attempt_id,question_id,user_id,answer,answered_at,saved_at) VALUES
 ('e7100000-0000-0000-0000-000000000001','e7200000-0000-0000-0000-000000000001','e7000000-0000-0000-0000-000000000001','"B"',now(),now());
INSERT INTO audio_plays(attempt_id,media_id,user_id,played_at) VALUES
 ('e7100000-0000-0000-0000-000000000001',gen_random_uuid(),'e7000000-0000-0000-0000-000000000001',now());

DO $$
BEGIN
  ASSERT (SELECT bool_and(relrowsecurity) FROM pg_class WHERE relname IN ('attempt_sections','attempt_questions','responses','audio_plays','speaking_recordings')), 'RLS on every table of the exam room';
  -- the identity of an attempt never changes, as the table owner too
  ASSERT public.t_attempt('UPDATE exam_attempts SET user_id = ''e7000000-0000-0000-0000-000000000002'' WHERE id = ''e7100000-0000-0000-0000-000000000001''') = 'err:42501', 'the owner cannot change';
  ASSERT public.t_attempt('UPDATE exam_attempts SET owner_org_id = NULL WHERE id = ''e7100000-0000-0000-0000-000000000001''') = 'err:42501', 'nor the organisation';
  ASSERT public.t_attempt('UPDATE exam_attempts SET test_version_id = gen_random_uuid() WHERE id = ''e7100000-0000-0000-0000-000000000001''') = 'err:42501', 'nor the test version';
  ASSERT public.t_attempt('UPDATE exam_attempts SET submit_reason = ''BOGUS'' WHERE id = ''e7100000-0000-0000-0000-000000000001''') = 'err:23514', 'only the known reasons';
  -- a response needs a question of the attempt
  ASSERT public.t_attempt('INSERT INTO responses(attempt_id,question_id,user_id,answer,answered_at,saved_at) VALUES (''e7100000-0000-0000-0000-000000000001'',gen_random_uuid(),''e7000000-0000-0000-0000-000000000001'',''"A"'',now(),now())') = 'err:23503', 'no answer to a question that is not in the test';
END $$;

-- learner 1 in the organisation: sees and changes their own
SET ROLE e9x_app;
SELECT set_config('app.user_id','e7000000-0000-0000-0000-000000000001',false), set_config('app.org_id','e7aaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM exam_attempts) = 1 AND (SELECT count(*) FROM attempt_sections) = 2 AND (SELECT count(*) FROM responses) = 1
     AND (SELECT count(*) FROM attempt_questions) = 1 AND (SELECT count(*) FROM audio_plays) = 1, 'the learner sees their own attempt and what hangs on it';
  ASSERT public.t_attempt('INSERT INTO responses(attempt_id,question_id,user_id,answer,answered_at,saved_at) VALUES (''e7100000-0000-0000-0000-000000000001'',''e7200000-0000-0000-0000-000000000001'',''e7000000-0000-0000-0000-000000000001'',''"C"'',now(),now()) ON CONFLICT (attempt_id,question_id) DO UPDATE SET answer = excluded.answer') = 'rows:1', 'upsert of an answer';
  ASSERT public.t_attempt('INSERT INTO responses(attempt_id,question_id,user_id,answer,answered_at,saved_at) VALUES (''e7100000-0000-0000-0000-000000000001'',''e7200000-0000-0000-0000-000000000001'',''e7000000-0000-0000-0000-000000000002'',''"C"'',now(),now())') = 'err:42501', 'not as somebody else';
  ASSERT public.t_attempt('DELETE FROM responses') = 'err:42501', 'no delete';
  ASSERT public.t_attempt('DELETE FROM exam_attempts') = 'err:42501', 'no delete of an attempt';
  ASSERT public.t_attempt('UPDATE exam_attempts SET resume_count = 1 WHERE id = ''e7100000-0000-0000-0000-000000000001''') = 'rows:1', 'state changes';
  ASSERT public.t_attempt('UPDATE exam_attempts SET user_id = ''e7000000-0000-0000-0000-000000000002'' WHERE id = ''e7100000-0000-0000-0000-000000000001''') = 'err:42501', 'the identity stays';
END $$;
-- the same learner after leaving the organisation: personal context, still submits their own attempt (auto_submit_attempt)
DELETE FROM org_memberships WHERE user_id = 'e7000000-0000-0000-0000-000000000001';
SELECT set_config('app.org_id','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM exam_attempts) = 1, 'still theirs to read';
  ASSERT public.t_attempt('UPDATE exam_attempts SET status = ''SUBMITTED'', submit_reason = ''TIME_UP'', submitted_at = now(), version = version + 1 WHERE id = ''e7100000-0000-0000-0000-000000000001''') = 'rows:1', 'submitted in the personal context';
  ASSERT public.t_attempt('UPDATE exam_attempts SET submit_reason = ''LEARNER'' WHERE id = ''e7100000-0000-0000-0000-000000000001''') = 'rows:0', 'a submitted attempt is not updatable';
END $$;
-- learner 2, the organisation administrator: none of it
SELECT set_config('app.user_id','e7000000-0000-0000-0000-000000000002',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM exam_attempts WHERE id = 'e7100000-0000-0000-0000-000000000001') = 0, 'another learner cannot read the attempt';
  ASSERT (SELECT count(*) FROM responses) = 0 AND (SELECT count(*) FROM attempt_sections) = 0 AND (SELECT count(*) FROM audio_plays) = 0 AND (SELECT count(*) FROM attempt_questions) = 0, 'nor its responses, sections, plays or questions';
  ASSERT public.t_attempt('UPDATE exam_attempts SET resume_count = 5 WHERE id = ''e7100000-0000-0000-0000-000000000001''') = 'rows:0', 'nor change it';
  ASSERT public.t_attempt('UPDATE responses SET answer = ''"Z"''') = 'rows:0', 'nor change an answer';
END $$;
SELECT set_config('app.user_id','e7000000-0000-0000-0000-000000000003',false), set_config('app.org_id','e7aaaaaa-0000-0000-0000-00000000000a',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM responses) = 0 AND (SELECT count(*) FROM attempt_sections) = 0, 'the organisation administrator reads no answers of the exam room';
  ASSERT public.t_attempt('UPDATE exam_attempts SET resume_count = 7') = 'rows:0', 'and changes no attempt';
END $$;
-- the solution side: not reachable by the application role from the key columns of the base table, and no exam-room table has one
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM question_versions) = 0 AND (SELECT count(*) FROM question_group_versions) = 0, 'a learner reads no question version of the base tables (where the key lives)';
  ASSERT pg_get_function_result('e9x_question_exam_view(uuid)'::regprocedure) !~* '(answer|explanation|transcript|solution)', 'the exam view function returns no solution column';
  ASSERT NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name IN ('exam_attempts','attempt_sections','attempt_questions','responses','audio_plays','speaking_recordings')
                       AND (column_name LIKE '%key%' OR column_name LIKE '%solution%' OR column_name LIKE '%transcript%' OR column_name LIKE '%explanation%')),
         'no table of the exam room holds the solution side';
END $$;
RESET ROLE;
