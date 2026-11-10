-- P5.3: organisation licences and seats, enforced by the database for the real request role e9x_app (never the owner):
--   * only a real SYS_ADMIN writes a licence; the ORG_ADMIN of the organisation reads it, another organisation's does not
--   * capacity: a licence of N seats takes N live seats and no more, whatever the application does; a revoked seat is free again
--   * a seat is for a MEMBER of the organisation, on an ACTIVE licence of that organisation
--   * a seat entitlement: a real live seat of that person and organisation, a course of the licence, never past the licence's end,
--     and afterwards only ever shortened; revoking the seat ends it in the same statement
--   * the audit record of seat.assign / seat.revoke holds ids, never an email (the record itself is written by AuditLog#record)
INSERT INTO users(id,email,email_verified_at) VALUES
 ('d0000000-0000-0000-0000-000000000001','sys@x.test', now()),
 ('d0000000-0000-0000-0000-000000000002','admin-a@x.test', now()),
 ('d0000000-0000-0000-0000-000000000003','admin-b@x.test', now()),
 ('d0000000-0000-0000-0000-000000000004','m1@x.test', now()),
 ('d0000000-0000-0000-0000-000000000005','m2@x.test', now()),
 ('d0000000-0000-0000-0000-000000000006','m3@x.test', now()),
 ('d0000000-0000-0000-0000-000000000007','outsider@x.test', now());
INSERT INTO organizations(id,name,type) VALUES
 ('a1a1a1a1-0000-0000-0000-00000000000a','A','ENTERPRISE'),
 ('b1b1b1b1-0000-0000-0000-00000000000b','B','ENTERPRISE');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('a1a1a1a1-0000-0000-0000-00000000000a','d0000000-0000-0000-0000-000000000002'),
 ('a1a1a1a1-0000-0000-0000-00000000000a','d0000000-0000-0000-0000-000000000004'),
 ('a1a1a1a1-0000-0000-0000-00000000000a','d0000000-0000-0000-0000-000000000005'),
 ('a1a1a1a1-0000-0000-0000-00000000000a','d0000000-0000-0000-0000-000000000006'),
 ('b1b1b1b1-0000-0000-0000-00000000000b','d0000000-0000-0000-0000-000000000003'),
 ('b1b1b1b1-0000-0000-0000-00000000000b','d0000000-0000-0000-0000-000000000007');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('d0000000-0000-0000-0000-000000000001',NULL,NULL,'SYS_ADMIN'),
 ('d0000000-0000-0000-0000-000000000002','a1a1a1a1-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN'),
 ('d0000000-0000-0000-0000-000000000003','b1b1b1b1-0000-0000-0000-00000000000b',NULL,'ORG_ADMIN');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS') ON CONFLICT DO NOTHING;
INSERT INTO courses(id,owner_org_id,exam_code,code,title,status) VALUES
 ('c1000000-0000-0000-0000-000000000001',NULL,'IELTS','LIC001','in licence','PUBLISHED'),
 ('c1000000-0000-0000-0000-000000000002',NULL,'IELTS','LIC002','not in licence','PUBLISHED');

SET ROLE e9x_app;

-- ---- the SYS_ADMIN writes the licence ----------------------------------------------------------------------------------------------
SELECT set_config('app.user_id','d0000000-0000-0000-0000-000000000001',false), set_config('app.org_id','',false),
       set_config('app.e9x_staff','on',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  INSERT INTO org_licenses(id,org_id,seats,months,price_vnd,activation,contract_no,status,valid_from,valid_until,created_by) VALUES
   ('11000000-0000-0000-0000-00000000000a','a1a1a1a1-0000-0000-0000-00000000000a',2,6,5000000,'CONTRACT','HD-1','ACTIVE',now() - interval '1 day',now() + interval '180 days','d0000000-0000-0000-0000-000000000001'),
   ('11000000-0000-0000-0000-00000000000e','a1a1a1a1-0000-0000-0000-00000000000a',2,6,5000000,'CONTRACT','HD-2','ACTIVE',now() - interval '400 days',now() - interval '1 day','d0000000-0000-0000-0000-000000000001');
  INSERT INTO org_license_courses(license_id,org_id,course_id) VALUES
   ('11000000-0000-0000-0000-00000000000a','a1a1a1a1-0000-0000-0000-00000000000a','c1000000-0000-0000-0000-000000000001'),
   ('11000000-0000-0000-0000-00000000000e','a1a1a1a1-0000-0000-0000-00000000000a','c1000000-0000-0000-0000-000000000001');
  -- the state CHECK: ACTIVE needs its dates, a QR licence cannot start active
  BEGIN
    INSERT INTO org_licenses(org_id,seats,months,price_vnd,activation,status,created_by)
     VALUES ('a1a1a1a1-0000-0000-0000-00000000000a',1,1,1,'QR','ACTIVE','d0000000-0000-0000-0000-000000000001');
    RAISE EXCEPTION 'ACTIVE without dates must fail';
  EXCEPTION WHEN check_violation OR insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO org_licenses(org_id,seats,months,price_vnd,activation,status,valid_from,valid_until,created_by)
     VALUES ('a1a1a1a1-0000-0000-0000-00000000000a',1,1,1,'QR','ACTIVE',now(),now() + interval '1 day','d0000000-0000-0000-0000-000000000001');
    RAISE EXCEPTION 'a QR licence cannot start ACTIVE (it waits for its order)' ;
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO org_licenses(org_id,seats,months,price_vnd,activation,contract_no,status,created_by)
     VALUES ('a1a1a1a1-0000-0000-0000-00000000000a',1,1,1,'CONTRACT','X','PENDING_PAYMENT','d0000000-0000-0000-0000-000000000001');
    RAISE EXCEPTION 'a CONTRACT licence cannot wait for payment';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  -- an ACTIVE licence is not decided again
  UPDATE org_licenses SET status = 'CANCELLED', valid_from = NULL, valid_until = NULL WHERE id = '11000000-0000-0000-0000-00000000000a';
  ASSERT (SELECT status FROM org_licenses WHERE id = '11000000-0000-0000-0000-00000000000a') = 'ACTIVE', 'the SYS_ADMIN cannot cancel an ACTIVE licence through the decision policy';
  BEGIN UPDATE org_licenses SET seats = 99; RAISE EXCEPTION 'seats are not updatable'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN UPDATE org_licenses SET price_vnd = 1; RAISE EXCEPTION 'the price is locked'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- ---- who may write a licence: nobody else ------------------------------------------------------------------------------------------
SELECT set_config('app.user_id','d0000000-0000-0000-0000-000000000002',false),
       set_config('app.org_id','a1a1a1a1-0000-0000-0000-00000000000a',false), set_config('app.e9x_staff','',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  BEGIN
    INSERT INTO org_licenses(org_id,seats,months,price_vnd,activation,contract_no,status,valid_from,valid_until,created_by)
     VALUES ('a1a1a1a1-0000-0000-0000-00000000000a',1000,6,1,'CONTRACT','X','ACTIVE',now(),now() + interval '1 day','d0000000-0000-0000-0000-000000000002');
    RAISE EXCEPTION 'an ORG_ADMIN must not write a licence';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO org_license_courses(license_id,org_id,course_id) SELECT id, org_id, 'c1000000-0000-0000-0000-000000000002' FROM org_licenses WHERE id = '11000000-0000-0000-0000-00000000000a';
    RAISE EXCEPTION 'an ORG_ADMIN must not add a course to its licence';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  ASSERT (SELECT count(*) FROM org_licenses) = 2, 'the ORG_ADMIN of A reads A''s licences';
  ASSERT (SELECT count(*) FROM org_license_courses) = 2, 'and their courses';
END $$;

-- ---- seats: capacity, membership, state of the licence -----------------------------------------------------------------------------
DO $$
DECLARE failed boolean;
BEGIN
  INSERT INTO seat_assignments(id,license_id,org_id,user_id,assigned_by) VALUES
   ('5ea70000-0000-0000-0000-000000000001','11000000-0000-0000-0000-00000000000a','a1a1a1a1-0000-0000-0000-00000000000a','d0000000-0000-0000-0000-000000000004','d0000000-0000-0000-0000-000000000002'),
   ('5ea70000-0000-0000-0000-000000000002','11000000-0000-0000-0000-00000000000a','a1a1a1a1-0000-0000-0000-00000000000a','d0000000-0000-0000-0000-000000000005','d0000000-0000-0000-0000-000000000002');
  -- capacity: the third seat of a 2-seat licence
  failed := false;
  BEGIN
    INSERT INTO seat_assignments(license_id,org_id,user_id,assigned_by) VALUES
     ('11000000-0000-0000-0000-00000000000a','a1a1a1a1-0000-0000-0000-00000000000a','d0000000-0000-0000-0000-000000000006','d0000000-0000-0000-0000-000000000002');
  EXCEPTION WHEN check_violation THEN failed := true; END;
  ASSERT failed, 'a third seat on a 2-seat licence is refused by the database';
  -- one live seat per person
  failed := false;
  BEGIN
    INSERT INTO seat_assignments(license_id,org_id,user_id,assigned_by) VALUES
     ('11000000-0000-0000-0000-00000000000a','a1a1a1a1-0000-0000-0000-00000000000a','d0000000-0000-0000-0000-000000000004','d0000000-0000-0000-0000-000000000002');
  EXCEPTION WHEN unique_violation OR check_violation THEN failed := true; END;
  ASSERT failed, 'a person holds one live seat per licence';
  -- a seat for somebody who is not a member of the organisation
  failed := false;
  BEGIN
    INSERT INTO seat_assignments(license_id,org_id,user_id,assigned_by) VALUES
     ('11000000-0000-0000-0000-00000000000e','a1a1a1a1-0000-0000-0000-00000000000a','d0000000-0000-0000-0000-000000000007','d0000000-0000-0000-0000-000000000002');
  EXCEPTION WHEN check_violation THEN failed := true; END;
  ASSERT failed, 'a member of another organisation cannot hold a seat';
  -- an expired licence takes no seat
  failed := false;
  BEGIN
    INSERT INTO seat_assignments(license_id,org_id,user_id,assigned_by) VALUES
     ('11000000-0000-0000-0000-00000000000e','a1a1a1a1-0000-0000-0000-00000000000a','d0000000-0000-0000-0000-000000000006','d0000000-0000-0000-0000-000000000002');
  EXCEPTION WHEN check_violation THEN failed := true; END;
  ASSERT failed, 'an expired licence takes no seat';
  -- the seat row must name the organisation of the context, and the assigner must be the acting person
  failed := false;
  BEGIN
    INSERT INTO seat_assignments(license_id,org_id,user_id,assigned_by) VALUES
     ('11000000-0000-0000-0000-00000000000a','b1b1b1b1-0000-0000-0000-00000000000b','d0000000-0000-0000-0000-000000000006','d0000000-0000-0000-0000-000000000002');
  EXCEPTION WHEN insufficient_privilege OR foreign_key_violation THEN failed := true; END;
  ASSERT failed, 'a seat in the name of another organisation is refused';
  failed := false;
  BEGIN
    INSERT INTO seat_assignments(license_id,org_id,user_id,assigned_by) VALUES
     ('11000000-0000-0000-0000-00000000000a','a1a1a1a1-0000-0000-0000-00000000000a','d0000000-0000-0000-0000-000000000006','d0000000-0000-0000-0000-000000000001');
  EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'the assigner is the acting person';
  ASSERT (SELECT count(*) FROM seat_assignments WHERE revoked_at IS NULL) = 2, 'two live seats, no more';
END $$;

-- ---- seat entitlements -------------------------------------------------------------------------------------------------------------
DO $$
DECLARE failed boolean;
BEGIN
  INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id,source_org_id)
   SELECT 'd0000000-0000-0000-0000-000000000004','c1000000-0000-0000-0000-000000000001',now(),valid_until,'5ea70000-0000-0000-0000-000000000001','a1a1a1a1-0000-0000-0000-00000000000a'
     FROM org_licenses WHERE id = '11000000-0000-0000-0000-00000000000a';
  -- a course outside the licence
  failed := false;
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id,source_org_id)
     VALUES ('d0000000-0000-0000-0000-000000000004','c1000000-0000-0000-0000-000000000002',now(),now() + interval '10 days','5ea70000-0000-0000-0000-000000000001','a1a1a1a1-0000-0000-0000-00000000000a');
  EXCEPTION WHEN check_violation THEN failed := true; END;
  ASSERT failed, 'a course outside org_license_courses is refused';
  -- past the licence's end
  failed := false;
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id,source_org_id)
     VALUES ('d0000000-0000-0000-0000-000000000005','c1000000-0000-0000-0000-000000000001',now(),now() + interval '181 days','5ea70000-0000-0000-0000-000000000002','a1a1a1a1-0000-0000-0000-00000000000a');
  EXCEPTION WHEN check_violation THEN failed := true; END;
  ASSERT failed, 'an entitlement cannot outlive the licence';
  -- another person on somebody's seat
  failed := false;
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id,source_org_id)
     VALUES ('d0000000-0000-0000-0000-000000000005','c1000000-0000-0000-0000-000000000001',now(),now() + interval '10 days','5ea70000-0000-0000-0000-000000000001','a1a1a1a1-0000-0000-0000-00000000000a');
  EXCEPTION WHEN check_violation THEN failed := true; END;
  ASSERT failed, 'the seat belongs to its holder only';
  -- a seat that does not exist (the arbitrary seat row of before P5.3)
  failed := false;
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id,source_org_id)
     VALUES ('d0000000-0000-0000-0000-000000000005','c1000000-0000-0000-0000-000000000001',now(),now() + interval '10 days',gen_random_uuid(),'a1a1a1a1-0000-0000-0000-00000000000a');
  EXCEPTION WHEN foreign_key_violation OR check_violation THEN failed := true; END;
  ASSERT failed, 'an invented seat id is refused';
  -- extending: only shortening is allowed
  failed := false;
  BEGIN
    UPDATE entitlements SET valid_until = valid_until + interval '1 day' WHERE user_id = 'd0000000-0000-0000-0000-000000000004';
  EXCEPTION WHEN check_violation THEN failed := true; END;
  ASSERT failed, 'a seat entitlement cannot be extended';
  failed := false;
  BEGIN
    UPDATE entitlements SET course_id = 'c1000000-0000-0000-0000-000000000002' WHERE user_id = 'd0000000-0000-0000-0000-000000000004';
  EXCEPTION WHEN check_violation THEN failed := true; END;
  ASSERT failed, 'a seat entitlement cannot be moved to another course';
