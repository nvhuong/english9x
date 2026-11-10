INSERT INTO users(id,email) VALUES
 ('11111111-0000-0000-0000-000000000001','u1@x.test'),
 ('22222222-0000-0000-0000-000000000002','u2@x.test');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS') ON CONFLICT DO NOTHING;
INSERT INTO organizations(id,name,type) VALUES ('a1a1a1a1-0000-0000-0000-00000000000a','A','CENTER');
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
    -- with the issuing organisation present, so this can only fail for having both sources
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_order_id,source_seat_id,source_org_id)
     VALUES ('11111111-0000-0000-0000-000000000001','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '1 day',gen_random_uuid(),gen_random_uuid(),'a1a1a1a1-0000-0000-0000-00000000000a');
    RAISE EXCEPTION 'both sources set should fail';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id)
     VALUES ('11111111-0000-0000-0000-000000000001','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '1 day',gen_random_uuid());
    RAISE EXCEPTION 'seat entitlement without issuing org should fail';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_order_id)
     VALUES ('11111111-0000-0000-0000-000000000001','dddddddd-0000-0000-0000-00000000000d',now(),now(),gen_random_uuid());
    RAISE EXCEPTION 'valid_until <= valid_from should fail';
  EXCEPTION WHEN check_violation THEN NULL; END;
END $$;

INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_order_id) VALUES
 ('11111111-0000-0000-0000-000000000001','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '1 day',gen_random_uuid());
-- P5.3: a seat entitlement needs a real seat of a real licence (foreign key and guard); the seat rows are fixtures written as the owner
INSERT INTO org_memberships(org_id,user_id) VALUES ('a1a1a1a1-0000-0000-0000-00000000000a','22222222-0000-0000-0000-000000000002');
INSERT INTO org_licenses(id,org_id,seats,months,price_vnd,activation,contract_no,status,valid_from,valid_until,created_by) VALUES
 ('11000000-0000-0000-0000-00000000000a','a1a1a1a1-0000-0000-0000-00000000000a',5,12,1000,'CONTRACT','C-1','ACTIVE',now() - interval '30 days','2099-01-01 00:00:00+00','11111111-0000-0000-0000-000000000001');
INSERT INTO org_license_courses(license_id,org_id,course_id) SELECT id, org_id, 'dddddddd-0000-0000-0000-00000000000d' FROM org_licenses WHERE id = '11000000-0000-0000-0000-00000000000a';
INSERT INTO seat_assignments(id,license_id,org_id,user_id,assigned_by) VALUES ('5ea70000-0000-0000-0000-00000000000a','11000000-0000-0000-0000-00000000000a','a1a1a1a1-0000-0000-0000-00000000000a','22222222-0000-0000-0000-000000000002','11111111-0000-0000-0000-000000000001');
INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id,source_org_id) VALUES
 ('22222222-0000-0000-0000-000000000002','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '1 day','5ea70000-0000-0000-0000-00000000000a','a1a1a1a1-0000-0000-0000-00000000000a');

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
