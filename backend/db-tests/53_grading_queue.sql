-- P4.3 teacher grading queue (V202611150001): grading_queue_items, teacher_reviews, org_grading_sides, the official announcement and the narrow definer functions.
--  * A TEACHER sees the OPEN items of the SIDE they are a teacher of, in the context of that side (English9x teachers in the English9x context, a centre's teachers in the
--    centre's context), and the items they hold; an item somebody else holds is hidden. A centre's queue is invisible to English9x teachers and to other centres' teachers
--    (centerQueueOnlyVisibleToCenterTeachers), and English9x's queue is invisible to the centre's teachers.
--  * A claim is optimistic (version) and only the claimer's; the administrator of a side hands an item to a teacher OF THAT SIDE; a graded item is final.
--  * The grader (learner's context) creates the item with the side the database computes and marks it DONE; it cannot claim. A teacher review is written only for an item
--    the named teacher holds; the AI band and the deviation are not readable by the request role.
--  * The definer functions answer only what they are for (search_path pinned, PUBLIC revoked).
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS') ON CONFLICT DO NOTHING;
INSERT INTO exams(code,name) VALUES ('TOEIC','TOEIC') ON CONFLICT DO NOTHING;
INSERT INTO users(id,email) VALUES
 ('f7000000-0000-0000-0000-000000000001','gq-learner-a@x.test'),   -- learner in centre A
 ('f7000000-0000-0000-0000-000000000002','gq-learner-p@x.test'),   -- B2C learner
 ('f7000000-0000-0000-0000-000000000003','gq-teacher-e@x.test'),   -- English9x teacher of IELTS
 ('f7000000-0000-0000-0000-000000000004','gq-teacher-a@x.test'),   -- centre A teacher of IELTS
 ('f7000000-0000-0000-0000-000000000005','gq-teacher-a2@x.test'),  -- another centre A teacher of IELTS
 ('f7000000-0000-0000-0000-000000000006','gq-teacher-b@x.test'),   -- centre B teacher of IELTS
 ('f7000000-0000-0000-0000-000000000007','gq-teacher-toeic@x.test'), -- English9x teacher of TOEIC only
 ('f7000000-0000-0000-0000-000000000008','gq-sysadmin@x.test'),
 ('f7000000-0000-0000-0000-000000000009','gq-admin-a@x.test'),     -- ORG_ADMIN of centre A
 ('f7000000-0000-0000-0000-00000000000a','gq-not-teacher@x.test'); -- a member of centre A with no TEACHER grant
INSERT INTO organizations(id,name,type) VALUES
 ('f7aaaaaa-0000-0000-0000-00000000000a','Queue Center A','CENTER'),
 ('f7bbbbbb-0000-0000-0000-00000000000b','Queue Center B','CENTER'),
 ('f7cccccc-0000-0000-0000-00000000000c','Queue Center C (English9x grades)','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('f7aaaaaa-0000-0000-0000-00000000000a','f7000000-0000-0000-0000-000000000001'),
 ('f7aaaaaa-0000-0000-0000-00000000000a','f7000000-0000-0000-0000-000000000004'),
 ('f7aaaaaa-0000-0000-0000-00000000000a','f7000000-0000-0000-0000-000000000005'),
 ('f7aaaaaa-0000-0000-0000-00000000000a','f7000000-0000-0000-0000-000000000009'),
 ('f7aaaaaa-0000-0000-0000-00000000000a','f7000000-0000-0000-0000-00000000000a'),
 ('f7bbbbbb-0000-0000-0000-00000000000b','f7000000-0000-0000-0000-000000000006');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('f7000000-0000-0000-0000-000000000003',NULL,'IELTS','TEACHER'),
 ('f7000000-0000-0000-0000-000000000004','f7aaaaaa-0000-0000-0000-00000000000a','IELTS','TEACHER'),
 ('f7000000-0000-0000-0000-000000000005','f7aaaaaa-0000-0000-0000-00000000000a','IELTS','TEACHER'),
 ('f7000000-0000-0000-0000-000000000006','f7bbbbbb-0000-0000-0000-00000000000b','IELTS','TEACHER'),
 ('f7000000-0000-0000-0000-000000000007',NULL,'TOEIC','TEACHER'),
 ('f7000000-0000-0000-0000-000000000008',NULL,NULL,'SYS_ADMIN'),
 ('f7000000-0000-0000-0000-000000000009','f7aaaaaa-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN');
-- centre A grades with its own teachers; centre C chose English9x (no row would mean the same); B has no row
INSERT INTO org_grading_sides(org_id,side) VALUES
 ('f7aaaaaa-0000-0000-0000-00000000000a','CENTER'),
 ('f7cccccc-0000-0000-0000-00000000000c','ENGLISH9X');
INSERT INTO org_memberships(org_id,user_id) VALUES ('f7cccccc-0000-0000-0000-00000000000c','f7000000-0000-0000-0000-000000000002');

-- Attempts: 1 = learner A in centre A (own teachers), 2 = B2C learner (English9x), 3 = learner A in centre A, IN_PROGRESS, 4 = learner P in centre C (English9x grades)
INSERT INTO exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,submitted_at,interrupted,submit_reason) VALUES
 ('f7300000-0000-0000-0000-000000000001','f7000000-0000-0000-0000-000000000001','f7aaaaaa-0000-0000-0000-00000000000a',gen_random_uuid(),'SUBMITTED','IELTS',2,now(),false,'LEARNER'),
 ('f7300000-0000-0000-0000-000000000002','f7000000-0000-0000-0000-000000000002',NULL,gen_random_uuid(),'SUBMITTED','IELTS',2,now(),false,'LEARNER'),
 ('f7300000-0000-0000-0000-000000000004','f7000000-0000-0000-0000-000000000002','f7cccccc-0000-0000-0000-00000000000c',gen_random_uuid(),'SUBMITTED','IELTS',2,now(),false,'LEARNER');
INSERT INTO exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,deadline_at) VALUES
 ('f7300000-0000-0000-0000-000000000003','f7000000-0000-0000-0000-000000000001','f7aaaaaa-0000-0000-0000-00000000000a',gen_random_uuid(),'IN_PROGRESS','IELTS',2,now()+interval '1 hour');
INSERT INTO attempt_gradings(attempt_id,user_id,owner_org_id,interrupted) VALUES
 ('f7300000-0000-0000-0000-000000000001','f7000000-0000-0000-0000-000000000001','f7aaaaaa-0000-0000-0000-00000000000a',false);

CREATE FUNCTION public.t_gq(stmt text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  EXECUTE stmt; GET DIAGNOSTICS n = ROW_COUNT;
  RETURN 'rows:' || n;
EXCEPTION WHEN OTHERS THEN RETURN 'err:' || SQLSTATE;
END $$;
GRANT EXECUTE ON FUNCTION public.t_gq(text) TO e9x_app, e9x_grader, e9x_worker;

-- ---- the grant list --------------------------------------------------------------------------------------------------------------------
DO $$
BEGIN
  ASSERT (SELECT bool_and(relrowsecurity) FROM pg_class WHERE relname IN ('grading_queue_items','teacher_reviews','org_grading_sides')), 'row level security on the three tables';
  ASSERT NOT has_table_privilege('e9x_app', 'grading_queue_items', 'INSERT') AND NOT has_table_privilege('e9x_app', 'grading_queue_items', 'DELETE')
     AND NOT has_table_privilege('e9x_grader', 'grading_queue_items', 'DELETE'), 'nobody inserts as the request role or deletes';
  ASSERT NOT has_table_privilege('e9x_app', 'teacher_reviews', 'INSERT') AND NOT has_table_privilege('e9x_app', 'teacher_reviews', 'UPDATE')
     AND NOT has_table_privilege('e9x_grader', 'teacher_reviews', 'UPDATE'), 'a review is written by the grader and never changed';
  ASSERT NOT has_column_privilege('e9x_app', 'teacher_reviews', 'ai_band', 'SELECT') AND NOT has_column_privilege('e9x_app', 'teacher_reviews', 'deviation', 'SELECT')
     AND has_column_privilege('e9x_app', 'teacher_reviews', 'band', 'SELECT'), 'the request role does not read the AI band or the deviation';
  ASSERT NOT has_table_privilege('e9x_app', 'org_grading_sides', 'INSERT') AND NOT has_table_privilege('e9x_app', 'org_grading_sides', 'UPDATE'), 'the grading side is not written here (P6.5)';
  ASSERT has_function_privilege('e9x_grader', 'grading_queue_side(uuid)', 'EXECUTE') AND NOT has_function_privilege('e9x_app', 'grading_queue_side(uuid)', 'EXECUTE'),
     'the side function is the grader''s';
  ASSERT has_function_privilege('e9x_app', 'grading_queue_teachers(uuid)', 'EXECUTE') AND has_function_privilege('e9x_app', 'scores_has_official(uuid,uuid)', 'EXECUTE')
     AND NOT has_function_privilege('e9x_grader', 'grading_queue_teachers(uuid)', 'EXECUTE'), 'the notification functions are the request role''s';
  ASSERT has_function_privilege('e9x_worker', 'grading_queue_release_expired(timestamptz)', 'EXECUTE') AND has_function_privilege('e9x_worker', 'grading_queue_claims_due(timestamptz,timestamptz)', 'EXECUTE')
     AND NOT has_function_privilege('e9x_app', 'grading_queue_release_expired(timestamptz)', 'EXECUTE') AND NOT has_function_privilege('e9x_app', 'grading_queue_claims_due(timestamptz,timestamptz)', 'EXECUTE'),
     'the two doors are the worker''s alone';
  ASSERT (SELECT bool_and(prosecdef AND proconfig::text LIKE '%search_path=pg_catalog, public, pg_temp%') FROM pg_proc
           WHERE proname IN ('grading_queue_side','grading_queue_is_teacher','grading_queue_teachers','scores_has_official','grading_queue_release_expired','grading_queue_claims_due')),
     'every definer function pins its search_path';
END $$;

-- ---- the GRADER creates the items, in the learner's context -----------------------------------------------------------------------------------
SET ROLE e9x_grader;
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000001',false), set_config('app.org_id','f7aaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT grading_queue_side('f7aaaaaa-0000-0000-0000-00000000000a') = 'f7aaaaaa-0000-0000-0000-00000000000a', 'centre A chose its own teachers';
  ASSERT grading_queue_side('f7bbbbbb-0000-0000-0000-00000000000b') IS NULL AND grading_queue_side('f7cccccc-0000-0000-0000-00000000000c') IS NULL AND grading_queue_side(NULL) IS NULL,
     'a centre with no row, one that chose English9x and a personal context are graded by English9x';
  ASSERT public.t_gq('INSERT INTO grading_queue_items(attempt_id,user_id,owner_org_id,grading_org_id,exam_code,reason) VALUES (''f7300000-0000-0000-0000-000000000001'',''f7000000-0000-0000-0000-000000000001'',''f7aaaaaa-0000-0000-0000-00000000000a'',NULL,''IELTS'',''POLICY'')') = 'err:42501',
     'the side is the database''s: English9x cannot be named for a centre that grades itself';
  ASSERT public.t_gq('INSERT INTO grading_queue_items(attempt_id,user_id,owner_org_id,grading_org_id,exam_code,reason) VALUES (''f7300000-0000-0000-0000-000000000001'',''f7000000-0000-0000-0000-000000000001'',''f7aaaaaa-0000-0000-0000-00000000000a'',''f7bbbbbb-0000-0000-0000-00000000000b'',''IELTS'',''POLICY'')') = 'err:42501',
     'nor another centre';
  ASSERT public.t_gq('INSERT INTO grading_queue_items(attempt_id,user_id,owner_org_id,grading_org_id,exam_code,reason) VALUES (''f7300000-0000-0000-0000-000000000003'',''f7000000-0000-0000-0000-000000000001'',''f7aaaaaa-0000-0000-0000-00000000000a'',''f7aaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''POLICY'')') = 'err:42501',
     'not for an attempt in progress';
  ASSERT public.t_gq('INSERT INTO grading_queue_items(attempt_id,user_id,owner_org_id,grading_org_id,exam_code,reason) VALUES (''f7300000-0000-0000-0000-000000000002'',''f7000000-0000-0000-0000-000000000002'',NULL,NULL,''IELTS'',''POLICY'')') = 'err:42501',
     'not for the attempt of another learner';
  ASSERT public.t_gq('INSERT INTO grading_queue_items(attempt_id,user_id,owner_org_id,grading_org_id,exam_code,reason) VALUES (''f7300000-0000-0000-0000-000000000001'',''f7000000-0000-0000-0000-000000000001'',''f7aaaaaa-0000-0000-0000-00000000000a'',''f7aaaaaa-0000-0000-0000-00000000000a'',''TOEIC'',''POLICY'')') = 'err:42501',
     'the exam is the attempt''s';
  ASSERT public.t_gq('INSERT INTO grading_queue_items(attempt_id,user_id,owner_org_id,grading_org_id,exam_code,reason) VALUES (''f7300000-0000-0000-0000-000000000001'',''f7000000-0000-0000-0000-000000000001'',''f7aaaaaa-0000-0000-0000-00000000000a'',''f7aaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''POLICY'')') = 'rows:1',
     'the item of the centre''s own attempt, on the centre''s side';
  ASSERT public.t_gq('INSERT INTO grading_queue_items(attempt_id,user_id,owner_org_id,grading_org_id,exam_code,reason) VALUES (''f7300000-0000-0000-0000-000000000001'',''f7000000-0000-0000-0000-000000000001'',''f7aaaaaa-0000-0000-0000-00000000000a'',''f7aaaaaa-0000-0000-0000-00000000000a'',''IELTS'',''POLICY'')') = 'err:23505',
     'one item per attempt';
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''CLAIMED'', claimed_by = ''f7000000-0000-0000-0000-000000000004'', claimed_at = now(), claim_due_at = now(), version = version + 1') = 'err:42501',
     'the grader does not claim';
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''DONE'', graded_at = now(), version = version + 1') = 'rows:0', 'and cannot finish an item nobody holds';
  ASSERT public.t_gq('DELETE FROM grading_queue_items') = 'err:42501', 'never deleted';
  ASSERT (SELECT count(*) FROM grading_queue_items) = 1, 'it reads back its own context';
END $$;
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000002',false), set_config('app.org_id','',false);
DO $$
BEGIN
  ASSERT public.t_gq('INSERT INTO grading_queue_items(attempt_id,user_id,owner_org_id,grading_org_id,exam_code,reason) VALUES (''f7300000-0000-0000-0000-000000000002'',''f7000000-0000-0000-0000-000000000002'',NULL,NULL,''IELTS'',''AI_FAILED'')') = 'rows:1',
     'a B2C learner''s item is English9x''s';
END $$;
SELECT set_config('app.org_id','f7cccccc-0000-0000-0000-00000000000c',false);
DO $$
BEGIN
  ASSERT public.t_gq('INSERT INTO grading_queue_items(attempt_id,user_id,owner_org_id,grading_org_id,exam_code,reason) VALUES (''f7300000-0000-0000-0000-000000000004'',''f7000000-0000-0000-0000-000000000002'',''f7cccccc-0000-0000-0000-00000000000c'',NULL,''IELTS'',''POLICY'')') = 'rows:1',
     'a learner of a centre that chose English9x: English9x''s queue';
END $$;
RESET ROLE;
-- the grader may not name the id (it is generated); the fixtures below refer to fixed ones
ALTER TABLE grading_queue_items DISABLE TRIGGER grading_queue_items_guard;
UPDATE grading_queue_items SET id = 'f7400000-0000-0000-0000-000000000001' WHERE attempt_id = 'f7300000-0000-0000-0000-000000000001';
UPDATE grading_queue_items SET id = 'f7400000-0000-0000-0000-000000000002' WHERE attempt_id = 'f7300000-0000-0000-0000-000000000002';
UPDATE grading_queue_items SET id = 'f7400000-0000-0000-0000-000000000004' WHERE attempt_id = 'f7300000-0000-0000-0000-000000000004';
ALTER TABLE grading_queue_items ENABLE TRIGGER grading_queue_items_guard;

-- ---- who sees the queue (request role) --------------------------------------------------------------------------------------------------------
-- items: 1 = centre A's queue, 2 = English9x's (B2C), 4 = English9x's (a learner of centre C)
SET ROLE e9x_app;
-- a centre A teacher, in centre A's context
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000004',false), set_config('app.org_id','f7aaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(id) FROM grading_queue_items) = ARRAY['f7400000-0000-0000-0000-000000000001'::uuid], 'a centre teacher sees the centre''s queue and nothing of English9x''s';
END $$;
-- the same teacher in the English9x context or in another organisation's: nothing (the context must BE the side)
SELECT set_config('app.org_id','',false), set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM grading_queue_items) = 0, 'a centre teacher, in the English9x context, sees no queue';
END $$;
SELECT set_config('app.org_id','f7bbbbbb-0000-0000-0000-00000000000b',false), set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM grading_queue_items) = 0, 'nor in another centre''s context';
END $$;
-- an English9x teacher of IELTS, in the English9x context: English9x's queue only
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000003',false), set_config('app.org_id','',false), set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(id ORDER BY id) FROM grading_queue_items) = ARRAY['f7400000-0000-0000-0000-000000000002'::uuid,'f7400000-0000-0000-0000-000000000004'::uuid],
     'English9x teachers see English9x''s queue (B2C and a centre that chose English9x), never a centre''s own';
  ASSERT (SELECT count(*) FROM grading_queue_items WHERE id = 'f7400000-0000-0000-0000-000000000001') = 0, 'centerQueueOnlyVisibleToCenterTeachers: not centre A''s item';
