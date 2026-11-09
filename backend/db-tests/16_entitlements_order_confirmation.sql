-- P5 order confirmation: only English9x SYS_ADMIN writes order-sourced entitlements; nobody grants themselves access.
INSERT INTO users(id,email) VALUES
 ('a0000000-0000-0000-0000-000000000001','sys@x.test'),
 ('a0000000-0000-0000-0000-000000000002','author@x.test'),
 ('a0000000-0000-0000-0000-000000000003','learner@x.test'),
 ('a0000000-0000-0000-0000-000000000004','orgadmin@x.test'),
 ('a0000000-0000-0000-0000-000000000005','other@x.test');
INSERT INTO organizations(id,name,type) VALUES
 ('a1a1a1a1-0000-0000-0000-00000000000a','A','CENTER'),
 ('b1b1b1b1-0000-0000-0000-00000000000b','B','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('a1a1a1a1-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000003'),
 ('a1a1a1a1-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000004'),
 ('b1b1b1b1-0000-0000-0000-00000000000b','a0000000-0000-0000-0000-000000000005');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('a0000000-0000-0000-0000-000000000001',NULL,NULL,'SYS_ADMIN'),
 ('a0000000-0000-0000-0000-000000000002',NULL,'IELTS','AUTHOR'),
 ('a0000000-0000-0000-0000-000000000004','a1a1a1a1-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS') ON CONFLICT DO NOTHING;
INSERT INTO courses(id,owner_org_id,exam_code,code,title,status)
 VALUES ('dddddddd-0000-0000-0000-00000000000d',NULL,'IELTS','E9PUB1','c','PUBLISHED');
INSERT INTO entitlements(id,user_id,course_id,valid_from,valid_until,source_order_id) VALUES
 ('e0000000-0000-0000-0000-000000000001','a0000000-0000-0000-0000-000000000003','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '1 day',gen_random_uuid());

SET ROLE e9x_app;

-- English9x SYS_ADMIN (staff flag + SYS_ADMIN grant, English9x context): may confirm and adjust orders.
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000001',false),
       set_config('app.org_id','',false), set_config('app.e9x_staff','on',false), set_config('app.org_admin','',false);
DO $$
DECLARE n int;
BEGIN
  INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_order_id)
   VALUES ('a0000000-0000-0000-0000-000000000005','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '30 days',gen_random_uuid());
  UPDATE entitlements SET valid_until = now() + interval '60 days' WHERE id = 'e0000000-0000-0000-0000-000000000001';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 1, 'SYS_ADMIN extends an order entitlement';
  ASSERT (SELECT count(*) FROM entitlements) = 2, 'SYS_ADMIN sees entitlements';
  -- seat-sourced rows are the org admins' business, not the order flow's
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id,source_org_id)
     VALUES ('a0000000-0000-0000-0000-000000000005','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '1 day',gen_random_uuid(),'a1a1a1a1-0000-0000-0000-00000000000a');
    RAISE EXCEPTION 'SYS_ADMIN must not write seat-sourced rows';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  -- nobody deletes entitlements (revoke = end it): since P2.1 not even the privilege exists (it had no policy, so it only ever matched no row)
  BEGIN
    DELETE FROM entitlements WHERE id = 'e0000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'e9x_app must not hold DELETE on entitlements';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- AUTHOR: app.e9x_staff is on, but no SYS_ADMIN grant -> no billing writes
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000002',false),
       set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_order_id)
     VALUES ('a0000000-0000-0000-0000-000000000002','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '1 day',gen_random_uuid());
    RAISE EXCEPTION 'AUTHOR must not grant entitlements';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- Plain learner: cannot grant themselves or extend their own entitlement
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000003',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
DECLARE n int;
BEGIN
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_order_id)
     VALUES ('a0000000-0000-0000-0000-000000000003','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '365 days',gen_random_uuid());
    RAISE EXCEPTION 'learner must not grant themselves an order entitlement';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id,source_org_id)
     VALUES ('a0000000-0000-0000-0000-000000000003','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '365 days',gen_random_uuid(),'a1a1a1a1-0000-0000-0000-00000000000a');
    RAISE EXCEPTION 'learner must not grant themselves a seat entitlement';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  UPDATE entitlements SET valid_until = now() + interval '999 days' WHERE user_id = 'a0000000-0000-0000-0000-000000000003';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'learner must not extend their own entitlement';
END $$;

-- A learner who forges every flag and the org context still has no SYS_ADMIN / ORG_ADMIN grant behind them
SELECT set_config('app.org_id','a1a1a1a1-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','on',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_order_id)
     VALUES ('a0000000-0000-0000-0000-000000000003','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '365 days',gen_random_uuid());
    RAISE EXCEPTION 'forged staff flag must not allow an order entitlement';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id,source_org_id)
     VALUES ('a0000000-0000-0000-0000-000000000003','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '365 days',gen_random_uuid(),'a1a1a1a1-0000-0000-0000-00000000000a');
    RAISE EXCEPTION 'forged org_admin flag must not allow a seat entitlement';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- ORG_ADMIN (real grant): may not write order-sourced rows
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000004',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_order_id)
     VALUES ('a0000000-0000-0000-0000-000000000003','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '365 days',gen_random_uuid());
    RAISE EXCEPTION 'ORG_ADMIN must not confirm orders';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