END $$;

-- ---- revoking: the seat is free again, and its entitlements end at the same instant --------------------------------------------------
DO $$
DECLARE failed boolean; n int; ended timestamptz;
BEGIN
  failed := false;
  BEGIN UPDATE seat_assignments SET user_id = 'd0000000-0000-0000-0000-000000000006' WHERE id = '5ea70000-0000-0000-0000-000000000001'; EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'only revoked_at is updatable';
  UPDATE seat_assignments SET revoked_at = now() WHERE id = '5ea70000-0000-0000-0000-000000000001';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 1, 'the ORG_ADMIN revokes the seat';
  SELECT valid_until INTO ended FROM entitlements WHERE source_seat_id = '5ea70000-0000-0000-0000-000000000001';
  ASSERT ended <= now() + interval '1 second', 'the entitlement ended with the revocation';
  failed := false;
  BEGIN UPDATE seat_assignments SET revoked_at = NULL WHERE id = '5ea70000-0000-0000-0000-000000000001'; EXCEPTION WHEN check_violation THEN failed := true; END;
  ASSERT failed, 'a revoked seat is not given back by an update';
  failed := false;
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id,source_org_id)
     SELECT 'd0000000-0000-0000-0000-000000000004','c1000000-0000-0000-0000-000000000001',now(),valid_until,'5ea70000-0000-0000-0000-000000000001','a1a1a1a1-0000-0000-0000-00000000000a'
       FROM org_licenses WHERE id = '11000000-0000-0000-0000-00000000000a';
  EXCEPTION WHEN check_violation THEN failed := true; END;
  ASSERT failed, 'a revoked seat takes no new entitlement';
  -- the seat is free: the third person gets it
  INSERT INTO seat_assignments(license_id,org_id,user_id,assigned_by) VALUES
   ('11000000-0000-0000-0000-00000000000a','a1a1a1a1-0000-0000-0000-00000000000a','d0000000-0000-0000-0000-000000000006','d0000000-0000-0000-0000-000000000002');
  ASSERT (SELECT count(*) FROM seat_assignments WHERE revoked_at IS NULL) = 2, 'two live seats again';