END $$;
-- an English9x teacher of TOEIC: no IELTS item
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000007',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM grading_queue_items) = 0, 'a teacher of another exam sees nothing';
END $$;
-- centre B's teacher in centre B's context; a member of A with no TEACHER grant in A's; the learner
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000006',false), set_config('app.org_id','f7bbbbbb-0000-0000-0000-00000000000b',false), set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM grading_queue_items) = 0, 'another centre''s teacher sees nothing of centre A';
END $$;
SELECT set_config('app.user_id','f7000000-0000-0000-0000-00000000000a',false), set_config('app.org_id','f7aaaaaa-0000-0000-0000-00000000000a',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM grading_queue_items) = 0, 'a member who is not a teacher sees nothing';
END $$;
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000001',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM grading_queue_items) = 0, 'the learner does not see the queue of their own attempt';
END $$;
-- the administrators: ORG_ADMIN of centre A in A's context sees A's queue; SYS_ADMIN in the English9x context sees English9x's
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000009',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(id) FROM grading_queue_items) = ARRAY['f7400000-0000-0000-0000-000000000001'::uuid], 'the administrator of centre A sees centre A''s queue';
END $$;
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000008',false), set_config('app.org_id','',false), set_config('app.org_admin','',false), set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(id ORDER BY id) FROM grading_queue_items) = ARRAY['f7400000-0000-0000-0000-000000000002'::uuid,'f7400000-0000-0000-0000-000000000004'::uuid], 'SYS_ADMIN sees English9x''s queue, not a centre''s';
  ASSERT (SELECT count(*) FROM org_grading_sides) = 2, 'SYS_ADMIN reads the grading sides';
