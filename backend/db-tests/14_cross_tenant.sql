INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-000000000002','Center B','CENTER');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS');
INSERT INTO courses(owner_org_id,exam_code,code,title,status) VALUES
 ('bbbbbbbb-0000-0000-0000-000000000002','IELTS','BCRS01','b course','PUBLISHED'),
 ('bbbbbbbb-0000-0000-0000-000000000002','IELTS','BCRS02','b draft','DRAFT');
INSERT INTO classes(owner_org_id,name) VALUES ('bbbbbbbb-0000-0000-0000-000000000002','class-B');
INSERT INTO questions(owner_org_id,exam_code,content,answer_key,status) VALUES
 ('bbbbbbbb-0000-0000-0000-000000000002','IELTS','{"q":"b-pub"}','{"a":1}','PUBLISHED'),
 ('bbbbbbbb-0000-0000-0000-000000000002','IELTS','{"q":"b-draft"}','{"a":1}','DRAFT'),
 (NULL,'IELTS','{"q":"e9-pub"}','{"a":1}','PUBLISHED'),
 (NULL,'IELTS','{"q":"e9-draft"}','{"a":1}','DRAFT');

SET ROLE e9x_app;
SELECT set_config('app.e9x_staff','',false);
SELECT set_config('app.org_admin','on',false);
SELECT set_config('app.org_id','aaaaaaaa-0000-0000-0000-000000000001',false);
DO $$
DECLARE n int;
BEGIN
  UPDATE courses SET title='hacked' WHERE owner_org_id='bbbbbbbb-0000-0000-0000-000000000002'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'A must not update B courses';
  DELETE FROM courses WHERE owner_org_id='bbbbbbbb-0000-0000-0000-000000000002'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'A must not delete B courses';
  UPDATE classes SET name='hacked' WHERE name='class-B'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'A must not update B class';
  DELETE FROM classes WHERE name='class-B'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'A must not delete B class';
  UPDATE questions SET status='ARCHIVED' WHERE owner_org_id IS NULL OR owner_org_id='bbbbbbbb-0000-0000-0000-000000000002'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'A must not update B or english9x questions';
  DELETE FROM questions WHERE owner_org_id IS NULL OR owner_org_id='bbbbbbbb-0000-0000-0000-000000000002'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'A must not delete B or english9x questions';
  ASSERT (SELECT count(*) FROM (SELECT id FROM questions WHERE owner_org_id='bbbbbbbb-0000-0000-0000-000000000002') q) = 0, 'A must not see B questions';
  ASSERT (SELECT count(*) FROM (SELECT id FROM questions WHERE owner_org_id IS NULL AND status='DRAFT') q) = 0, 'A must not see english9x draft questions';
  ASSERT (SELECT count(*) FROM (SELECT id FROM questions WHERE owner_org_id IS NULL AND status='PUBLISHED') q) = 1, 'A sees english9x published question';
END $$;
RESET ROLE;
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE title='hacked') = 0, 'no B course changed';
  ASSERT (SELECT count(*) FROM courses) = 2, 'no B course deleted';
  ASSERT (SELECT count(*) FROM classes WHERE name='class-B') = 1, 'B class intact';
  ASSERT (SELECT count(*) FROM questions WHERE status='ARCHIVED') = 0, 'no question changed';
  ASSERT (SELECT count(*) FROM questions) = 4, 'no question deleted';
END $$;