END $$;

-- ---- another organisation's ORG_ADMIN, a learner ------------------------------------------------------------------------------------
SELECT set_config('app.user_id','d0000000-0000-0000-0000-000000000003',false), set_config('app.org_id','b1b1b1b1-0000-0000-0000-00000000000b',false);
DO $$
DECLARE failed boolean;
BEGIN
  ASSERT (SELECT count(*) FROM org_licenses) = 0, 'B''s ORG_ADMIN sees no licence of A';
  ASSERT (SELECT count(*) FROM seat_assignments) = 0, 'and no seat of A';
  failed := false;
  BEGIN
    INSERT INTO seat_assignments(license_id,org_id,user_id,assigned_by) VALUES
     ('11000000-0000-0000-0000-00000000000a','b1b1b1b1-0000-0000-0000-00000000000b','d0000000-0000-0000-0000-000000000007','d0000000-0000-0000-0000-000000000003');
  EXCEPTION WHEN foreign_key_violation OR check_violation OR insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'B cannot take seats of A''s licence';
END $$;
SELECT set_config('app.user_id','d0000000-0000-0000-0000-000000000004',false), set_config('app.org_id','a1a1a1a1-0000-0000-0000-00000000000a',false), set_config('app.org_admin','on',false);
DO $$
DECLARE failed boolean;
BEGIN
  ASSERT (SELECT count(*) FROM org_licenses) = 0, 'a member sees no licence even with a forged org_admin flag (no ORG_ADMIN grant)';
  failed := false;
  BEGIN
    INSERT INTO seat_assignments(license_id,org_id,user_id,assigned_by) VALUES
     ('11000000-0000-0000-0000-00000000000a','a1a1a1a1-0000-0000-0000-00000000000a','d0000000-0000-0000-0000-000000000004','d0000000-0000-0000-0000-000000000004');
  EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'a learner cannot take a seat for themselves';
