-- P4.2 AI grading (V202611140002): the grader's way to the PUBLISHED grading skill (decision of P2.1-fix1 M3), ai_gradings, ai_evaluations.
--  * The ai_grade job reads the skill as e9x_grader with the attempt's organisation in app.org_id: it sees PUBLISHED skills of English9x and of that organisation,
--    nothing else (no draft, no other organisation). The learner's own role (e9x_app, any context) reads no skill row at all, which is why the job must not run as the learner.
--  * ai_gradings / ai_evaluations: written only by the grader, only for the SUBMITTED attempt of the person and organisation in the session; the learner reads their own.
--    AI_FAILED may become DONE and nothing else moves; an evaluation is never changed.
--  * scores_has_preliminary answers only a session with nobody bound (the notification listener).
INSERT INTO users(id,email) VALUES
 ('f6000000-0000-0000-0000-000000000001','ai-learner1@x.test'),
 ('f6000000-0000-0000-0000-000000000002','ai-learner2@x.test'),
 ('f6000000-0000-0000-0000-000000000003','ai-author@x.test');
INSERT INTO organizations(id,name,type) VALUES
 ('f6aaaaaa-0000-0000-0000-00000000000a','AI Center A','CENTER'),
 ('f6bbbbbb-0000-0000-0000-00000000000b','AI Center B','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('f6aaaaaa-0000-0000-0000-00000000000a','f6000000-0000-0000-0000-000000000001');

CREATE FUNCTION public.t_ai(stmt text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  EXECUTE stmt; GET DIAGNOSTICS n = ROW_COUNT;
  RETURN 'rows:' || n;
EXCEPTION WHEN OTHERS THEN RETURN 'err:' || SQLSTATE;
END $$;
GRANT EXECUTE ON FUNCTION public.t_ai(text) TO e9x_app, e9x_grader;

-- Skills: s1 English9x Writing task 2 (PUBLISHED); s2 org A Speaking part 1 (PUBLISHED); s3 org B Speaking part 2 (PUBLISHED); s4 org A Writing task 2 (DRAFT only).
INSERT INTO grading_skills(id,owner_org_id,exam_code,question_type_code) VALUES
 ('f6100000-0000-0000-0000-000000000001',NULL,'IELTS','IELTS_W_TASK2'),
 ('f6100000-0000-0000-0000-000000000002','f6aaaaaa-0000-0000-0000-00000000000a','IELTS','IELTS_S_PART1'),
 ('f6100000-0000-0000-0000-000000000003','f6bbbbbb-0000-0000-0000-00000000000b','IELTS','IELTS_S_PART2'),
 ('f6100000-0000-0000-0000-000000000004','f6aaaaaa-0000-0000-0000-00000000000a','IELTS','IELTS_W_TASK2');
ALTER TABLE grading_skill_versions DISABLE TRIGGER USER;
INSERT INTO grading_skill_versions(id,grading_skill_id,owner_org_id,exam_code,version_no,status,rubric,created_by) VALUES
 ('f6200000-0000-0000-0000-000000000001','f6100000-0000-0000-0000-000000000001',NULL,'IELTS',1,'PUBLISHED','english9x writing','f6000000-0000-0000-0000-000000000003'),
 ('f6200000-0000-0000-0000-000000000002','f6100000-0000-0000-0000-000000000002','f6aaaaaa-0000-0000-0000-00000000000a','IELTS',1,'PUBLISHED','org a speaking','f6000000-0000-0000-0000-000000000003'),
 ('f6200000-0000-0000-0000-000000000003','f6100000-0000-0000-0000-000000000003','f6bbbbbb-0000-0000-0000-00000000000b','IELTS',1,'PUBLISHED','org b speaking','f6000000-0000-0000-0000-000000000003'),
 ('f6200000-0000-0000-0000-000000000004','f6100000-0000-0000-0000-000000000004','f6aaaaaa-0000-0000-0000-00000000000a','IELTS',1,'DRAFT','org a writing draft','f6000000-0000-0000-0000-000000000003');
ALTER TABLE grading_skill_versions ENABLE TRIGGER USER;

-- Attempts: 1 = learner 1 in org A, SUBMITTED; 2 = learner 1 in org A, IN_PROGRESS; 3 = learner 2 personal, SUBMITTED
INSERT INTO exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,submitted_at,interrupted,submit_reason) VALUES
 ('f6300000-0000-0000-0000-000000000001','f6000000-0000-0000-0000-000000000001','f6aaaaaa-0000-0000-0000-00000000000a',gen_random_uuid(),'SUBMITTED','IELTS',2,now(),false,'LEARNER'),
 ('f6300000-0000-0000-0000-000000000003','f6000000-0000-0000-0000-000000000002',NULL,gen_random_uuid(),'SUBMITTED','IELTS',2,now(),false,'LEARNER');
INSERT INTO exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,deadline_at) VALUES
 ('f6300000-0000-0000-0000-000000000002','f6000000-0000-0000-0000-000000000001','f6aaaaaa-0000-0000-0000-00000000000a',gen_random_uuid(),'IN_PROGRESS','IELTS',2,now()+interval '1 hour');
