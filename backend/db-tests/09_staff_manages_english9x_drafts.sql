-- P3.1: English9x draft courses are read by the AUTHOR / REVIEWER of the exam (flag on, personal context) only; the header's status follows its versions.
INSERT INTO organizations(id,name,type) VALUES ('aaaaaaaa-0000-0000-0000-000000000001','Center A','CENTER');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS') ON CONFLICT DO NOTHING;
INSERT INTO users(id,email) VALUES ('c9000000-0000-0000-0000-000000000001','e9author@x.test');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('c9000000-0000-0000-0000-000000000001',NULL,'IELTS','AUTHOR');

SET ROLE e9x_app;
SELECT set_config('app.user_id','c9000000-0000-0000-0000-000000000001',false);
SELECT set_config('app.org_id','',false);
SELECT set_config('app.e9x_staff','on',false);
INSERT INTO courses(id,owner_org_id,exam_code,code,title,status,created_by) VALUES
 ('c9100000-0000-0000-0000-000000000001',NULL,'IELTS','DRAFT1','draft one','DRAFT','c9000000-0000-0000-0000-000000000001');
INSERT INTO course_versions(course_id,owner_org_id,exam_code,version_no,title,unlock_rule,created_by)
 VALUES ('c9100000-0000-0000-0000-000000000001',NULL,'IELTS',1,'draft one v1','FREE','c9000000-0000-0000-0000-000000000001');
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE code='DRAFT1') = 1, 'the author reads the english9x draft';
  ASSERT (SELECT count(*) FROM course_versions) = 1, 'the author reads the draft version';
  BEGIN
    UPDATE courses SET status='PUBLISHED' WHERE code='DRAFT1';
    RAISE EXCEPTION 'no direct status write';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
SELECT set_config('app.e9x_staff','',false);
SELECT set_config('app.user_id','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE code='DRAFT1') = 0, 'a guest does not see the draft';
  ASSERT (SELECT count(*) FROM course_versions) = 0, 'a guest does not see the draft version';
END $$;
RESET ROLE;
-- the mirror: a published version publishes the header and gives it the title
UPDATE course_versions SET status='IN_REVIEW';
INSERT INTO review_requests(content_type,version_id,owner_org_id,exam_code,author_id,submitted_by)
 SELECT 'course', id, NULL, 'IELTS', created_by, created_by FROM course_versions;
UPDATE course_versions SET status='PUBLISHED';
DO $$
BEGIN
  ASSERT (SELECT status FROM courses WHERE code='DRAFT1') = 'PUBLISHED', 'the header follows its published version';
  ASSERT (SELECT title FROM courses WHERE code='DRAFT1') = 'draft one v1', 'and its title';
END $$;
