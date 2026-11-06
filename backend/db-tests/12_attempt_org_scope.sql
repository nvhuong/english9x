INSERT INTO users(id,email) VALUES ('11111111-0000-0000-0000-000000000001','u1@x.test');
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-000000000002','Center B','CENTER');

SET ROLE e9x_app;
SELECT set_config('app.user_id','11111111-0000-0000-0000-000000000001',false);
SELECT set_config('app.org_id','aaaaaaaa-0000-0000-0000-000000000001',false);
DO $$
DECLARE n int;
BEGIN
  BEGIN
    INSERT INTO exam_attempts(user_id,owner_org_id,test_version_id,status)
     VALUES ('11111111-0000-0000-0000-000000000001','bbbbbbbb-0000-0000-0000-000000000002','cccccccc-0000-0000-0000-000000000001','IN_PROGRESS');
    RAISE EXCEPTION 'attempt under another org should fail';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  INSERT INTO exam_attempts(user_id,owner_org_id,test_version_id,status)
   VALUES ('11111111-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001','cccccccc-0000-0000-0000-000000000002','IN_PROGRESS');
  INSERT INTO exam_attempts(user_id,owner_org_id,test_version_id,status)
   VALUES ('11111111-0000-0000-0000-000000000001',NULL,'cccccccc-0000-0000-0000-000000000003','IN_PROGRESS');
  BEGIN
    UPDATE exam_attempts SET owner_org_id='bbbbbbbb-0000-0000-0000-000000000002'
     WHERE test_version_id='cccccccc-0000-0000-0000-000000000002';
    RAISE EXCEPTION 'moving attempt to another org should fail';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  UPDATE exam_attempts SET status='SUBMITTED', submitted_at=now()
   WHERE test_version_id='cccccccc-0000-0000-0000-000000000002'; GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 1, 'learner submits own org attempt';
END $$;

-- personal context: may only create personal (owner NULL) attempts
SELECT set_config('app.org_id','',false);
DO $$
BEGIN
  BEGIN
    INSERT INTO exam_attempts(user_id,owner_org_id,test_version_id,status)
     VALUES ('11111111-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001','cccccccc-0000-0000-0000-000000000004','IN_PROGRESS');
    RAISE EXCEPTION 'org attempt without org context should fail';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  INSERT INTO exam_attempts(user_id,owner_org_id,test_version_id,status)
   VALUES ('11111111-0000-0000-0000-000000000001',NULL,'cccccccc-0000-0000-0000-000000000005','IN_PROGRESS');
END $$;
