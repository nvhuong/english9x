-- fixtures (superuser)
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-000000000002','Center B','CENTER');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS') ON CONFLICT DO NOTHING;
INSERT INTO classes(owner_org_id,name) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001','class-A'),
 ('bbbbbbbb-0000-0000-0000-000000000002','class-B');
SELECT set_config('app.e9x_staff','on',false);
INSERT INTO courses(owner_org_id,exam_code,code,title,status,shared_status) VALUES
 (NULL,'IELTS','E9PUB1','e9 published','PUBLISHED','NONE'),
 (NULL,'IELTS','E9DRF1','e9 draft','DRAFT','NONE'),
 ('bbbbbbbb-0000-0000-0000-000000000002','IELTS','BSHAR1','b shared','PUBLISHED','APPROVED'),
 ('bbbbbbbb-0000-0000-0000-000000000002','IELTS','BDRAF1','b draft','DRAFT','NONE'),
 ('bbbbbbbb-0000-0000-0000-000000000002','IELTS','BPRIV1','b published private','PUBLISHED','NONE'),
 ('aaaaaaaa-0000-0000-0000-000000000001','IELTS','ADRAF1','a draft','DRAFT','NONE');

SELECT set_config('app.e9x_staff','',false);

SET ROLE e9x_app;
SELECT set_config('app.org_id','aaaaaaaa-0000-0000-0000-000000000001',false);
DO $$
DECLARE c int;
BEGIN
  ASSERT (SELECT count(*) FROM classes) = 1, 'A sees exactly 1 class';
  ASSERT (SELECT name FROM classes) = 'class-A', 'A sees class-A';
  ASSERT (SELECT count(*) FROM classes WHERE name='class-B') = 0, 'A must not see class-B';
  ASSERT EXISTS (SELECT 1 FROM courses WHERE code='E9PUB1'), 'sees english9x published';
  ASSERT NOT EXISTS (SELECT 1 FROM courses WHERE code='E9DRF1'), 'must not see english9x draft';
  ASSERT EXISTS (SELECT 1 FROM courses WHERE code='BSHAR1'), 'sees B approved+published';
  ASSERT NOT EXISTS (SELECT 1 FROM courses WHERE code='BDRAF1'), 'must not see B draft';
  ASSERT NOT EXISTS (SELECT 1 FROM courses WHERE code='BPRIV1'), 'must not see B unshared';
  ASSERT EXISTS (SELECT 1 FROM courses WHERE code='ADRAF1'), 'sees own draft';
  ASSERT (SELECT count(*) FROM organizations) = 1, 'sees only own org';
  BEGIN
    INSERT INTO classes(owner_org_id,name) VALUES ('bbbbbbbb-0000-0000-0000-000000000002','evil');
    RAISE EXCEPTION 'insert into B class should have failed';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
  BEGIN
    INSERT INTO courses(owner_org_id,exam_code,code,title,status) VALUES (NULL,'IELTS','EVIL01','x','DRAFT');
    RAISE EXCEPTION 'insert english9x course as org should have failed';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
END $$;

SELECT set_config('app.org_id','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM classes) = 0, 'no context -> no classes';
  ASSERT (SELECT count(*) FROM organizations) = 0, 'no context -> no orgs';
  ASSERT (SELECT count(*) FROM courses) = 2, 'no context -> only english9x published + shared approved';
END $$;
