-- P3.1 courses (V202611130001): the measured hole of the P1 review (a TEACHER-TOEIC staff could set status = PUBLISHED / shared_status = APPROVED) is closed, and
-- course versions follow the pattern of tests. Here: role-and-exam policies of the header, the version lifecycle, the guards of the children, the public
-- catalog (a guest sees PUBLISHED English9x / APPROVED shared outlines, never drafts or private courses), and the media function.
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS'),('TOEIC','TOEIC') ON CONFLICT DO NOTHING;
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','Center B','CENTER');
INSERT INTO users(id,email) VALUES
 ('e7000000-0000-0000-0000-000000000001','author-a@x.test'),
 ('e7000000-0000-0000-0000-000000000002','reviewer-a@x.test'),
 ('e7000000-0000-0000-0000-000000000003','learner-a@x.test'),
 ('e7000000-0000-0000-0000-000000000004','teacher-e9-toeic@x.test'),
 ('e7000000-0000-0000-0000-000000000005','author-b@x.test'),
 ('e7000000-0000-0000-0000-000000000006','e9-author@x.test');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','e7000000-0000-0000-0000-000000000001'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','e7000000-0000-0000-0000-000000000002'),
 ('aaaaaaaa-0000-0000-0000-00000000000a','e7000000-0000-0000-0000-000000000003'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','e7000000-0000-0000-0000-000000000005');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('e7000000-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC','AUTHOR'),
 ('e7000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC','REVIEWER'),
 ('e7000000-0000-0000-0000-000000000003','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'LEARNER'),
 ('e7000000-0000-0000-0000-000000000004',NULL,'TOEIC','TEACHER'),
 ('e7000000-0000-0000-0000-000000000005','bbbbbbbb-0000-0000-0000-00000000000b','TOEIC','AUTHOR'),
 ('e7000000-0000-0000-0000-000000000006',NULL,'TOEIC','AUTHOR');

-- fixtures as the owner: lesson versions to hang courses on (a PUBLISHED one of A with an audio block, one of B, one of English9x), the courses
INSERT INTO strategy_lessons(id,owner_org_id,exam_code,created_by) VALUES
 ('e7100000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC','e7000000-0000-0000-0000-000000000001'),
 ('e7100000-0000-0000-0000-00000000000b','bbbbbbbb-0000-0000-0000-00000000000b','TOEIC','e7000000-0000-0000-0000-000000000005'),
 ('e7100000-0000-0000-0000-0000000000e9',NULL,'TOEIC','e7000000-0000-0000-0000-000000000006');
INSERT INTO strategy_lesson_versions(id,lesson_id,owner_org_id,exam_code,version_no,title,blocks,created_by) VALUES
 ('e7200000-0000-0000-0000-00000000000a','e7100000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC',1,'Lesson A','[{"type":"audio","audioMediaId":"e7900000-0000-0000-0000-000000000001"}]','e7000000-0000-0000-0000-000000000001'),
 ('e7200000-0000-0000-0000-00000000000b','e7100000-0000-0000-0000-00000000000b','bbbbbbbb-0000-0000-0000-00000000000b','TOEIC',1,'Lesson B','[]','e7000000-0000-0000-0000-000000000005'),
 ('e7200000-0000-0000-0000-0000000000e9','e7100000-0000-0000-0000-0000000000e9',NULL,'TOEIC',1,'Lesson E9','[]','e7000000-0000-0000-0000-000000000006');
UPDATE strategy_lesson_versions SET status = 'IN_REVIEW';
UPDATE strategy_lesson_versions SET status = 'PUBLISHED';
-- courses: cA (org A, PUBLISHED v1), cB (org B, PUBLISHED v1, private), cE (English9x, PUBLISHED), cD (org A, DRAFT only)
INSERT INTO courses(id,owner_org_id,exam_code,code,title,status,created_by) VALUES
 ('e7300000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC','CRSA01','x','DRAFT','e7000000-0000-0000-0000-000000000001'),
 ('e7300000-0000-0000-0000-00000000000b','bbbbbbbb-0000-0000-0000-00000000000b','TOEIC','CRSB01','x','DRAFT','e7000000-0000-0000-0000-000000000005'),
 ('e7300000-0000-0000-0000-0000000000e9',NULL,'TOEIC','CRSE01','x','DRAFT','e7000000-0000-0000-0000-000000000006'),
 ('e7300000-0000-0000-0000-0000000000d1','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC','CRSD01','x','DRAFT','e7000000-0000-0000-0000-000000000001');
INSERT INTO course_versions(id,course_id,owner_org_id,exam_code,version_no,title,unlock_rule,created_by) VALUES
 ('e7400000-0000-0000-0000-00000000000a','e7300000-0000-0000-0000-00000000000a','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC',1,'Course A','STRICT','e7000000-0000-0000-0000-000000000001'),
 ('e7400000-0000-0000-0000-00000000000b','e7300000-0000-0000-0000-00000000000b','bbbbbbbb-0000-0000-0000-00000000000b','TOEIC',1,'Course B','FREE','e7000000-0000-0000-0000-000000000005'),
 ('e7400000-0000-0000-0000-0000000000e9','e7300000-0000-0000-0000-0000000000e9',NULL,'TOEIC',1,'Course E9','COMPLETION','e7000000-0000-0000-0000-000000000006'),
 ('e7400000-0000-0000-0000-0000000000d1','e7300000-0000-0000-0000-0000000000d1','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC',1,'Course D','FREE','e7000000-0000-0000-0000-000000000001');
INSERT INTO course_chapters(course_version_id,position,title,owner_org_id,exam_code) VALUES
 ('e7400000-0000-0000-0000-00000000000a',1,'Ch A','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC'),
 ('e7400000-0000-0000-0000-00000000000b',1,'Ch B','bbbbbbbb-0000-0000-0000-00000000000b','TOEIC'),
 ('e7400000-0000-0000-0000-0000000000e9',1,'Ch E9',NULL,'TOEIC'),
 ('e7400000-0000-0000-0000-0000000000d1',1,'Ch D','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC');
INSERT INTO course_lessons(course_version_id,chapter_position,position,kind,ref_id,title,owner_org_id,exam_code) VALUES
 ('e7400000-0000-0000-0000-00000000000a',1,1,'LESSON','e7200000-0000-0000-0000-00000000000a','Lesson A','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC'),
 ('e7400000-0000-0000-0000-00000000000b',1,1,'LESSON','e7200000-0000-0000-0000-00000000000b','Lesson B','bbbbbbbb-0000-0000-0000-00000000000b','TOEIC'),
 ('e7400000-0000-0000-0000-0000000000e9',1,1,'LESSON','e7200000-0000-0000-0000-0000000000e9','Lesson E9',NULL,'TOEIC');
UPDATE course_versions SET status = 'IN_REVIEW' WHERE id <> 'e7400000-0000-0000-0000-0000000000d1';
UPDATE course_versions SET status = 'PUBLISHED' WHERE id <> 'e7400000-0000-0000-0000-0000000000d1';

-- the header mirrors the versions (status and title)
DO $$
BEGIN
  ASSERT (SELECT status FROM courses WHERE code = 'CRSA01') = 'PUBLISHED', 'the header follows its published version';
  ASSERT (SELECT title FROM courses WHERE code = 'CRSA01') = 'Course A', 'and its title';
  ASSERT (SELECT status FROM courses WHERE code = 'CRSD01') = 'DRAFT', 'a draft course stays a draft';
  -- the owner's own guard refuses a child under a PUBLISHED version, and a lesson of another owner
  BEGIN
    INSERT INTO course_chapters(course_version_id,position,title,owner_org_id,exam_code) VALUES ('e7400000-0000-0000-0000-00000000000a',2,'late','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC');
    RAISE EXCEPTION 'a published version has no new chapter';
  EXCEPTION WHEN integrity_constraint_violation THEN NULL; END;
  BEGIN
    INSERT INTO course_lessons(course_version_id,chapter_position,position,kind,ref_id,title,owner_org_id,exam_code)
      VALUES ('e7400000-0000-0000-0000-0000000000d1',1,1,'LESSON','e7200000-0000-0000-0000-00000000000b','of B','aaaaaaaa-0000-0000-0000-00000000000a','TOEIC');
    RAISE EXCEPTION 'a course holds lessons of its own owner only';
  EXCEPTION WHEN integrity_constraint_violation THEN NULL; END;
END $$;

SET ROLE e9x_app;
-- ---- a guest: PUBLISHED English9x only
SELECT set_config('app.user_id','',false);
SELECT set_config('app.org_id','',false);
SELECT set_config('app.e9x_staff','',false);
DO $$
DECLARE n int;
BEGIN
  ASSERT (SELECT array_agg(code::text ORDER BY code) FROM courses) = ARRAY['CRSE01'], 'a guest sees the published English9x course only';
  ASSERT (SELECT count(*) FROM course_versions) = 1 AND (SELECT count(*) FROM course_chapters) = 1 AND (SELECT count(*) FROM course_lessons) = 1, 'and its outline';
  UPDATE courses SET shared_status = 'APPROVED' WHERE code = 'CRSE01'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'a guest writes nothing';
END $$;

-- ---- org A's learner: the same plus A's own PUBLISHED course (not the draft, not B's)
SELECT set_config('app.user_id','e7000000-0000-0000-0000-000000000003',false);
SELECT set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false);
DO $$
BEGIN
  ASSERT (SELECT array_agg(code::text ORDER BY code) FROM courses) = ARRAY['CRSA01','CRSE01'], 'a learner of A sees A''s published and English9x''s';
  ASSERT (SELECT count(*) FROM course_versions WHERE status <> 'PUBLISHED') = 0, 'no draft version';
END $$;

-- ---- org A's author: also the drafts, and writes only their own; a PUBLISHED version cannot be written; status cannot be set
SELECT set_config('app.user_id','e7000000-0000-0000-0000-000000000001',false);
DO $$
DECLARE n int;
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE code = 'CRSD01') = 1, 'the author reads the draft course';
  ASSERT (SELECT count(*) FROM courses WHERE code = 'CRSB01') = 0, 'but not B''s';
  UPDATE course_versions SET title = 'renamed' WHERE id = 'e7400000-0000-0000-0000-0000000000d1'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 1, 'the author edits their draft version';
  BEGIN
    UPDATE course_versions SET status = 'PUBLISHED' WHERE id = 'e7400000-0000-0000-0000-0000000000d1';
    RAISE EXCEPTION 'a draft does not jump to PUBLISHED';
  EXCEPTION WHEN integrity_constraint_violation OR insufficient_privilege OR check_violation THEN NULL; END;
  BEGIN
    UPDATE courses SET status = 'PUBLISHED' WHERE code = 'CRSD01';
    RAISE EXCEPTION 'the header status is not a column of the application';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    UPDATE courses SET shared_status = 'APPROVED' WHERE code = 'CRSA01';
    RAISE EXCEPTION 'the owner does not approve';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  UPDATE courses SET shared_status = 'PENDING' WHERE code = 'CRSA01'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 1, 'the author asks for sharing';
  BEGIN
    UPDATE course_versions SET title = 'hacked' WHERE id = 'e7400000-0000-0000-0000-00000000000a';
    RAISE EXCEPTION 'nobody edits a published version';
  EXCEPTION WHEN integrity_constraint_violation THEN NULL; END;
END $$;

-- ---- the measured hole: an English9x TEACHER of TOEIC (staff flag on, personal context) neither sets PUBLISHED nor APPROVED, nor even sees org A's pending course
SELECT set_config('app.user_id','e7000000-0000-0000-0000-000000000004',false);
SELECT set_config('app.org_id','',false);
SELECT set_config('app.e9x_staff','on',false);
DO $$
DECLARE n int;
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE code = 'CRSA01') = 0, 'a TEACHER does not see the pending shared course';
  UPDATE courses SET shared_status = 'APPROVED' WHERE code = 'CRSA01'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'a TEACHER does not approve it';
  BEGIN
    UPDATE courses SET status = 'PUBLISHED' WHERE code = 'CRSE01';
    RAISE EXCEPTION 'a TEACHER does not publish';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO courses(owner_org_id,exam_code,code,title,status,shared_status,created_by) VALUES (NULL,'TOEIC','EVIL01','x','PUBLISHED','APPROVED','e7000000-0000-0000-0000-000000000004');
    RAISE EXCEPTION 'a TEACHER does not insert';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- ---- an English9x AUTHOR of TOEIC: English9x drafts yes; org A's course no
