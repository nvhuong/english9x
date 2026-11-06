INSERT INTO organizations(id,name,type) VALUES ('aaaaaaaa-0000-0000-0000-000000000001','Center A','CENTER');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS');
INSERT INTO courses(id,owner_org_id,exam_code,code,title,status) VALUES
 ('dddddddd-0000-0000-0000-00000000000d','aaaaaaaa-0000-0000-0000-000000000001','IELTS','ACRS01','a','PUBLISHED'),
 ('eeeeeeee-0000-0000-0000-00000000000e','aaaaaaaa-0000-0000-0000-000000000001','IELTS','ACRS03','private','PUBLISHED');

SET ROLE e9x_app;
-- owner (org A, not staff) requests sharing but cannot approve
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

-- non-staff without org context cannot touch the pending course
SELECT set_config('app.org_id','',false);
DO $$
DECLARE n int;
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE code='ACRS01') = 0, 'non-staff no-org must not see pending course';
  UPDATE courses SET title='x' WHERE code='ACRS01'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'non-staff no-org must not update pending course';
END $$;

-- English9x staff, NO org context: reviews and approves the shared catalog
SELECT set_config('app.org_id','',false);
SELECT set_config('app.e9x_staff','on',false);
DO $$
DECLARE n int;
BEGIN
  ASSERT (SELECT count(*) FROM courses WHERE code='ACRS01') = 1, 'staff sees pending course';
  ASSERT (SELECT count(*) FROM courses WHERE code='ACRS03') = 0, 'staff must not see unshared org course';
  UPDATE courses SET title='hacked' WHERE code='ACRS03'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'staff must not update unshared org course';
  UPDATE courses SET shared_status='APPROVED' WHERE code='ACRS01'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 1, 'staff approves from english9x context';
  ASSERT (SELECT shared_status FROM courses WHERE code='ACRS01') = 'APPROVED', 'staff can approve';
END $$;

-- already-approved row can be edited by the owner without tripping the guard
SELECT set_config('app.e9x_staff','',false);
SELECT set_config('app.org_id','aaaaaaaa-0000-0000-0000-000000000001',false);
DO $$
DECLARE n int;
BEGIN
  UPDATE courses SET title='renamed' WHERE code='ACRS01'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 1, 'owner edits approved course';
END $$;

