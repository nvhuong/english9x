INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-000000000002','Center B','CENTER');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS') ON CONFLICT DO NOTHING;
INSERT INTO courses(owner_org_id,exam_code,code,title,status) VALUES
 ('bbbbbbbb-0000-0000-0000-000000000002','IELTS','BCRS01','b course','PUBLISHED'),
 ('bbbbbbbb-0000-0000-0000-000000000002','IELTS','BCRS02','b draft','DRAFT');
INSERT INTO classes(owner_org_id,name) VALUES ('bbbbbbbb-0000-0000-0000-000000000002','class-B');
-- (questions across tenants: backend/db-tests/40_questions.sql)

SET ROLE e9x_app;
SELECT set_config('app.e9x_staff','',false);
SELECT set_config('app.org_admin','on',false);
SELECT set_config('app.org_id','aaaaaaaa-0000-0000-0000-000000000001',false);
DO $$
DECLARE n int;
BEGIN
  UPDATE courses SET shared_status='PENDING' WHERE owner_org_id='bbbbbbbb-0000-0000-0000-000000000002'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'A must not update B courses';
  BEGIN
    DELETE FROM courses WHERE owner_org_id='bbbbbbbb-0000-0000-0000-000000000002';
    RAISE EXCEPTION 'nobody deletes a course through the application role';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  UPDATE classes SET name='hacked' WHERE name='class-B'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'A must not update B class';
  DELETE FROM classes WHERE name='class-B'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'A must not delete B class';
END $$;
RESET ROLE;
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE shared_status <> 'NONE') = 0, 'no B course changed';
  ASSERT (SELECT count(*) FROM courses) = 2, 'no B course deleted';
  ASSERT (SELECT count(*) FROM classes WHERE name='class-B') = 1, 'B class intact';
END $$;
