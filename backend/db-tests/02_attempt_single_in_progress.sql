INSERT INTO users(id,email) VALUES
 ('11111111-0000-0000-0000-000000000001','u1@x.test'),
 ('22222222-0000-0000-0000-000000000002','u2@x.test');

SET ROLE e9x_app;
SELECT set_config('app.user_id','11111111-0000-0000-0000-000000000001',false);
INSERT INTO exam_attempts(user_id,test_version_id,status)
 VALUES ('11111111-0000-0000-0000-000000000001','cccccccc-0000-0000-0000-00000000000c','IN_PROGRESS');
DO $$
BEGIN
  BEGIN
    INSERT INTO exam_attempts(user_id,test_version_id,status)
     VALUES ('11111111-0000-0000-0000-000000000001','cccccccc-0000-0000-0000-00000000000c','IN_PROGRESS');
    RAISE EXCEPTION 'second in-progress attempt should have failed';
  EXCEPTION WHEN unique_violation THEN NULL;
  END;
END $$;
UPDATE exam_attempts SET status='SUBMITTED', submitted_at=now()
 WHERE user_id='11111111-0000-0000-0000-000000000001';
INSERT INTO exam_attempts(user_id,test_version_id,status)
 VALUES ('11111111-0000-0000-0000-000000000001','cccccccc-0000-0000-0000-00000000000c','IN_PROGRESS');
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM exam_attempts) = 2, 'owner sees both attempts';
END $$;

SELECT set_config('app.user_id','22222222-0000-0000-0000-000000000002',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM exam_attempts) = 0, 'other user must not see attempts';
END $$;
