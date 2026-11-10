INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-000000000002','Center B','CENTER');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS') ON CONFLICT DO NOTHING;
INSERT INTO courses(owner_org_id,exam_code,code,title,status) VALUES
 (NULL,'IELTS','E9PUB1','e9 published','PUBLISHED'),
 ('bbbbbbbb-0000-0000-0000-000000000002','IELTS','BPUB01','b published','PUBLISHED');
-- (questions: P2.3 replaced their wide policies with role-and-exam policies; backend/db-tests/40_questions.sql covers them)

SET ROLE e9x_app;
-- non-staff, no org
SELECT set_config('app.org_id','',false);
SELECT set_config('app.e9x_staff','',false);
DO $$
DECLARE n int;
BEGIN
  BEGIN
    INSERT INTO courses(owner_org_id,exam_code,code,title,status) VALUES (NULL,'IELTS','EVIL01','x','DRAFT');
    RAISE EXCEPTION 'non-staff english9x insert should fail';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  UPDATE courses SET title='hacked' WHERE code='E9PUB1'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'non-staff update of english9x course must affect 0 rows';
  DELETE FROM courses WHERE code='E9PUB1'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'non-staff delete of english9x course must affect 0 rows';
END $$;

-- org A
SELECT set_config('app.org_id','aaaaaaaa-0000-0000-0000-000000000001',false);
DO $$
DECLARE n int;
BEGIN
  BEGIN
    INSERT INTO courses(owner_org_id,exam_code,code,title,status) VALUES (NULL,'IELTS','EVIL02','x','DRAFT');
    RAISE EXCEPTION 'org A english9x insert should fail';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO courses(owner_org_id,exam_code,code,title,status) VALUES ('bbbbbbbb-0000-0000-0000-000000000002','IELTS','EVIL03','x','DRAFT');
    RAISE EXCEPTION 'org A insert for B should fail';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  UPDATE courses SET title='hacked' WHERE code IN ('E9PUB1','BPUB01'); GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'org A update of english9x/B courses must affect 0 rows';
  DELETE FROM courses WHERE code IN ('E9PUB1','BPUB01'); GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'org A delete of english9x/B courses must affect 0 rows';
END $$;

-- staff, no org
SELECT set_config('app.org_id','',false);
SELECT set_config('app.e9x_staff','on',false);
INSERT INTO courses(owner_org_id,exam_code,code,title,status) VALUES (NULL,'IELTS','STAFF1','x','DRAFT');
