-- P4.4 results and weakness (V202611160001): item_type_stats and results_method_lessons.
--  * Only the grader writes a statistic, only for a SUBMITTED attempt of the person and organisation in its session; one row per attempt and question version; never changed or deleted.
--  * The learner (request role) reads their own rows and nobody else's, and writes none.
--  * results_method_lessons names the PUBLISHED method lessons of PUBLISHED courses, tagged with the types, in courses the bound person holds a VALID entitlement to: not another
--    course, not an expired or a future plan, not a draft lesson, nothing with nobody bound.
INSERT INTO exams(code,name) VALUES ('TOEIC','TOEIC') ON CONFLICT DO NOTHING;
INSERT INTO users(id,email) VALUES
 ('f6000000-0000-0000-0000-000000000001','st-learner1@x.test'),
 ('f6000000-0000-0000-0000-000000000002','st-learner2@x.test'),
 ('f6000000-0000-0000-0000-000000000003','st-author@x.test');
INSERT INTO exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,submitted_at,submit_reason) VALUES
 ('f6300000-0000-0000-0000-000000000001','f6000000-0000-0000-0000-000000000001',NULL,gen_random_uuid(),'SUBMITTED','TOEIC',2,now(),'LEARNER'),
 ('f6300000-0000-0000-0000-000000000004','f6000000-0000-0000-0000-000000000002',NULL,gen_random_uuid(),'SUBMITTED','TOEIC',2,now(),'LEARNER');
INSERT INTO exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,deadline_at) VALUES
 ('f6300000-0000-0000-0000-000000000003','f6000000-0000-0000-0000-000000000001',NULL,gen_random_uuid(),'IN_PROGRESS','TOEIC',2,now()+interval '1 hour');

CREATE FUNCTION public.t_attempt(stmt text) RETURNS text LANGUAGE plpgsql AS $$
DECLARE n int;
BEGIN
  EXECUTE stmt; GET DIAGNOSTICS n = ROW_COUNT;
  RETURN 'rows:' || n;
EXCEPTION WHEN OTHERS THEN RETURN 'err:' || SQLSTATE;
END $$;
GRANT EXECUTE ON FUNCTION public.t_attempt(text) TO e9x_app, e9x_grader;

DO $$
BEGIN
  ASSERT (SELECT relrowsecurity FROM pg_class WHERE relname = 'item_type_stats'), 'row level security on item_type_stats';
  ASSERT NOT has_table_privilege('e9x_app', 'item_type_stats', 'INSERT') AND NOT has_table_privilege('e9x_app', 'item_type_stats', 'UPDATE')
     AND NOT has_table_privilege('e9x_app', 'item_type_stats', 'DELETE'), 'the request role writes no statistic';
  ASSERT NOT has_table_privilege('e9x_grader', 'item_type_stats', 'UPDATE') AND NOT has_table_privilege('e9x_grader', 'item_type_stats', 'DELETE'), 'nor does the grader change one';
  ASSERT has_function_privilege('e9x_app', 'results_method_lessons(text,text[],timestamptz)', 'EXECUTE')
     AND NOT has_function_privilege('e9x_worker', 'results_method_lessons(text,text[],timestamptz)', 'EXECUTE'), 'the lesson lookup is the request role''s';
END $$;

