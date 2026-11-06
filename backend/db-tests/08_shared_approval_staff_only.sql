INSERT INTO organizations(id,name,type) VALUES ('aaaaaaaa-0000-0000-0000-000000000001','Center A','CENTER');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS');
INSERT INTO courses(id,owner_org_id,exam_code,code,title,status)
 VALUES ('dddddddd-0000-0000-0000-00000000000d','aaaaaaaa-0000-0000-0000-000000000001','IELTS','ACRS01','a','PUBLISHED');

SET ROLE e9x_app;
SELECT set_config('app.org_id','aaaaaaaa-0000-0000-0000-000000000001',false);
SELECT set_config('app.e9x_staff','',false);
UPDATE courses SET shared_status='PENDING' WHERE code='ACRS01';
DO $$
BEGIN
  ASSERT (SELECT shared_status FROM courses WHERE code='ACRS01') = 'PENDING', 'owner can request sharing';
  BEGIN
    UPDATE courses SET shared_status='APPROVED' WHERE code='ACRS01';
    RAISE EXCEPTION 'owner must not self-approve';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO courses(owner_org_id,exam_code,code,title,status,shared_status)
     VALUES ('aaaaaaaa-0000-0000-0000-000000000001','IELTS','ACRS02','b','PUBLISHED','APPROVED');
    RAISE EXCEPTION 'owner must not insert pre-approved course';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
UPDATE courses SET shared_status='NONE' WHERE code='ACRS01';

SELECT set_config('app.e9x_staff','on',false);
-- staff acts on the owner row via superuser-free path: staff w/ org context of the owner
UPDATE courses SET shared_status='APPROVED' WHERE code='ACRS01';
DO $$
BEGIN
  ASSERT (SELECT shared_status FROM courses WHERE code='ACRS01') = 'APPROVED', 'staff can approve';
END $$;
-- already-approved row can be edited by the owner without tripping the guard
SELECT set_config('app.e9x_staff','',false);
UPDATE courses SET title='renamed' WHERE code='ACRS01';
