-- P4.5 progress by plan and AI monitor (V202611160002): the SECURITY DEFINER functions re-check WHO asks in SQL.
--  * progress_viewable: a person reads their own progress; nobody else's without an organisation context; an organisation's ORG_ADMIN / TEACHER reads a MEMBER of the
--    organisation of the session context, not a member of another organisation, and not from another context; a plain member reads no other member.
--  * monitor_*: empty for everybody but a SYS_ADMIN in the English9x context (flag AND grant); EXECUTE is the request role's only.
INSERT INTO exams(code,name) VALUES ('TOEIC','TOEIC') ON CONFLICT DO NOTHING;
INSERT INTO users(id,email) VALUES
 ('f7000000-0000-0000-0000-000000000001','pm-learner@x.test'),
 ('f7000000-0000-0000-0000-000000000002','pm-admin@x.test'),
 ('f7000000-0000-0000-0000-000000000003','pm-teacher@x.test'),
 ('f7000000-0000-0000-0000-000000000004','pm-sys@x.test'),
 ('f7000000-0000-0000-0000-000000000005','pm-outsider@x.test');
INSERT INTO organizations(id,name,type) VALUES ('f7100000-0000-0000-0000-000000000001','PM org A','CENTER'), ('f7100000-0000-0000-0000-000000000002','PM org B','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('f7100000-0000-0000-0000-000000000001','f7000000-0000-0000-0000-000000000001'),
 ('f7100000-0000-0000-0000-000000000001','f7000000-0000-0000-0000-000000000002'),
 ('f7100000-0000-0000-0000-000000000001','f7000000-0000-0000-0000-000000000003'),
 ('f7100000-0000-0000-0000-000000000002','f7000000-0000-0000-0000-000000000005');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('f7000000-0000-0000-0000-000000000002','f7100000-0000-0000-0000-000000000001',NULL,'ORG_ADMIN'),
 ('f7000000-0000-0000-0000-000000000003','f7100000-0000-0000-0000-000000000001','TOEIC','TEACHER'),
 ('f7000000-0000-0000-0000-000000000004',NULL,NULL,'SYS_ADMIN');
INSERT INTO users(id,email) VALUES ('f7000000-0000-0000-0000-000000000006','pm-evalowner@x.test');
INSERT INTO exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,submitted_at,submit_reason) VALUES
 ('f7300000-0000-0000-0000-000000000001','f7000000-0000-0000-0000-000000000006',NULL,gen_random_uuid(),'SUBMITTED','TOEIC',2,now(),'LEARNER');
INSERT INTO ai_evaluations(attempt_id,question_id,user_id,owner_org_id,model,input_tokens,output_tokens,cost_vnd,band,result)
 VALUES ('f7300000-0000-0000-0000-000000000001',gen_random_uuid(),'f7000000-0000-0000-0000-000000000006',NULL,'gemini',1,1,10,6,'{}'::jsonb);
INSERT INTO jobs(id,type,payload,status,run_at,finished_at,attempts,last_error) VALUES (gen_random_uuid(),'ai_grade','{}','FAILED',now(),now(),3,'boom');

DO $$
BEGIN
  ASSERT has_function_privilege('e9x_app', 'progress_viewable(uuid,uuid)', 'EXECUTE') AND NOT has_function_privilege('e9x_worker', 'progress_viewable(uuid,uuid)', 'EXECUTE')
     AND has_function_privilege('e9x_app', 'monitor_ai_cost(timestamptz,timestamptz)', 'EXECUTE') AND NOT has_function_privilege('e9x_worker', 'monitor_ai_cost(timestamptz,timestamptz)', 'EXECUTE'),
     'the request role calls the functions, nobody else';
END $$;

SET ROLE e9x_app;
-- the learner themselves, no organisation
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000001',false), set_config('app.org_id','',false), set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT progress_viewable('f7000000-0000-0000-0000-000000000001', NULL), 'my own progress';
  ASSERT NOT progress_viewable('f7000000-0000-0000-0000-000000000002', NULL), 'not another person''s';
  ASSERT NOT progress_viewable('f7000000-0000-0000-0000-000000000001', 'f7100000-0000-0000-0000-000000000001'), 'the organisation form needs the organisation context and a role';
  ASSERT (SELECT count(*) FROM monitor_ai_cost(now() - interval '1 day', now() + interval '1 day')) = 0 AND (SELECT count(*) FROM monitor_failed_jobs(now() - interval '1 day', now() + interval '1 day', 10)) = 0
     AND monitor_ai_failures(now() - interval '1 day', now() + interval '1 day') = 0, 'a learner sees no cost and no job';
END $$;
-- a plain member in the organisation context
SELECT set_config('app.org_id','f7100000-0000-0000-0000-000000000001',false);
DO $$
BEGIN
  ASSERT NOT progress_viewable('f7000000-0000-0000-0000-000000000003', 'f7100000-0000-0000-0000-000000000001'), 'a member without a role reads nobody';
END $$;
-- the ORG_ADMIN of organisation A
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000002',false), set_config('app.org_id','f7100000-0000-0000-0000-000000000001',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  ASSERT progress_viewable('f7000000-0000-0000-0000-000000000001', 'f7100000-0000-0000-0000-000000000001'), 'the ORG_ADMIN reads a member';
  ASSERT NOT progress_viewable('f7000000-0000-0000-0000-000000000005', 'f7100000-0000-0000-0000-000000000001'), 'not a member of another organisation';
  ASSERT NOT progress_viewable('f7000000-0000-0000-0000-000000000001', 'f7100000-0000-0000-0000-000000000002'), 'not through another organisation''s id';
  ASSERT (SELECT count(*) FROM monitor_ai_cost(now() - interval '1 day', now() + interval '1 day')) = 0, 'an ORG_ADMIN sees no platform cost';
END $$;
-- a TEACHER of organisation A
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000003',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT progress_viewable('f7000000-0000-0000-0000-000000000001', 'f7100000-0000-0000-0000-000000000001'), 'a TEACHER reads a member';
  ASSERT NOT progress_viewable('f7000000-0000-0000-0000-000000000005', 'f7100000-0000-0000-0000-000000000001'), 'not an outsider';
END $$;
-- the SYS_ADMIN in the English9x context reads the monitor; with no flag, or inside an organisation, nothing
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000004',false), set_config('app.org_id','',false), set_config('app.org_admin','',false), set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM monitor_ai_cost(now() - interval '1 day', now() + interval '1 day')) >= 1, 'the SYS_ADMIN sees the cost';
  ASSERT (SELECT count(*) FROM monitor_failed_jobs(now() - interval '1 day', now() + interval '1 day', 10)) >= 1, 'and the failed jobs';
END $$;
SELECT set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM monitor_ai_cost(now() - interval '1 day', now() + interval '1 day')) = 0, 'the grant without the English9x context is nothing';
END $$;
RESET ROLE;
