-- P6 seat assignment: ORG_ADMIN of the current org assigns / revokes seat entitlements for members of that org only.
INSERT INTO users(id,email) VALUES
 ('a0000000-0000-0000-0000-00000000000a','adminA@x.test'),
 ('a0000000-0000-0000-0000-00000000000b','adminB@x.test'),
 ('a0000000-0000-0000-0000-00000000000c','learnerA@x.test'),
 ('a0000000-0000-0000-0000-00000000000d','learnerB@x.test'),
 ('a0000000-0000-0000-0000-00000000000e','teacherA@x.test');
INSERT INTO organizations(id,name,type) VALUES
 ('a1a1a1a1-0000-0000-0000-00000000000a','A','CENTER'),
 ('b1b1b1b1-0000-0000-0000-00000000000b','B','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('a1a1a1a1-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-00000000000a'),
 ('a1a1a1a1-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-00000000000c'),
 ('a1a1a1a1-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-00000000000e'),
 ('b1b1b1b1-0000-0000-0000-00000000000b','a0000000-0000-0000-0000-00000000000b'),
 ('b1b1b1b1-0000-0000-0000-00000000000b','a0000000-0000-0000-0000-00000000000d');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('a0000000-0000-0000-0000-00000000000a','a1a1a1a1-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN'),
 ('a0000000-0000-0000-0000-00000000000b','b1b1b1b1-0000-0000-0000-00000000000b',NULL,'ORG_ADMIN'),
 ('a0000000-0000-0000-0000-00000000000e','a1a1a1a1-0000-0000-0000-00000000000a','IELTS','TEACHER');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS');
INSERT INTO courses(id,owner_org_id,exam_code,code,title,status)
 VALUES ('dddddddd-0000-0000-0000-00000000000d',NULL,'IELTS','E9PUB1','c','PUBLISHED');

SET ROLE e9x_app;

-- ORG_ADMIN of A assigns a seat to a member of A, then revokes it
SELECT set_config('app.user_id','a0000000-0000-0000-0000-00000000000a',false),
       set_config('app.org_id','a1a1a1a1-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','on',false);
DO $$
DECLARE n int;
BEGIN
  INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id)
   VALUES ('a0000000-0000-0000-0000-00000000000c','dddddddd-0000-0000-0000-00000000000d',now() - interval '1 day',now()+interval '90 days',gen_random_uuid());
  ASSERT (SELECT count(*) FROM entitlements) = 1, 'the org admin sees the seat entitlement of a member';
  UPDATE entitlements SET valid_until = now() WHERE user_id = 'a0000000-0000-0000-0000-00000000000c';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 1, 'revocation (ending the entitlement) works';
  -- not a member of A: cannot be given a seat by A's admin
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id)
     VALUES ('a0000000-0000-0000-0000-00000000000d','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '1 day',gen_random_uuid());
    RAISE EXCEPTION 'seat for a member of another org must fail';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  -- order-sourced rows are not an org admin's to write
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_order_id)
     VALUES ('a0000000-0000-0000-0000-00000000000c','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '1 day',gen_random_uuid());
    RAISE EXCEPTION 'order-sourced insert by org admin must fail';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  -- the update may not move the seat to a non-member either
  BEGIN
    UPDATE entitlements SET user_id = 'a0000000-0000-0000-0000-00000000000d' WHERE user_id = 'a0000000-0000-0000-0000-00000000000c';
    RAISE EXCEPTION 'reassigning a seat to another org member must fail';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- ORG_ADMIN of B (another org) cannot touch A's members, even while claiming A as the context
SELECT set_config('app.user_id','a0000000-0000-0000-0000-00000000000b',false),
       set_config('app.org_id','b1b1b1b1-0000-0000-0000-00000000000b',false);
DO $$
DECLARE n int;
BEGIN
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id)
     VALUES ('a0000000-0000-0000-0000-00000000000c','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '1 day',gen_random_uuid());
    RAISE EXCEPTION 'B admin must not assign seats to A members';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  UPDATE entitlements SET valid_until = now() + interval '999 days' WHERE user_id = 'a0000000-0000-0000-0000-00000000000c';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'B admin must not extend A member entitlement';
END $$;
SELECT set_config('app.org_id','a1a1a1a1-0000-0000-0000-00000000000a',false);
DO $$
BEGIN
  -- context forged to A, flag on, but the ORG_ADMIN grant is for B only
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id)
     VALUES ('a0000000-0000-0000-0000-00000000000c','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '1 day',gen_random_uuid());
    RAISE EXCEPTION 'forged org context must not authorise seats';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- A teacher and a plain member cannot assign seats; a learner cannot grant themselves one
SELECT set_config('app.user_id','a0000000-0000-0000-0000-00000000000e',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id)
     VALUES ('a0000000-0000-0000-0000-00000000000e','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '1 day',gen_random_uuid());
    RAISE EXCEPTION 'teacher must not assign seats';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
SELECT set_config('app.user_id','a0000000-0000-0000-0000-00000000000c',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_seat_id)
     VALUES ('a0000000-0000-0000-0000-00000000000c','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '365 days',gen_random_uuid());
    RAISE EXCEPTION 'learner with a forged org_admin flag must not grant themselves a seat';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