INSERT INTO questions(id,owner_org_id,exam_code,type_code,created_by) VALUES
 ('f6400000-0000-0000-0000-000000000001',NULL,'IELTS','IELTS_S_PART1','f6000000-0000-0000-0000-000000000003');
INSERT INTO question_versions(id,question_id,owner_org_id,exam_code,type_code,version_no,status,content,answer_key,created_by) VALUES
 ('f6500000-0000-0000-0000-000000000001','f6400000-0000-0000-0000-000000000001',NULL,'IELTS','IELTS_S_PART1',1,'PUBLISHED','{"topic":"Home"}','{}','f6000000-0000-0000-0000-000000000003');
INSERT INTO attempt_sections(attempt_id,position,user_id,section_code,skill,duration_sec,starts_at) VALUES
 ('f6300000-0000-0000-0000-000000000001',0,'f6000000-0000-0000-0000-000000000001','SPEAKING','SPEAKING',600,now());
INSERT INTO attempt_questions(attempt_id,question_id,user_id,question_version_id,section_code) VALUES
 ('f6300000-0000-0000-0000-000000000001','f6400000-0000-0000-0000-000000000001','f6000000-0000-0000-0000-000000000001','f6500000-0000-0000-0000-000000000001','SPEAKING');
INSERT INTO speaking_recordings(attempt_id,question_id,user_id,storage_path,mime_type,size_bytes,uploaded_at) VALUES
 ('f6300000-0000-0000-0000-000000000001','f6400000-0000-0000-0000-000000000001','f6000000-0000-0000-0000-000000000001','staging/recordings/x.webm','audio/webm',10,now());

-- ---- the grant list ------------------------------------------------------------------------------------------------------------------
DO $$
BEGIN
  ASSERT (SELECT bool_and(relrowsecurity) FROM pg_class WHERE relname IN ('ai_gradings','ai_evaluations')), 'row level security on both tables';
  ASSERT has_table_privilege('e9x_grader', 'grading_skill_versions', 'SELECT') AND NOT has_table_privilege('e9x_grader', 'grading_skill_versions', 'UPDATE')
     AND NOT has_table_privilege('e9x_grader', 'grading_skill_versions', 'INSERT'), 'the grader reads skills and changes none';
  ASSERT NOT has_table_privilege('e9x_app', 'ai_gradings', 'INSERT') AND NOT has_table_privilege('e9x_app', 'ai_evaluations', 'INSERT')
     AND NOT has_table_privilege('e9x_app', 'ai_gradings', 'UPDATE') AND NOT has_table_privilege('e9x_app', 'ai_evaluations', 'UPDATE'), 'the request role writes neither';
  ASSERT NOT has_table_privilege('e9x_grader', 'ai_evaluations', 'UPDATE') AND NOT has_table_privilege('e9x_grader', 'ai_evaluations', 'DELETE')
     AND NOT has_table_privilege('e9x_grader', 'ai_gradings', 'DELETE'), 'the grader updates no evaluation and deletes nothing';
  ASSERT NOT has_function_privilege('e9x_grader', 'scores_has_preliminary(uuid,uuid)', 'EXECUTE') AND has_function_privilege('e9x_app', 'scores_has_preliminary(uuid,uuid)', 'EXECUTE'),
     'the notification function is the request role''s';
END $$;