END $$;

-- ---- the order of a QR licence: filed by the SYS_ADMIN for an ORG_ADMIN of the organisation, at the licence's price ---------------------
SELECT set_config('app.user_id','d0000000-0000-0000-0000-000000000001',false), set_config('app.org_id','',false),
       set_config('app.e9x_staff','on',false), set_config('app.org_admin','',false);
DO $$
DECLARE failed boolean;
BEGIN
  INSERT INTO org_licenses(id,org_id,seats,months,price_vnd,activation,status,created_by) VALUES
   ('11000000-0000-0000-0000-0000000000f1','a1a1a1a1-0000-0000-0000-00000000000a',3,12,9000000,'QR','PENDING_PAYMENT','d0000000-0000-0000-0000-000000000001');
  INSERT INTO org_license_courses(license_id,org_id,course_id) SELECT id, org_id, 'c1000000-0000-0000-0000-000000000001' FROM org_licenses WHERE id = '11000000-0000-0000-0000-0000000000f1';
  failed := false;
  BEGIN
    INSERT INTO orders(id,code,user_id,license_id,plan_months,amount_vnd) VALUES
     ('0d000000-0000-0000-0000-000000000001','LICAAA','d0000000-0000-0000-0000-000000000002','11000000-0000-0000-0000-0000000000f1',12,1);
  EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'the order is at the licence''s price';
  failed := false;
  BEGIN
    INSERT INTO orders(id,code,user_id,license_id,plan_months,amount_vnd) VALUES
     ('0d000000-0000-0000-0000-000000000001','LICAAA','d0000000-0000-0000-0000-000000000007','11000000-0000-0000-0000-0000000000f1',12,9000000);
  EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'the payer is an ORG_ADMIN of the licence''s organisation';
  INSERT INTO orders(id,code,user_id,license_id,plan_months,amount_vnd) VALUES
   ('0d000000-0000-0000-0000-000000000001','LICAAA','d0000000-0000-0000-0000-000000000002','11000000-0000-0000-0000-0000000000f1',12,9000000);
  INSERT INTO order_items(order_id,user_id,course_id,course_code) VALUES
   ('0d000000-0000-0000-0000-000000000001','d0000000-0000-0000-0000-000000000002','c1000000-0000-0000-0000-000000000001','LIC001');
  failed := false;
  BEGIN
    INSERT INTO order_items(order_id,user_id,course_id,course_code) VALUES
     ('0d000000-0000-0000-0000-000000000001','d0000000-0000-0000-0000-000000000002','c1000000-0000-0000-0000-000000000002','LIC002');
  EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'an item of a licence order is one of the licence''s courses';
