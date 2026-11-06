INSERT INTO users(id,email) VALUES
 ('11111111-0000-0000-0000-000000000001','u1@x.test'),
 ('22222222-0000-0000-0000-000000000002','u2@x.test');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS');
INSERT INTO courses(id,owner_org_id,exam_code,code,title,status)
 VALUES ('dddddddd-0000-0000-0000-00000000000d',NULL,'IELTS','E9PUB1','c','PUBLISHED');

DO $$
BEGIN
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until)
     VALUES ('11111111-0000-0000-0000-000000000001','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '1 day');
    RAISE EXCEPTION 'both sources null should fail';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_order_id,source_seat_id)
     VALUES ('11111111-0000-0000-0000-000000000001','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '1 day',gen_random_uuid(),gen_random_uuid());
    RAISE EXCEPTION 'both sources set should fail';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_order_id)
     VALUES ('11111111-0000-0000-0000-000000000001','dddddddd-0000-0000-0000-00000000000d',now(),now(),gen_random_uuid());
    RAISE EXCEPTION 'valid_until <= valid_from should fail';
  EXCEPTION WHEN check_violation THEN NULL; END;
END $$;

INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_order_id) VALUES
 ('11111111-0000-0000-0000-000000000001','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '1 day',gen_random_uuid());
INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id) VALUES
 ('22222222-0000-0000-0000-000000000002','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '1 day',gen_random_uuid());

SET ROLE e9x_app;
SELECT set_config('app.user_id','11111111-0000-0000-0000-000000000001',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM entitlements) = 1, 'learner sees one entitlement';
  ASSERT (SELECT count(*) FROM entitlements WHERE user_id='22222222-0000-0000-0000-000000000002') = 0, 'must not see other learner entitlement';
END $$;
SELECT set_config('app.user_id','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM entitlements) = 0, 'no context -> none';
END $$;