-- ---- the grader writes, in the context of learner 1 ------------------------------------------------------------------------------------------------------------
SET ROLE e9x_grader;
SELECT set_config('app.user_id','f6000000-0000-0000-0000-000000000001',false), set_config('app.org_id','',false), set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT public.t_attempt('INSERT INTO item_type_stats(attempt_id,question_version_id,user_id,owner_org_id,exam_code,type_code,skill,earned,max_points,correct) VALUES (''f6300000-0000-0000-0000-000000000001'',''f6200000-0000-0000-0000-000000000001'',''f6000000-0000-0000-0000-000000000001'',NULL,''TOEIC'',''TOEIC_P5_INCOMPLETE_SENTENCE'',''READING'',1,1,true)') = 'rows:1', 'the grader writes the statistic of a scored attempt';
  ASSERT public.t_attempt('INSERT INTO item_type_stats(attempt_id,question_version_id,user_id,owner_org_id,exam_code,type_code,skill,earned,max_points,correct) VALUES (''f6300000-0000-0000-0000-000000000001'',''f6200000-0000-0000-0000-000000000001'',''f6000000-0000-0000-0000-000000000001'',NULL,''TOEIC'',''TOEIC_P5_INCOMPLETE_SENTENCE'',''READING'',1,1,true)') = 'err:23505', 'once per attempt and question';
  ASSERT public.t_attempt('INSERT INTO item_type_stats(attempt_id,question_version_id,user_id,owner_org_id,exam_code,type_code,skill,earned,max_points,correct) VALUES (''f6300000-0000-0000-0000-000000000001'',''f6200000-0000-0000-0000-000000000002'',''f6000000-0000-0000-0000-000000000001'',NULL,''TOEIC'',''TOEIC_P1_PHOTO'',''LISTENING'',0,1,false)') = 'rows:1', 'a wrong answer';
  ASSERT public.t_attempt('INSERT INTO item_type_stats(attempt_id,question_version_id,user_id,owner_org_id,exam_code,type_code,skill,earned,max_points,correct) VALUES (''f6300000-0000-0000-0000-000000000003'',''f6200000-0000-0000-0000-000000000001'',''f6000000-0000-0000-0000-000000000001'',NULL,''TOEIC'',''TOEIC_P5_INCOMPLETE_SENTENCE'',''READING'',1,1,true)') = 'err:42501', 'not for an attempt still in progress';
  ASSERT public.t_attempt('INSERT INTO item_type_stats(attempt_id,question_version_id,user_id,owner_org_id,exam_code,type_code,skill,earned,max_points,correct) VALUES (''f6300000-0000-0000-0000-000000000004'',''f6200000-0000-0000-0000-000000000001'',''f6000000-0000-0000-0000-000000000001'',NULL,''TOEIC'',''TOEIC_P5_INCOMPLETE_SENTENCE'',''READING'',1,1,true)') = 'err:42501', 'not for the attempt of another learner';
  ASSERT public.t_attempt('INSERT INTO item_type_stats(attempt_id,question_version_id,user_id,owner_org_id,exam_code,type_code,skill,earned,max_points,correct) VALUES (''f6300000-0000-0000-0000-000000000001'',''f6200000-0000-0000-0000-000000000009'',''f6000000-0000-0000-0000-000000000002'',NULL,''TOEIC'',''TOEIC_P5_INCOMPLETE_SENTENCE'',''READING'',1,1,true)') = 'err:42501', 'nor with another learner''s name on the row';
  ASSERT public.t_attempt('INSERT INTO item_type_stats(attempt_id,question_version_id,user_id,owner_org_id,exam_code,type_code,skill,earned,max_points,correct) VALUES (''f6300000-0000-0000-0000-000000000001'',''f6200000-0000-0000-0000-000000000008'',''f6000000-0000-0000-0000-000000000001'',NULL,''TOEIC'',''TOEIC_P5_INCOMPLETE_SENTENCE'',''READING'',2,1,true)') = 'err:23514', 'never more earned than the maximum';
  ASSERT public.t_attempt('UPDATE item_type_stats SET correct = true') = 'err:42501', 'a statistic is not changed';
  ASSERT public.t_attempt('DELETE FROM item_type_stats') = 'err:42501', 'nor deleted';
END $$;
RESET ROLE;
-- learner 2 has one row of their own (the superuser writes it: the test is about who READS)
INSERT INTO item_type_stats(attempt_id,question_version_id,user_id,owner_org_id,exam_code,type_code,skill,earned,max_points,correct) VALUES
 ('f6300000-0000-0000-0000-000000000004','f6200000-0000-0000-0000-000000000001','f6000000-0000-0000-0000-000000000002',NULL,'TOEIC','TOEIC_P5_INCOMPLETE_SENTENCE','READING',1,1,true);