END $$;
-- a learner can no longer place an order that names a licence
SELECT set_config('app.user_id','d0000000-0000-0000-0000-000000000002',false), set_config('app.e9x_staff','',false);
DO $$
DECLARE failed boolean;
BEGIN
  failed := false;
  BEGIN
    INSERT INTO orders(code,user_id,license_id,plan_months,amount_vnd) VALUES
     ('LICBBB','d0000000-0000-0000-0000-000000000002','11000000-0000-0000-0000-0000000000f1',12,9000000);
  EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'a person cannot file the order of a licence';
END $$;

-- ---- the audit records of seat.assign / seat.revoke: ids only ----------------------------------------------------------------------
-- (written by AuditLog#record as the acting ORG_ADMIN; the table's own rules are in 35_audit_log.sql)
SELECT set_config('app.user_id','d0000000-0000-0000-0000-000000000002',false), set_config('app.org_id','a1a1a1a1-0000-0000-0000-00000000000a',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  INSERT INTO audit_log(action,target_type,target_id,detail) VALUES
   ('seat.assign','seat','5ea70000-0000-0000-0000-000000000001',
    '{"licenseId":"11000000-0000-0000-0000-00000000000a","userId":"d0000000-0000-0000-0000-000000000004","courseIds":["c1000000-0000-0000-0000-000000000001"]}'),
   ('seat.revoke','seat','5ea70000-0000-0000-0000-000000000001',
    '{"licenseId":"11000000-0000-0000-0000-00000000000a","userId":"d0000000-0000-0000-0000-000000000004","courseIds":["c1000000-0000-0000-0000-000000000001"]}');
END $$;
RESET ROLE;
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM audit_log WHERE action IN ('seat.assign','seat.revoke') AND target_type = 'seat') = 2, 'both records are filed under the seat';
  ASSERT (SELECT count(*) FROM audit_log WHERE action IN ('seat.assign','seat.revoke') AND detail::text LIKE '%@%') = 0, 'and carry no email address';
  ASSERT (SELECT count(*) FROM audit_log WHERE action IN ('seat.assign','seat.revoke') AND actor_id = 'd0000000-0000-0000-0000-000000000002' AND org_id = 'a1a1a1a1-0000-0000-0000-00000000000a') = 2, 'by the acting ORG_ADMIN in their organisation';
END $$;