-- ---- the GRADER READ PATH: the published skill, as e9x_grader, in the context of org A -----------------------------------------------------
SET ROLE e9x_grader;
SELECT set_config('app.user_id','f6000000-0000-0000-0000-000000000001',false), set_config('app.org_id','f6aaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(v.id ORDER BY v.id) FROM grading_skill_versions v JOIN grading_skills s ON s.id = v.grading_skill_id)
         = ARRAY['f6200000-0000-0000-0000-000000000001'::uuid,'f6200000-0000-0000-0000-000000000002'::uuid],
         'the PUBLISHED skills of English9x and of org A: not org B''s, not a draft';
  ASSERT (SELECT v.rubric FROM grading_skill_versions v JOIN grading_skills s ON s.id = v.grading_skill_id WHERE s.question_type_code = 'IELTS_W_TASK2' AND v.status = 'PUBLISHED') = 'english9x writing',
         'the lookup of the job (by question type, PUBLISHED) finds the skill';
END $$;
SELECT set_config('app.org_id','',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(v.id) FROM grading_skill_versions v) = ARRAY['f6200000-0000-0000-0000-000000000001'::uuid], 'in the personal context: English9x''s only';
END $$;
-- with the org of another organisation bound: that organisation's and English9x's
SELECT set_config('app.org_id','f6bbbbbb-0000-0000-0000-00000000000b',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(v.id ORDER BY v.id) FROM grading_skill_versions v) = ARRAY['f6200000-0000-0000-0000-000000000001'::uuid,'f6200000-0000-0000-0000-000000000003'::uuid], 'org B: its own and English9x''s';
  ASSERT public.t_ai('UPDATE grading_skill_versions SET rubric = ''x''') = 'err:42501', 'the grader changes no skill';
END $$;
RESET ROLE;

-- the learner (e9x_app) cannot read a skill: in any context
SET ROLE e9x_app;
SELECT set_config('app.user_id','f6000000-0000-0000-0000-000000000001',false), set_config('app.org_id','f6aaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM grading_skill_versions) = 0 AND (SELECT count(*) FROM grading_skills) = 0, 'a learner (e9x_app, in the context of the attempt) reads 0 skill rows';
END $$;
SELECT set_config('app.org_id','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM grading_skill_versions) = 0, 'and in the personal context';
END $$;
RESET ROLE;

-- ---- the grader writes the AI records, for the attempt of the session only ----------------------------------------------------------------
SET ROLE e9x_grader;
SELECT set_config('app.user_id','f6000000-0000-0000-0000-000000000001',false), set_config('app.org_id','f6aaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM speaking_recordings) = 1, 'the recording of the attempt in the session';
  ASSERT public.t_ai('INSERT INTO ai_evaluations(attempt_id,question_id,user_id,owner_org_id,skill_version_id,model,input_tokens,output_tokens,cost_vnd,band,result) VALUES (''f6300000-0000-0000-0000-000000000001'',''f6400000-0000-0000-0000-000000000001'',''f6000000-0000-0000-0000-000000000001'',''f6aaaaaa-0000-0000-0000-00000000000a'',''f6200000-0000-0000-0000-000000000002'',''m'',10,5,1.5,6.5,''{"TA":6}'')') = 'rows:1', 'an evaluation';
  ASSERT public.t_ai('INSERT INTO ai_evaluations(attempt_id,question_id,user_id,owner_org_id,skill_version_id,model,input_tokens,output_tokens,cost_vnd,band,result) VALUES (''f6300000-0000-0000-0000-000000000001'',''f6400000-0000-0000-0000-000000000001'',''f6000000-0000-0000-0000-000000000001'',''f6aaaaaa-0000-0000-0000-00000000000a'',NULL,''m'',10,5,1.5,6.5,''{}'')') = 'err:23505', 'one per question';
  ASSERT public.t_ai('INSERT INTO ai_evaluations(attempt_id,question_id,user_id,owner_org_id,model,input_tokens,output_tokens,cost_vnd,band,result) VALUES (''f6300000-0000-0000-0000-000000000002'',gen_random_uuid(),''f6000000-0000-0000-0000-000000000001'',''f6aaaaaa-0000-0000-0000-00000000000a'',''m'',1,1,0,1,''{}'')') = 'err:42501', 'not for an attempt in progress';
  ASSERT public.t_ai('INSERT INTO ai_evaluations(attempt_id,question_id,user_id,owner_org_id,model,input_tokens,output_tokens,cost_vnd,band,result) VALUES (''f6300000-0000-0000-0000-000000000003'',gen_random_uuid(),''f6000000-0000-0000-0000-000000000002'',NULL,''m'',1,1,0,1,''{}'')') = 'err:42501', 'not for another learner';
  ASSERT public.t_ai('UPDATE ai_evaluations SET band = 9') = 'err:42501' AND public.t_ai('DELETE FROM ai_evaluations') = 'err:42501', 'an evaluation is final';
  ASSERT public.t_ai('INSERT INTO ai_gradings(attempt_id,user_id,owner_org_id,state,error) VALUES (''f6300000-0000-0000-0000-000000000001'',''f6000000-0000-0000-0000-000000000001'',''f6aaaaaa-0000-0000-0000-00000000000a'',''AI_FAILED'',''x'')') = 'rows:1', 'AI_FAILED';
  ASSERT public.t_ai('INSERT INTO ai_gradings(attempt_id,user_id,owner_org_id,state) VALUES (''f6300000-0000-0000-0000-000000000001'',''f6000000-0000-0000-0000-000000000001'',''f6aaaaaa-0000-0000-0000-00000000000a'',''DONE'')') = 'err:23505', 'one state per attempt';
  ASSERT public.t_ai('INSERT INTO ai_gradings(attempt_id,user_id,owner_org_id,state) VALUES (''f6300000-0000-0000-0000-000000000002'',''f6000000-0000-0000-0000-000000000001'',''f6aaaaaa-0000-0000-0000-00000000000a'',''DONE'')') = 'err:42501', 'not for an attempt in progress';
  ASSERT public.t_ai('INSERT INTO ai_gradings(attempt_id,user_id,owner_org_id,state) VALUES (''f6300000-0000-0000-0000-000000000001'',''f6000000-0000-0000-0000-000000000001'',''f6aaaaaa-0000-0000-0000-00000000000a'',''BOGUS'')') = 'err:23514', 'a known state';
  ASSERT public.t_ai('UPDATE ai_gradings SET state = ''TEACHER_QUEUE''') = 'err:42501', 'AI_FAILED becomes DONE and nothing else';
  ASSERT public.t_ai('UPDATE ai_gradings SET state = ''DONE'', error = NULL, updated_at = now()') = 'rows:1', 'a late run completes the work';
  ASSERT public.t_ai('UPDATE ai_gradings SET state = ''AI_FAILED''') = 'rows:0', 'DONE is final (no policy row is visible to the update)';
  ASSERT public.t_ai('DELETE FROM ai_gradings') = 'err:42501', 'never deleted';
END $$;
RESET ROLE;

-- ---- the learner reads their own, writes none ---------------------------------------------------------------------------------------------
SET ROLE e9x_app;
SELECT set_config('app.user_id','f6000000-0000-0000-0000-000000000001',false), set_config('app.org_id','f6aaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM ai_evaluations) = 1 AND (SELECT count(*) FROM ai_gradings) = 1, 'the learner reads their own AI records';
  ASSERT public.t_ai('INSERT INTO ai_gradings(attempt_id,user_id,state) VALUES (gen_random_uuid(),''f6000000-0000-0000-0000-000000000001'',''DONE'')') = 'err:42501', 'and writes none';
END $$;
SELECT set_config('app.user_id','f6000000-0000-0000-0000-000000000002',false), set_config('app.org_id','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM ai_evaluations) = 0 AND (SELECT count(*) FROM ai_gradings) = 0, 'another learner reads nothing of it';
  ASSERT (SELECT scores_has_preliminary('f6300000-0000-0000-0000-000000000001','f6000000-0000-0000-0000-000000000001')) = false, 'a signed-in request gets nothing from the notification function';
END $$;
RESET ROLE;

-- ---- scores_has_preliminary: with nobody bound -----------------------------------------------------------------------------------------------
INSERT INTO scores(attempt_id,user_id,owner_org_id,skill,kind,source,raw,scaled) VALUES
 ('f6300000-0000-0000-0000-000000000001','f6000000-0000-0000-0000-000000000001','f6aaaaaa-0000-0000-0000-00000000000a','SPEAKING','PRELIMINARY','AI',6,6);
SET ROLE e9x_app;
SELECT set_config('app.user_id','',false), set_config('app.org_id','',false);
DO $$
BEGIN
  ASSERT scores_has_preliminary('f6300000-0000-0000-0000-000000000001','f6000000-0000-0000-0000-000000000001') = true, 'the person holds a PRELIMINARY score of the attempt';
  ASSERT scores_has_preliminary('f6300000-0000-0000-0000-000000000001','f6000000-0000-0000-0000-000000000002') = false, 'not another person';
  ASSERT scores_has_preliminary(gen_random_uuid(),'f6000000-0000-0000-0000-000000000001') = false, 'not another attempt';
END $$;
RESET ROLE;