-- ---- the learner reads their own and writes none ---------------------------------------------------------------------------------------------------------------
SET ROLE e9x_app;
SELECT set_config('app.user_id','f6000000-0000-0000-0000-000000000001',false), set_config('app.org_id','',false), set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM item_type_stats) = 2 AND (SELECT count(*) FILTER (WHERE user_id <> 'f6000000-0000-0000-0000-000000000001') FROM item_type_stats) = 0, 'learner 1 reads their two rows and no other';
  ASSERT public.t_attempt('INSERT INTO item_type_stats(attempt_id,question_version_id,user_id,exam_code,type_code,skill,earned,max_points,correct) VALUES (''f6300000-0000-0000-0000-000000000001'',''f6200000-0000-0000-0000-000000000005'',''f6000000-0000-0000-0000-000000000001'',''TOEIC'',''TOEIC_P5_INCOMPLETE_SENTENCE'',''READING'',1,1,true)') = 'err:42501', 'a learner cannot write their own statistic';
END $$;
RESET ROLE;

-- ---- the method lessons: courses c1 (entitled), c2 (no entitlement), c3 (expired), c4 (future), c5 (lesson version still a draft) ---------------------------------------
INSERT INTO courses(id,owner_org_id,exam_code,code,title,status) VALUES
 ('f6c00000-0000-0000-0000-000000000001',NULL,'TOEIC','F6C001','Course 1','PUBLISHED'),
 ('f6c00000-0000-0000-0000-000000000002',NULL,'TOEIC','F6C002','Course 2','PUBLISHED'),
 ('f6c00000-0000-0000-0000-000000000003',NULL,'TOEIC','F6C003','Course 3','PUBLISHED'),
 ('f6c00000-0000-0000-0000-000000000004',NULL,'TOEIC','F6C004','Course 4','PUBLISHED'),
 ('f6c00000-0000-0000-0000-000000000005',NULL,'TOEIC','F6C005','Course 5','PUBLISHED');
INSERT INTO course_versions(id,course_id,owner_org_id,exam_code,version_no,status,title,unlock_rule,created_by)
  SELECT ('f6c10000-0000-0000-0000-00000000000' || n)::uuid, ('f6c00000-0000-0000-0000-00000000000' || n)::uuid, NULL, 'TOEIC', 1, 'PUBLISHED', 'Course ' || n, 'FREE', 'f6000000-0000-0000-0000-000000000003'
    FROM generate_series(1,5) n;
INSERT INTO strategy_lessons(id,owner_org_id,exam_code,created_by)
  SELECT ('f6d00000-0000-0000-0000-00000000000' || n)::uuid, NULL, 'TOEIC', 'f6000000-0000-0000-0000-000000000003' FROM generate_series(1,5) n;
INSERT INTO strategy_lesson_versions(id,lesson_id,owner_org_id,exam_code,version_no,status,title,blocks,created_by)
  SELECT ('f6e00000-0000-0000-0000-00000000000' || n)::uuid, ('f6d00000-0000-0000-0000-00000000000' || n)::uuid, NULL, 'TOEIC', 1, 'PUBLISHED', 'Lesson ' || n, '[]'::jsonb, 'f6000000-0000-0000-0000-000000000003'
    FROM generate_series(1,5) n;
ALTER TABLE strategy_lesson_types DISABLE TRIGGER USER;
ALTER TABLE course_chapters DISABLE TRIGGER USER;
ALTER TABLE course_lessons DISABLE TRIGGER USER;
INSERT INTO strategy_lesson_types(version_id,type_code,owner_org_id,exam_code)
  SELECT ('f6e00000-0000-0000-0000-00000000000' || n)::uuid, 'TOEIC_P5_INCOMPLETE_SENTENCE', NULL, 'TOEIC' FROM generate_series(1,5) n;
INSERT INTO course_chapters(course_version_id,position,title,owner_org_id,exam_code)
  SELECT ('f6c10000-0000-0000-0000-00000000000' || n)::uuid, 1, 'Chapter', NULL, 'TOEIC' FROM generate_series(1,5) n;
