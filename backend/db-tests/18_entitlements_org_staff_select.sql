-- spec §8: ORG_ADMIN / TEACHER of an org see the seat entitlements of that org's members, nothing else.
INSERT INTO users(id,email) VALUES
 ('a0000000-0000-0000-0000-00000000000a','adminA@x.test'),
 ('a0000000-0000-0000-0000-00000000000b','adminB@x.test'),
 ('a0000000-0000-0000-0000-00000000000c','learnerA1@x.test'),
 ('a0000000-0000-0000-0000-00000000000d','learnerB@x.test'),
 ('a0000000-0000-0000-0000-00000000000e','teacherA@x.test'),
 ('a0000000-0000-0000-0000-00000000000f','learnerA2@x.test');
INSERT INTO organizations(id,name,type) VALUES
 ('a1a1a1a1-0000-0000-0000-00000000000a','A','CENTER'),
 ('b1b1b1b1-0000-0000-0000-00000000000b','B','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('a1a1a1a1-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-00000000000a'),
 ('a1a1a1a1-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-00000000000c'),
 ('a1a1a1a1-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-00000000000e'),
 ('a1a1a1a1-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-00000000000f'),
 ('b1b1b1b1-0000-0000-0000-00000000000b','a0000000-0000-0000-0000-00000000000b'),
 ('b1b1b1b1-0000-0000-0000-00000000000b','a0000000-0000-0000-0000-00000000000d');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('a0000000-0000-0000-0000-00000000000a','a1a1a1a1-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN'),
 ('a0000000-0000-0000-0000-00000000000b','b1b1b1b1-0000-0000-0000-00000000000b',NULL,'ORG_ADMIN'),
 ('a0000000-0000-0000-0000-00000000000e','a1a1a1a1-0000-0000-0000-00000000000a','IELTS','TEACHER');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS');
INSERT INTO courses(id,owner_org_id,exam_code,code,title,status)
 VALUES ('dddddddd-0000-0000-0000-00000000000d',NULL,'IELTS','E9PUB1','c','PUBLISHED');
INSERT INTO entitlements(id,user_id,course_id,valid_from,valid_until,source_seat_id,source_org_id) VALUES
 ('e0000000-0000-0000-0000-0000000000a1','a0000000-0000-0000-0000-00000000000c','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '1 day',gen_random_uuid(),'a1a1a1a1-0000-0000-0000-00000000000a');
INSERT INTO entitlements(id,user_id,course_id,valid_from,valid_until,source_order_id) VALUES
 ('e0000000-0000-0000-0000-0000000000a2','a0000000-0000-0000-0000-00000000000c','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '1 day',gen_random_uuid());
INSERT INTO entitlements(id,user_id,course_id,valid_from,valid_until,source_seat_id,source_org_id) VALUES
 ('e0000000-0000-0000-0000-0000000000b1','a0000000-0000-0000-0000-00000000000d','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '1 day',gen_random_uuid(),'b1b1b1b1-0000-0000-0000-00000000000b');

SET ROLE e9x_app;
SELECT set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);

-- ORG_ADMIN of A: the member's seat entitlement, not the member's personal purchase, not org B
SELECT set_config('app.user_id','a0000000-0000-0000-0000-00000000000a',false),
       set_config('app.org_id','a1a1a1a1-0000-0000-0000-00000000000a',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM entitlements) = 1, 'org admin sees exactly one entitlement';
  ASSERT (SELECT id FROM entitlements) = 'e0000000-0000-0000-0000-0000000000a1', 'it is the seat entitlement';
END $$;

-- TEACHER of A
SELECT set_config('app.user_id','a0000000-0000-0000-0000-00000000000e',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM entitlements) = 1, 'teacher sees the seat entitlement of the org member';
END $$;

-- another member of A without a staff role: nothing of other people's
SELECT set_config('app.user_id','a0000000-0000-0000-0000-00000000000f',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM entitlements) = 0, 'a plain member sees none of their classmates entitlements';
END $$;

-- the learner sees their own two
SELECT set_config('app.user_id','a0000000-0000-0000-0000-00000000000c',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM entitlements) = 2, 'learner sees their own entitlements';
END $$;

-- ORG_ADMIN of B sees only B's
SELECT set_config('app.user_id','a0000000-0000-0000-0000-00000000000b',false),
       set_config('app.org_id','b1b1b1b1-0000-0000-0000-00000000000b',false), set_config('app.org_admin','on',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM entitlements) = 1 AND (SELECT id FROM entitlements) = 'e0000000-0000-0000-0000-0000000000b1',
         'B admin sees only B member seat entitlement';
END $$;

-- ORG_ADMIN of A claiming org B as the context (no grant, no membership there), and with no org context at all
SELECT set_config('app.user_id','a0000000-0000-0000-0000-00000000000a',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM entitlements) = 0, 'forged org context shows nothing';
END $$;
SELECT set_config('app.org_id','',false), set_config('app.org_admin','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM entitlements) = 0, 'no org context -> no org-wide view';
END $$;