END $$;
RESET ROLE;

-- ---- claim: optimistic, only the claimer's, hidden from the others ------------------------------------------------------------------------------
SET ROLE e9x_app;
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000004',false), set_config('app.org_id','f7aaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''CLAIMED'', claimed_by = ''f7000000-0000-0000-0000-000000000005'', claimed_at = now(), claim_due_at = now() + interval ''1 day'', version = version + 1 WHERE id = ''f7400000-0000-0000-0000-000000000001''') = 'err:42501',
     'a teacher cannot claim in somebody else''s name';
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''DONE'', version = version + 1 WHERE id = ''f7400000-0000-0000-0000-000000000001''') = 'err:42501', 'a teacher cannot finish an item without grading it (OPEN goes to CLAIMED only)';
  ASSERT public.t_gq('UPDATE grading_queue_items SET graded_at = now(), version = version + 1 WHERE id = ''f7400000-0000-0000-0000-000000000001''') = 'err:42501', 'nor stamp the grading time (no grant)';
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''CLAIMED'', claimed_by = ''f7000000-0000-0000-0000-000000000004'', claimed_at = now(), claim_due_at = now() + interval ''1 day'' WHERE id = ''f7400000-0000-0000-0000-000000000001''') = 'err:42501',
     'every change raises the version by one';
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''CLAIMED'', claimed_by = ''f7000000-0000-0000-0000-000000000004'', claimed_at = now(), claim_due_at = now() + interval ''1 day'', version = version + 1 WHERE id = ''f7400000-0000-0000-0000-000000000001'' AND version = 5') = 'rows:0',
     'a stale version claims nothing (the optimistic lock)';
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''CLAIMED'', claimed_by = ''f7000000-0000-0000-0000-000000000004'', claimed_at = now(), claim_due_at = now() + interval ''1 day'', version = version + 1 WHERE id = ''f7400000-0000-0000-0000-000000000001'' AND version = 0') = 'rows:1',
     'the first claim wins';
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''CLAIMED'', claimed_by = ''f7000000-0000-0000-0000-000000000004'', claimed_at = now(), claim_due_at = now() + interval ''1 day'', version = version + 1 WHERE id = ''f7400000-0000-0000-0000-000000000001'' AND version = 0') = 'rows:0',
     'a second claim of the same version claims nothing';
  ASSERT (SELECT count(*) FROM grading_queue_items WHERE claimed_by = 'f7000000-0000-0000-0000-000000000004') = 1, 'the claimer still sees the item';
END $$;
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000005',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM grading_queue_items) = 0, 'claimedHiddenFromOthers: the other teacher of the centre no longer sees it';
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''CLAIMED'', claimed_by = ''f7000000-0000-0000-0000-000000000005'', claimed_at = now(), claim_due_at = now() + interval ''1 day'', version = version + 1') = 'rows:0',
     'and cannot take it';
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''OPEN'', claimed_by = NULL, claimed_at = NULL, claim_due_at = NULL, version = version + 1') = 'rows:0', 'nor return it';
END $$;
-- the holder returns it; it is OPEN again for both
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000004',false);
DO $$
BEGIN
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''OPEN'', claimed_by = NULL, claimed_at = NULL, claim_due_at = NULL, version = version + 1 WHERE id = ''f7400000-0000-0000-0000-000000000001''') = 'rows:1', 'the holder returns the item';
  ASSERT public.t_gq('UPDATE grading_queue_items SET claimed_by = ''f7000000-0000-0000-0000-000000000004'' WHERE id = ''f7400000-0000-0000-0000-000000000001''') = 'err:42501', 'a change that does not raise the version is refused';
  ASSERT public.t_gq('UPDATE grading_queue_items SET exam_code = ''TOEIC'', version = version + 1') = 'err:42501', 'the exam of an item never changes (no grant)';
  ASSERT public.t_gq('DELETE FROM grading_queue_items') = 'err:42501', 'never deleted';