INSERT INTO course_lessons(course_version_id,chapter_position,position,kind,ref_id,title,owner_org_id,exam_code)
  SELECT ('f6c10000-0000-0000-0000-00000000000' || n)::uuid, 1, 1, 'LESSON', ('f6e00000-0000-0000-0000-00000000000' || n)::uuid, 'Lesson ' || n, NULL, 'TOEIC' FROM generate_series(1,5) n;
ALTER TABLE strategy_lesson_types ENABLE TRIGGER USER;
ALTER TABLE course_chapters ENABLE TRIGGER USER;
ALTER TABLE course_lessons ENABLE TRIGGER USER;
ALTER TABLE strategy_lesson_versions DISABLE TRIGGER USER;
UPDATE strategy_lesson_versions SET status = 'DRAFT' WHERE id = 'f6e00000-0000-0000-0000-000000000005';
ALTER TABLE strategy_lesson_versions ENABLE TRIGGER USER;
INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_order_id) VALUES
 ('f6000000-0000-0000-0000-000000000001','f6c00000-0000-0000-0000-000000000001', now() - interval '1 day',  now() + interval '30 days', gen_random_uuid()),
 ('f6000000-0000-0000-0000-000000000001','f6c00000-0000-0000-0000-000000000003', now() - interval '60 days', now() - interval '30 days', gen_random_uuid()),
 ('f6000000-0000-0000-0000-000000000001','f6c00000-0000-0000-0000-000000000004', now() + interval '5 days', now() + interval '35 days', gen_random_uuid()),
 ('f6000000-0000-0000-0000-000000000001','f6c00000-0000-0000-0000-000000000005', now() - interval '1 day',  now() + interval '30 days', gen_random_uuid()),
 ('f6000000-0000-0000-0000-000000000002','f6c00000-0000-0000-0000-000000000002', now() - interval '1 day',  now() + interval '30 days', gen_random_uuid());

SET ROLE e9x_app;
SELECT set_config('app.user_id','f6000000-0000-0000-0000-000000000001',false), set_config('app.org_id','',false), set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(course_title) FROM results_method_lessons('TOEIC', ARRAY['TOEIC_P5_INCOMPLETE_SENTENCE'], now())) = ARRAY['Course 1'],
         'learner 1: only the course they hold a valid plan for (not course 2 of somebody else, not the expired, the future, or the draft lesson)';
  ASSERT (SELECT count(*) FROM results_method_lessons('TOEIC', ARRAY['TOEIC_P1_PHOTO'], now())) = 0, 'a type no lesson is tagged with';
  ASSERT (SELECT count(*) FROM results_method_lessons('IELTS', ARRAY['TOEIC_P5_INCOMPLETE_SENTENCE'], now())) = 0, 'another exam';
  ASSERT (SELECT count(*) FROM results_method_lessons(NULL, ARRAY['TOEIC_P5_INCOMPLETE_SENTENCE'], now())) = 1, 'no exam filter: the same one lesson';
  ASSERT (SELECT array_agg(course_title) FROM results_method_lessons('TOEIC', ARRAY['TOEIC_P5_INCOMPLETE_SENTENCE'], now() + interval '10 days')) = ARRAY['Course 1','Course 4'],
         'ten days on, the future plan has begun (course 4); the draft lesson of course 5 is never named';
  ASSERT (SELECT array_agg(course_title) FROM results_method_lessons('TOEIC', ARRAY['TOEIC_P5_INCOMPLETE_SENTENCE'], now() - interval '45 days')) = ARRAY['Course 3'],
         'asked for a moment inside the plan that expired since: that plan, and only that one';
END $$;
SELECT set_config('app.user_id','f6000000-0000-0000-0000-000000000002',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(course_title) FROM results_method_lessons('TOEIC', ARRAY['TOEIC_P5_INCOMPLETE_SENTENCE'], now())) = ARRAY['Course 2'], 'learner 2: theirs only';
END $$;
SELECT set_config('app.user_id','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM results_method_lessons('TOEIC', ARRAY['TOEIC_P5_INCOMPLETE_SENTENCE'], now())) = 0, 'with nobody bound: nothing';
END $$;
