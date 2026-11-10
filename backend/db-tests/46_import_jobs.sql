-- P2.7 Excel import (feature F11): import_jobs / import_errors / import_job_versions.
--  * Seen and written by the person who uploaded, in the context of the upload, while still an AUTHOR of the exam there: not by another author of the
--    same exam, not by a reviewer, not in another context, not once the grant is gone.
--  * Inserted only as QUEUED and as oneself. The children (errors, drafts of the lot) follow their import's visibility.
--  * The worker has NO table grant; its one door is import_job_mark_failed(): marks a QUEUED / RUNNING import FAILED with a reason, nothing else; the
--    request role cannot call it.
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS'),('TOEIC','TOEIC') ON CONFLICT DO NOTHING;
INSERT INTO users(id,email) VALUES
 ('d4600000-0000-0000-0000-000000000001','imp-author@x.test'),
 ('d4600000-0000-0000-0000-000000000002','imp-author2@x.test'),
 ('d4600000-0000-0000-0000-000000000003','imp-reviewer@x.test'),
 ('d4600000-0000-0000-0000-000000000004','imp-removed@x.test');
INSERT INTO organizations(id,name,type) VALUES
 ('a4600000-0000-0000-0000-00000000000a','Import Center A','CENTER'),
 ('b4600000-0000-0000-0000-00000000000b','Import Center B','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('a4600000-0000-0000-0000-00000000000a','d4600000-0000-0000-0000-000000000001'),
 ('a4600000-0000-0000-0000-00000000000a','d4600000-0000-0000-0000-000000000002'),
 ('a4600000-0000-0000-0000-00000000000a','d4600000-0000-0000-0000-000000000003'),
 ('b4600000-0000-0000-0000-00000000000b','d4600000-0000-0000-0000-000000000001');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('d4600000-0000-0000-0000-000000000001','a4600000-0000-0000-0000-00000000000a','TOEIC','AUTHOR'),
 ('d4600000-0000-0000-0000-000000000002','a4600000-0000-0000-0000-00000000000a','TOEIC','AUTHOR'),
 ('d4600000-0000-0000-0000-000000000003','a4600000-0000-0000-0000-00000000000a','TOEIC','REVIEWER'),
 ('d4600000-0000-0000-0000-000000000001','b4600000-0000-0000-0000-00000000000b','IELTS','AUTHOR');

CREATE FUNCTION public.t_attempt(stmt text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  EXECUTE stmt; GET DIAGNOSTICS n = ROW_COUNT;
  RETURN 'rows:' || n;
EXCEPTION WHEN OTHERS THEN RETURN 'err:' || SQLSTATE;
END $$;
GRANT EXECUTE ON FUNCTION public.t_attempt(text) TO e9x_app, e9x_worker;

-- fixtures as the owner: one RUNNING import of author 1 in org A, with an error and a finished one
INSERT INTO import_jobs(id,owner_org_id,user_id,exam_code,type_code,status,source_name) VALUES
 ('c4600000-0000-0000-0000-000000000001','a4600000-0000-0000-0000-00000000000a','d4600000-0000-0000-0000-000000000001','TOEIC','TOEIC_P5_INCOMPLETE_SENTENCE','RUNNING','a.xlsx');
INSERT INTO import_jobs(id,owner_org_id,user_id,exam_code,type_code,status,source_name,error_count,finished_at) VALUES
 ('c4600000-0000-0000-0000-000000000002','a4600000-0000-0000-0000-00000000000a','d4600000-0000-0000-0000-000000000001','TOEIC','TOEIC_P5_INCOMPLETE_SENTENCE','REJECTED','b.xlsx',1,now());
INSERT INTO import_errors(import_job_id,row_no,column_name,message) VALUES ('c4600000-0000-0000-0000-000000000002',3,'answer.slots.1','thiếu đáp án');

DO $$
BEGIN
  ASSERT (SELECT relrowsecurity FROM pg_class WHERE relname = 'import_jobs'), 'import_jobs has row level security';
  ASSERT (SELECT relrowsecurity FROM pg_class WHERE relname = 'import_errors'), 'import_errors has row level security';
  ASSERT (SELECT relrowsecurity FROM pg_class WHERE relname = 'import_job_versions'), 'import_job_versions has row level security';
  -- the state and the counters agree (as the owner: the constraints)
  ASSERT public.t_attempt('UPDATE import_jobs SET status = ''REJECTED'' WHERE id = ''c4600000-0000-0000-0000-000000000001''') = 'err:23514', 'a REJECTED import has errors and no drafts and is finished';
  ASSERT public.t_attempt('UPDATE import_jobs SET status = ''SUCCEEDED'' WHERE id = ''c4600000-0000-0000-0000-000000000001''') = 'err:23514', 'a finished import has a finish time';
  ASSERT public.t_attempt('UPDATE import_jobs SET status = ''DONE'' WHERE id = ''c4600000-0000-0000-0000-000000000001''') = 'err:23514', 'only the known states';
END $$;

-- the worker has no door into the tables
SET ROLE e9x_worker;
DO $$
BEGIN
  ASSERT NOT has_table_privilege('e9x_worker', 'import_jobs', 'SELECT') AND NOT has_table_privilege('e9x_worker', 'import_jobs', 'UPDATE')
     AND NOT has_table_privilege('e9x_worker', 'import_errors', 'SELECT') AND NOT has_table_privilege('e9x_worker', 'import_job_versions', 'SELECT'), 'the worker has no table grant';
  ASSERT public.t_attempt('SELECT * FROM import_jobs') = 'err:42501', 'the worker reads no import';
  ASSERT public.t_attempt('UPDATE import_jobs SET status = ''FAILED''') = 'err:42501', 'the worker updates no import';
END $$;
RESET ROLE;

-- requests: the uploader, in the context of the upload
SET ROLE e9x_app;
SELECT set_config('app.user_id','d4600000-0000-0000-0000-000000000001',false), set_config('app.org_id','a4600000-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM import_jobs) = 2, 'the uploader sees their two imports';
  ASSERT (SELECT count(*) FROM import_errors) = 1, 'and the errors of them';
  -- insert: as oneself, QUEUED, in the context, as an author of the exam there
  ASSERT public.t_attempt('INSERT INTO import_jobs(id,owner_org_id,user_id,exam_code,type_code,source_name) VALUES (gen_random_uuid(),''a4600000-0000-0000-0000-00000000000a'',''d4600000-0000-0000-0000-000000000001'',''TOEIC'',''TOEIC_P5_INCOMPLETE_SENTENCE'',''n.xlsx'')') = 'rows:1', 'an author inserts their own import';
  ASSERT public.t_attempt('INSERT INTO import_jobs(id,owner_org_id,user_id,exam_code,type_code,source_name) VALUES (gen_random_uuid(),''a4600000-0000-0000-0000-00000000000a'',''d4600000-0000-0000-0000-000000000002'',''TOEIC'',''TOEIC_P5_INCOMPLETE_SENTENCE'',''n.xlsx'')') = 'err:42501', 'not as somebody else';
  ASSERT public.t_attempt('INSERT INTO import_jobs(id,owner_org_id,user_id,exam_code,type_code,status,source_name) VALUES (gen_random_uuid(),''a4600000-0000-0000-0000-00000000000a'',''d4600000-0000-0000-0000-000000000001'',''TOEIC'',''TOEIC_P5_INCOMPLETE_SENTENCE'',''SUCCEEDED'',''n.xlsx'')') = 'err:42501', 'never already finished';
  ASSERT public.t_attempt('INSERT INTO import_jobs(id,owner_org_id,user_id,exam_code,type_code,source_name) VALUES (gen_random_uuid(),NULL,''d4600000-0000-0000-0000-000000000001'',''TOEIC'',''TOEIC_P5_INCOMPLETE_SENTENCE'',''n.xlsx'')') = 'err:42501', 'not in a context it is not in';
  -- update: the counters and the state, of one's own; never the owner, the user, the type
  ASSERT public.t_attempt('UPDATE import_jobs SET processed_rows = 3 WHERE id = ''c4600000-0000-0000-0000-000000000001''') = 'rows:1', 'progress';
  ASSERT public.t_attempt('UPDATE import_jobs SET user_id = ''d4600000-0000-0000-0000-000000000002'' WHERE id = ''c4600000-0000-0000-0000-000000000001''') = 'err:42501', 'the owner cannot be changed';
  ASSERT public.t_attempt('DELETE FROM import_jobs') = 'err:42501', 'imports are never deleted by a request';
  -- no import without the job being the person's: errors and versions follow the import
  ASSERT public.t_attempt('INSERT INTO import_errors(import_job_id,row_no,column_name,message) VALUES (''c4600000-0000-0000-0000-000000000001'',2,NULL,''x'')') = 'rows:1', 'an error of one''s own import';
END $$;
-- another author of the same exam, in the same organisation: not theirs
SELECT set_config('app.user_id','d4600000-0000-0000-0000-000000000002',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM import_jobs) = 0, 'a colleague sees none';
  ASSERT (SELECT count(*) FROM import_errors) = 0, 'nor their errors';
  ASSERT public.t_attempt('UPDATE import_jobs SET processed_rows = 9') = 'rows:0', 'nor changes them';
  ASSERT public.t_attempt('INSERT INTO import_errors(import_job_id,row_no,column_name,message) VALUES (''c4600000-0000-0000-0000-000000000001'',2,NULL,''x'')') = 'err:42501', 'nor adds errors to them';
END $$;
-- a reviewer is no uploader
SELECT set_config('app.user_id','d4600000-0000-0000-0000-000000000003',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM import_jobs) = 0, 'a reviewer sees none';
END $$;
-- the uploader in their other context (org B, an IELTS author there): not the org A imports
SELECT set_config('app.user_id','d4600000-0000-0000-0000-000000000001',false), set_config('app.org_id','b4600000-0000-0000-0000-00000000000b',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM import_jobs) = 0, 'in another context the imports of org A are not there';
END $$;
RESET ROLE;

-- an uploader who lost the grant sees nothing of it any more (the job then fails through the definer, below)
DELETE FROM role_grants WHERE user_id = 'd4600000-0000-0000-0000-000000000001' AND org_id = 'a4600000-0000-0000-0000-00000000000a';
SET ROLE e9x_app;
SELECT set_config('app.user_id','d4600000-0000-0000-0000-000000000001',false), set_config('app.org_id','a4600000-0000-0000-0000-00000000000a',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM import_jobs) = 0, 'without the AUTHOR grant the imports are gone from view';
END $$;
RESET ROLE;

-- the definer: narrow, pinned, for the worker only
DO $$
DECLARE f pg_proc%ROWTYPE;
BEGIN
  SELECT * INTO f FROM pg_proc WHERE proname = 'import_job_mark_failed';
  ASSERT f.prosecdef, 'import_job_mark_failed is SECURITY DEFINER';
  ASSERT f.proconfig @> ARRAY['search_path=pg_catalog, public, pg_temp'], 'its search_path is pinned';
  ASSERT has_function_privilege('e9x_worker', f.oid, 'EXECUTE'), 'the worker may call it';
  ASSERT NOT has_function_privilege('e9x_app', f.oid, 'EXECUTE'), 'the request role may not';
  ASSERT NOT has_function_privilege('public', f.oid, 'EXECUTE'), 'nobody else may';
END $$;
SET ROLE e9x_worker;
DO $$
BEGIN
  -- no user binding at all: it works on the id alone
  ASSERT (SELECT import_job_mark_failed('c4600000-0000-0000-0000-000000000001', 'the import could not be run')), 'marks a RUNNING import FAILED';
  ASSERT NOT (SELECT import_job_mark_failed('c4600000-0000-0000-0000-000000000001', 'again')), 'only once: a finished import does not move';
  ASSERT NOT (SELECT import_job_mark_failed('c4600000-0000-0000-0000-000000000002', 'late')), 'a REJECTED import does not become FAILED';
  ASSERT NOT (SELECT import_job_mark_failed(gen_random_uuid(), 'x')), 'an unknown import is just false';
END $$;
RESET ROLE;
DO $$
BEGIN
  ASSERT (SELECT status || '|' || failure_reason || '|' || (finished_at IS NOT NULL) FROM import_jobs WHERE id = 'c4600000-0000-0000-0000-000000000001') = 'FAILED|the import could not be run|true', 'the reason and the finish time are recorded';
  ASSERT (SELECT status FROM import_jobs WHERE id = 'c4600000-0000-0000-0000-000000000002') = 'REJECTED', 'the rejected one is untouched';
  ASSERT (SELECT length(failure_reason) FROM (SELECT left('x', 1) AS failure_reason) s) = 1, 'sanity';
END $$;
SET ROLE e9x_app;
DO $$
BEGIN
  ASSERT public.t_attempt('SELECT import_job_mark_failed(gen_random_uuid(), ''x'')') = 'err:42501', 'the request role cannot call the worker''s door';
END $$;
RESET ROLE;