END $$;
RESET ROLE;

-- ---- the administrator hands the item to a teacher of THE SIDE ---------------------------------------------------------------------------------
SET ROLE e9x_app;
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000009',false), set_config('app.org_id','f7aaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  ASSERT grading_queue_is_teacher('IELTS','f7aaaaaa-0000-0000-0000-00000000000a','f7000000-0000-0000-0000-000000000005'), 'a teacher of centre A';
  ASSERT NOT grading_queue_is_teacher('IELTS','f7aaaaaa-0000-0000-0000-00000000000a','f7000000-0000-0000-0000-000000000003'), 'an English9x teacher is not one of centre A''s';
  ASSERT NOT grading_queue_is_teacher('IELTS','f7aaaaaa-0000-0000-0000-00000000000a','f7000000-0000-0000-0000-000000000006'), 'nor is centre B''s';
  ASSERT NOT grading_queue_is_teacher('IELTS','f7aaaaaa-0000-0000-0000-00000000000a','f7000000-0000-0000-0000-00000000000a'), 'nor a member without the grant';
  ASSERT NOT grading_queue_is_teacher('IELTS',NULL,'f7000000-0000-0000-0000-000000000003'), 'the administrator of centre A cannot ask about English9x''s side';
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''CLAIMED'', claimed_by = ''f7000000-0000-0000-0000-000000000003'', claimed_at = now(), claim_due_at = now() + interval ''1 day'', version = version + 1 WHERE id = ''f7400000-0000-0000-0000-000000000001''') = 'err:42501',
     'an English9x teacher cannot be given a centre''s item';
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''CLAIMED'', claimed_by = ''f7000000-0000-0000-0000-000000000005'', claimed_at = now(), claim_due_at = now() + interval ''1 day'', version = version + 1 WHERE id = ''f7400000-0000-0000-0000-000000000001''') = 'rows:1',
     'a teacher of the centre can';
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''CLAIMED'', claimed_by = ''f7000000-0000-0000-0000-000000000004'', claimed_at = now(), claim_due_at = now() + interval ''1 day'', version = version + 1 WHERE id = ''f7400000-0000-0000-0000-000000000001''') = 'rows:1',
     'and again, from one teacher to another';
END $$;
-- the administrator of centre A does not touch English9x's queue
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000008',false), set_config('app.org_id','',false), set_config('app.org_admin','',false), set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''CLAIMED'', claimed_by = ''f7000000-0000-0000-0000-000000000004'', claimed_at = now(), claim_due_at = now() + interval ''1 day'', version = version + 1 WHERE id = ''f7400000-0000-0000-0000-000000000001''') = 'rows:0',
     'SYS_ADMIN in the English9x context does not see centre A''s item to hand it over';
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''CLAIMED'', claimed_by = ''f7000000-0000-0000-0000-000000000003'', claimed_at = now(), claim_due_at = now() + interval ''1 day'', version = version + 1 WHERE id = ''f7400000-0000-0000-0000-000000000002''') = 'rows:1',
     'SYS_ADMIN hands English9x''s item to an English9x teacher';
END $$;
RESET ROLE;

-- ---- the review: written by the grader, for the teacher who holds the item --------------------------------------------------------------------
-- item 1 is held by teacher A (f7..04), item 2 by the English9x teacher (f7..03)
SET ROLE e9x_grader;
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000001',false), set_config('app.org_id','f7aaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT public.t_gq('INSERT INTO teacher_reviews(queue_item_id,attempt_id,user_id,owner_org_id,skill,teacher_id,criteria,comment,band,ai_band,deviation) VALUES (''f7400000-0000-0000-0000-000000000001'',''f7300000-0000-0000-0000-000000000001'',''f7000000-0000-0000-0000-000000000001'',''f7aaaaaa-0000-0000-0000-00000000000a'',''WRITING'',''f7000000-0000-0000-0000-000000000005'',''{"TA":7}'',''x'',7,6.5,0.5)') = 'err:42501',
     'only for the teacher who holds the item';
  ASSERT public.t_gq('INSERT INTO teacher_reviews(queue_item_id,attempt_id,user_id,owner_org_id,skill,teacher_id,criteria,comment,band,ai_band,deviation) VALUES (''f7400000-0000-0000-0000-000000000001'',''f7300000-0000-0000-0000-000000000001'',''f7000000-0000-0000-0000-000000000001'',''f7aaaaaa-0000-0000-0000-00000000000a'',''WRITING'',''f7000000-0000-0000-0000-000000000004'',''{"TA":7}'',''x'',7,6.5,0.4)') = 'err:23514',
     'the deviation is teacher minus AI';
  ASSERT public.t_gq('INSERT INTO teacher_reviews(queue_item_id,attempt_id,user_id,owner_org_id,skill,teacher_id,criteria,comment,band,ai_band,deviation) VALUES (''f7400000-0000-0000-0000-000000000002'',''f7300000-0000-0000-0000-000000000002'',''f7000000-0000-0000-0000-000000000002'',NULL,''WRITING'',''f7000000-0000-0000-0000-000000000003'',''{}'',''x'',7,NULL,NULL)') = 'err:42501',
     'not for another learner''s item (the context is another person)';
  ASSERT public.t_gq('INSERT INTO teacher_reviews(queue_item_id,attempt_id,user_id,owner_org_id,skill,teacher_id,criteria,comment,band,ai_band,deviation) VALUES (''f7400000-0000-0000-0000-000000000001'',''f7300000-0000-0000-0000-000000000001'',''f7000000-0000-0000-0000-000000000001'',''f7aaaaaa-0000-0000-0000-00000000000a'',''WRITING'',''f7000000-0000-0000-0000-000000000004'',''{"TA":7}'',''Good.'',7,6.5,0.5)') = 'rows:1',
     'the review of the holder';
  ASSERT public.t_gq('INSERT INTO teacher_reviews(queue_item_id,attempt_id,user_id,owner_org_id,skill,teacher_id,criteria,comment,band,ai_band,deviation) VALUES (''f7400000-0000-0000-0000-000000000001'',''f7300000-0000-0000-0000-000000000001'',''f7000000-0000-0000-0000-000000000001'',''f7aaaaaa-0000-0000-0000-00000000000a'',''WRITING'',''f7000000-0000-0000-0000-000000000004'',''{"TA":8}'',''Again'',8,NULL,NULL)') = 'err:23505',
     'one review per skill';
  ASSERT public.t_gq('UPDATE teacher_reviews SET band = 9') = 'err:42501' AND public.t_gq('DELETE FROM teacher_reviews') = 'err:42501', 'a review is final';
  -- the teacher's score is OFFICIAL, beside a PRELIMINARY AI one
  ASSERT public.t_gq('INSERT INTO scores(attempt_id,user_id,owner_org_id,skill,kind,source,raw,raw_max,scaled) VALUES (''f7300000-0000-0000-0000-000000000001'',''f7000000-0000-0000-0000-000000000001'',''f7aaaaaa-0000-0000-0000-00000000000a'',''WRITING'',''PRELIMINARY'',''AI'',6.5,NULL,6.5)') = 'rows:1', 'the AI''s preliminary score';
  ASSERT public.t_gq('INSERT INTO scores(attempt_id,user_id,owner_org_id,skill,kind,source,raw,raw_max,scaled) VALUES (''f7300000-0000-0000-0000-000000000001'',''f7000000-0000-0000-0000-000000000001'',''f7aaaaaa-0000-0000-0000-00000000000a'',''WRITING'',''OFFICIAL'',''TEACHER'',7,NULL,7)') = 'rows:1', 'and the teacher''s official one beside it';
  -- finishing: DONE only from CLAIMED, the version +1
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''DONE'', graded_at = now(), claimed_by = ''f7000000-0000-0000-0000-000000000005'', version = version + 1') = 'err:42501', 'the grader cannot change the holder';
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''DONE'', graded_at = now(), version = version + 2') = 'err:42501', 'the version moves by one';
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''DONE'', graded_at = now(), version = version + 1') = 'rows:1', 'the grader finishes the item';
  ASSERT public.t_gq('UPDATE grading_queue_items SET state = ''CLAIMED'', version = version + 1') = 'rows:0', 'DONE is final (no policy row is visible to the update)';
  -- the announcement times
  ASSERT public.t_gq('UPDATE attempt_gradings SET announced_at = now()') = 'rows:1', 'the preliminary announcement';
  ASSERT public.t_gq('UPDATE attempt_gradings SET announced_at = now()') = 'err:42501', 'once';
  ASSERT public.t_gq('UPDATE attempt_gradings SET official_announced_at = now()') = 'rows:1', 'the official announcement, once, after it';
  ASSERT public.t_gq('UPDATE attempt_gradings SET official_announced_at = now()') = 'rows:0', 'and then both are final (no policy row is visible)';
END $$;
RESET ROLE;

-- ---- what the request role reads of a review ------------------------------------------------------------------------------------------------
SET ROLE e9x_app;
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000001',false), set_config('app.org_id','f7aaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM (SELECT band, comment, criteria FROM teacher_reviews) r) = 1, 'the learner reads their review';
  ASSERT public.t_gq('SELECT ai_band FROM teacher_reviews') = 'err:42501' AND public.t_gq('SELECT deviation FROM teacher_reviews') = 'err:42501', 'but not the AI band or the deviation';
END $$;
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000004',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM (SELECT band FROM teacher_reviews) r) = 1, 'the teacher reads their own review';
END $$;
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000005',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM (SELECT band FROM teacher_reviews) r) = 0, 'another teacher does not';
END $$;
RESET ROLE;

-- ---- the definer functions ----------------------------------------------------------------------------------------------------------------------
SET ROLE e9x_app;
SELECT set_config('app.user_id','',false), set_config('app.org_id','',false), set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  -- item 3 is a fresh OPEN item for the teachers of English9x (made by the owner here)
  ASSERT (SELECT array_agg(r_user_id ORDER BY r_user_id) FROM grading_queue_teachers('f7400000-0000-0000-0000-000000000004'))
         = ARRAY['f7000000-0000-0000-0000-000000000003'::uuid], 'the teachers to tell: those of the exam on the item''s side (not TOEIC''s, not a centre''s)';
  ASSERT (SELECT count(*) FROM grading_queue_teachers('f7400000-0000-0000-0000-000000000001')) = 0 AND (SELECT count(*) FROM grading_queue_teachers('f7400000-0000-0000-0000-000000000002')) = 0,
     'nobody is told about an item that is not OPEN';
  ASSERT scores_has_official('f7300000-0000-0000-0000-000000000001','f7000000-0000-0000-0000-000000000001') AND NOT scores_has_official('f7300000-0000-0000-0000-000000000001','f7000000-0000-0000-0000-000000000002')
     AND NOT scores_has_official('f7300000-0000-0000-0000-000000000002','f7000000-0000-0000-0000-000000000002'), 'official score: of that person''s attempt only';
END $$;
SELECT set_config('app.user_id','f7000000-0000-0000-0000-000000000003',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM grading_queue_teachers('f7400000-0000-0000-0000-000000000004')) = 0 AND NOT scores_has_official('f7300000-0000-0000-0000-000000000001','f7000000-0000-0000-0000-000000000001'),
     'both answer only a session with nobody bound';
END $$;
RESET ROLE;

-- the worker: claims past their deadline return to the queue; claims due soon are listed
ALTER TABLE grading_queue_items DISABLE TRIGGER grading_queue_items_guard;
UPDATE grading_queue_items SET claim_due_at = now() - interval '1 hour' WHERE id = 'f7400000-0000-0000-0000-000000000002';
ALTER TABLE grading_queue_items ENABLE TRIGGER grading_queue_items_guard;
SET ROLE e9x_worker;
DO $$
DECLARE released uuid[];
BEGIN
  ASSERT (SELECT count(*) FROM grading_queue_claims_due(now() - interval '2 hours', now())) = 1, 'the claim past its deadline is listed by the window';
  SELECT array_agg(r_item_id) INTO released FROM grading_queue_release_expired(now());
  ASSERT released = ARRAY['f7400000-0000-0000-0000-000000000002'::uuid], 'release_expired_claim returns the overdue claim to the queue';
  ASSERT (SELECT count(*) FROM grading_queue_release_expired(now())) = 0, 'and only once';
  ASSERT public.t_gq('SELECT count(*) FROM grading_queue_items') = 'err:42501', 'the worker has no table access';
END $$;
RESET ROLE;
DO $$
BEGIN
  ASSERT (SELECT state || ':' || coalesce(claimed_by::text,'-') || ':' || version FROM grading_queue_items WHERE id = 'f7400000-0000-0000-0000-000000000002') = 'OPEN:-:2', 'released: OPEN, no holder, version raised';
  ASSERT (SELECT state FROM grading_queue_items WHERE id = 'f7400000-0000-0000-0000-000000000001') = 'DONE', 'a graded item is not released';
END $$;