SELECT set_config('app.user_id','e7000000-0000-0000-0000-000000000006',false);
DO $$
BEGIN
  INSERT INTO courses(owner_org_id,exam_code,code,title,status,created_by) VALUES (NULL,'TOEIC','E9NEW1','x','DRAFT','e7000000-0000-0000-0000-000000000006');
  ASSERT (SELECT count(*) FROM courses WHERE code = 'E9NEW1') = 1, 'the English9x author reads their draft';
  ASSERT (SELECT count(*) FROM courses WHERE code = 'CRSD01') = 0, 'and not org A''s draft';
END $$;

-- ---- org B's author: nothing of A
SELECT set_config('app.user_id','e7000000-0000-0000-0000-000000000005',false);
SELECT set_config('app.org_id','bbbbbbbb-0000-0000-0000-00000000000b',false);
SELECT set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE code IN ('CRSA01','CRSD01')) = 0, 'B sees nothing of A (pending, not approved)';
  ASSERT (SELECT count(*) FROM course_versions WHERE owner_org_id = 'aaaaaaaa-0000-0000-0000-00000000000a') = 0, 'nor A''s versions';
END $$;

-- ---- the media function: which courses hold a file (executable by the application role, no other grant needed)
DO $$
BEGIN
  ASSERT (SELECT array_agg(c) FROM course_ids_with_media('e7900000-0000-0000-0000-000000000001') c) = ARRAY['e7300000-0000-0000-0000-00000000000a'::uuid], 'course A holds the audio of its lesson';
  ASSERT (SELECT count(*) FROM course_ids_with_media(gen_random_uuid())) = 0, 'a file no course holds';
END $$;
RESET ROLE;
-- a draft course's file is not held by it: only PUBLISHED / ARCHIVED versions count
DO $$
BEGIN
  ASSERT has_function_privilege('public', 'course_ids_with_media(uuid)', 'EXECUTE') = false, 'not executable by PUBLIC';
END $$;
