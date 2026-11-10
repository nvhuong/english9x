-- A user who belongs to A and B: an ORG_ADMIN of A must not read, extend or issue seats on behalf of B.
INSERT INTO users(id,email) VALUES
 ('a0000000-0000-0000-0000-00000000000a','adminA@x.test'),
 ('a0000000-0000-0000-0000-00000000000b','adminB@x.test'),
 ('a0000000-0000-0000-0000-00000000000c','both@x.test');
INSERT INTO organizations(id,name,type) VALUES
 ('a1a1a1a1-0000-0000-0000-00000000000a','A','CENTER'),
 ('b1b1b1b1-0000-0000-0000-00000000000b','B','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('a1a1a1a1-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-00000000000a'),
 ('a1a1a1a1-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-00000000000c'),
 ('b1b1b1b1-0000-0000-0000-00000000000b','a0000000-0000-0000-0000-00000000000b'),
 ('b1b1b1b1-0000-0000-0000-00000000000b','a0000000-0000-0000-0000-00000000000c');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('a0000000-0000-0000-0000-00000000000a','a1a1a1a1-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN'),
 ('a0000000-0000-0000-0000-00000000000b','b1b1b1b1-0000-0000-0000-00000000000b',NULL,'ORG_ADMIN');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS') ON CONFLICT DO NOTHING;
INSERT INTO courses(id,owner_org_id,exam_code,code,title,status)
 VALUES ('dddddddd-0000-0000-0000-00000000000d',NULL,'IELTS','E9PUB1','c','PUBLISHED');
-- P5.3: a seat entitlement needs a real seat of a real licence (foreign key and guard); the seat rows are fixtures written as the owner
INSERT INTO org_licenses(id,org_id,seats,months,price_vnd,activation,contract_no,status,valid_from,valid_until,created_by) VALUES
 ('11000000-0000-0000-0000-00000000000a','a1a1a1a1-0000-0000-0000-00000000000a',5,12,1000,'CONTRACT','C-1','ACTIVE',now() - interval '30 days','2099-01-01 00:00:00+00','a0000000-0000-0000-0000-00000000000a');
INSERT INTO org_license_courses(license_id,org_id,course_id) SELECT id, org_id, 'dddddddd-0000-0000-0000-00000000000d' FROM org_licenses WHERE id = '11000000-0000-0000-0000-00000000000a';
INSERT INTO org_licenses(id,org_id,seats,months,price_vnd,activation,contract_no,status,valid_from,valid_until,created_by) VALUES
 ('11000000-0000-0000-0000-00000000000b','b1b1b1b1-0000-0000-0000-00000000000b',5,12,1000,'CONTRACT','C-1','ACTIVE',now() - interval '30 days','2099-01-01 00:00:00+00','a0000000-0000-0000-0000-00000000000b');
INSERT INTO org_license_courses(license_id,org_id,course_id) SELECT id, org_id, 'dddddddd-0000-0000-0000-00000000000d' FROM org_licenses WHERE id = '11000000-0000-0000-0000-00000000000b';
INSERT INTO seat_assignments(id,license_id,org_id,user_id,assigned_by) VALUES ('5ea70000-0000-0000-0000-00000000000a','11000000-0000-0000-0000-00000000000a','a1a1a1a1-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-00000000000c','a0000000-0000-0000-0000-00000000000a');
INSERT INTO seat_assignments(id,license_id,org_id,user_id,assigned_by) VALUES ('5ea70000-0000-0000-0000-00000000000b','11000000-0000-0000-0000-00000000000b','b1b1b1b1-0000-0000-0000-00000000000b','a0000000-0000-0000-0000-00000000000c','a0000000-0000-0000-0000-00000000000b');
-- the seat was issued by B
INSERT INTO entitlements(id,user_id,course_id,valid_from,valid_until,source_seat_id,source_org_id) VALUES
 ('e0000000-0000-0000-0000-0000000000b1','a0000000-0000-0000-0000-00000000000c','dddddddd-0000-0000-0000-00000000000d',
  now() - interval '1 day',now()+interval '1 day','5ea70000-0000-0000-0000-00000000000b','b1b1b1b1-0000-0000-0000-00000000000b');

SET ROLE e9x_app;
SELECT set_config('app.e9x_staff','',false), set_config('app.org_admin','on',false);

-- admin of A, context A
SELECT set_config('app.user_id','a0000000-0000-0000-0000-00000000000a',false),
       set_config('app.org_id','a1a1a1a1-0000-0000-0000-00000000000a',false);
DO $$
DECLARE n int;
BEGIN
  ASSERT (SELECT count(*) FROM entitlements) = 0, 'A admin must not read a seat issued by B';
  UPDATE entitlements SET valid_until = now() + interval '999 days' WHERE id = 'e0000000-0000-0000-0000-0000000000b1';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'A admin must not extend a seat issued by B';
  -- issuing a seat in B's name, or leaving the issuer out, is refused
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id,source_org_id)
     VALUES ('a0000000-0000-0000-0000-00000000000c','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '9 days',gen_random_uuid(),'b1b1b1b1-0000-0000-0000-00000000000b');
    RAISE EXCEPTION 'A admin must not issue a seat in the name of B';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id)
     VALUES ('a0000000-0000-0000-0000-00000000000c','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '9 days',gen_random_uuid());
    RAISE EXCEPTION 'a seat without issuing org must fail';
  EXCEPTION WHEN check_violation OR insufficient_privilege THEN NULL; END;
  -- the legitimate seat of A for the same dual member works and is the only one A sees
  INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id,source_org_id)
   VALUES ('a0000000-0000-0000-0000-00000000000c','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '9 days','5ea70000-0000-0000-0000-00000000000a','a1a1a1a1-0000-0000-0000-00000000000a');
  ASSERT (SELECT count(*) FROM entitlements) = 1, 'A admin sees only the seat A issued';
END $$;

-- admin of B still manages B's seat, and cannot see A's
SELECT set_config('app.user_id','a0000000-0000-0000-0000-00000000000b',false),
       set_config('app.org_id','b1b1b1b1-0000-0000-0000-00000000000b',false);
DO $$
DECLARE n int;
BEGIN
  ASSERT (SELECT count(*) FROM entitlements) = 1, 'B admin sees only B seat';
  UPDATE entitlements SET valid_until = now() + interval '12 hours' WHERE id = 'e0000000-0000-0000-0000-0000000000b1';   -- a seat can only be shortened (P5.3 guard)
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 1, 'B admin manages the seat B issued';
END $$;
