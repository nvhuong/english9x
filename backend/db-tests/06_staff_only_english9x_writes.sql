-- P3.1: the header of a course is insert-only (shared_status aside); the AUTHOR of the exam, in the owner's context, inserts. Nobody else writes an English9x course.
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-000000000002','Center B','CENTER');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS') ON CONFLICT DO NOTHING;
INSERT INTO users(id,email) VALUES ('c6000000-0000-0000-0000-000000000001','e9author@x.test'),('c6000000-0000-0000-0000-000000000002','aauthor@x.test');
INSERT INTO org_memberships(org_id,user_id) VALUES ('aaaaaaaa-0000-0000-0000-000000000001','c6000000-0000-0000-0000-000000000002');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('c6000000-0000-0000-0000-000000000001',NULL,'IELTS','AUTHOR'),
 ('c6000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000001','IELTS','AUTHOR');
INSERT INTO courses(owner_org_id,exam_code,code,title,status) VALUES
 (NULL,'IELTS','E9PUB1','e9 published','PUBLISHED'),
 ('bbbbbbbb-0000-0000-0000-000000000002','IELTS','BPUB01','b published','PUBLISHED');

SET ROLE e9x_app;
-- nobody: no insert, no update, no delete
SELECT set_config('app.org_id','',false);
SELECT set_config('app.e9x_staff','',false);
DO $$
BEGIN
  BEGIN
    INSERT INTO courses(owner_org_id,exam_code,code,title,status,created_by) VALUES (NULL,'IELTS','EVIL01','x','DRAFT','c6000000-0000-0000-0000-000000000001');
    RAISE EXCEPTION 'insert without a user should fail';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    UPDATE courses SET title='hacked' WHERE code='E9PUB1';
    RAISE EXCEPTION 'the title is no column of the application';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    DELETE FROM courses WHERE code='E9PUB1';
    RAISE EXCEPTION 'no delete';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- org A's author: A's own course, never English9x's or B's; never PUBLISHED, never pre-approved
SELECT set_config('app.user_id','c6000000-0000-0000-0000-000000000002',false);
SELECT set_config('app.org_id','aaaaaaaa-0000-0000-0000-000000000001',false);
INSERT INTO courses(owner_org_id,exam_code,code,title,status,created_by) VALUES ('aaaaaaaa-0000-0000-0000-000000000001','IELTS','AOWN01','x','DRAFT','c6000000-0000-0000-0000-000000000002');
DO $$
BEGIN
  BEGIN
    INSERT INTO courses(owner_org_id,exam_code,code,title,status,created_by) VALUES (NULL,'IELTS','EVIL02','x','DRAFT','c6000000-0000-0000-0000-000000000002');
    RAISE EXCEPTION 'org A english9x insert should fail';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO courses(owner_org_id,exam_code,code,title,status,created_by) VALUES ('bbbbbbbb-0000-0000-0000-000000000002','IELTS','EVIL03','x','DRAFT','c6000000-0000-0000-0000-000000000002');
    RAISE EXCEPTION 'org A insert for B should fail';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO courses(owner_org_id,exam_code,code,title,status,created_by) VALUES ('aaaaaaaa-0000-0000-0000-000000000001','IELTS','EVIL04','x','PUBLISHED','c6000000-0000-0000-0000-000000000002');
    RAISE EXCEPTION 'a course is born a DRAFT';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    UPDATE courses SET status='PUBLISHED' WHERE code='AOWN01';
    RAISE EXCEPTION 'status is no column of the application (the versions decide)';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- English9x author (flag on, personal context)
SELECT set_config('app.user_id','c6000000-0000-0000-0000-000000000001',false);
SELECT set_config('app.org_id','',false);
SELECT set_config('app.e9x_staff','on',false);
INSERT INTO courses(owner_org_id,exam_code,code,title,status,created_by) VALUES (NULL,'IELTS','STAFF1','x','DRAFT','c6000000-0000-0000-0000-000000000001');
