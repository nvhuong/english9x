INSERT INTO organizations(id,name,type) VALUES ('aaaaaaaa-0000-0000-0000-000000000001','Center A','CENTER');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS') ON CONFLICT DO NOTHING;

SET ROLE e9x_app;
SELECT set_config('app.org_id','',false);
SELECT set_config('app.e9x_staff','on',false);
INSERT INTO courses(owner_org_id,exam_code,code,title,status) VALUES
 (NULL,'IELTS','DRAFT1','draft one','DRAFT'),
 (NULL,'IELTS','DRAFT2','draft two','DRAFT');
DO $$
DECLARE n int;
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE code='DRAFT1') = 1, 'staff reads english9x draft';
  UPDATE courses SET title='renamed' WHERE code='DRAFT1'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 1, 'staff updates draft title';
  UPDATE courses SET status='PUBLISHED' WHERE code='DRAFT1'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 1, 'staff publishes draft';
  DELETE FROM courses WHERE code='DRAFT2'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 1, 'staff deletes draft';
END $$;
-- a fresh draft that others must not see
INSERT INTO courses(owner_org_id,exam_code,code,title,status) VALUES (NULL,'IELTS','DRAFT3','draft three','DRAFT');

SELECT set_config('app.e9x_staff','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE code='DRAFT3') = 0, 'non-staff no-org must not see english9x draft';
  ASSERT (SELECT count(*) FROM courses WHERE code='DRAFT1') = 1, 'published english9x course is public';
END $$;
SELECT set_config('app.org_id','aaaaaaaa-0000-0000-0000-000000000001',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE code='DRAFT3') = 0, 'org A must not see english9x draft';
END $$;
