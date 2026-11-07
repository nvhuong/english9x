-- The two-step attack: grant yourself SYS_ADMIN (or ORG_ADMIN), then use it for billing writes.
-- A flag alone, or a real English9x staff role alone, must not be enough for step one.
INSERT INTO users(id,email) VALUES
 ('a0000000-0000-0000-0000-000000000001','sys@x.test'),
 ('a0000000-0000-0000-0000-000000000002','author@x.test'),
 ('a0000000-0000-0000-0000-000000000003','learner@x.test'),
 ('a0000000-0000-0000-0000-000000000004','target@x.test');
INSERT INTO organizations(id,name,type) VALUES ('a1a1a1a1-0000-0000-0000-00000000000a','A','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('a1a1a1a1-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000003');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('a0000000-0000-0000-0000-000000000001',NULL,NULL,'SYS_ADMIN'),
 ('a0000000-0000-0000-0000-000000000002',NULL,'IELTS','AUTHOR');
INSERT INTO exams(code,name) VALUES ('IELTS','IELTS');
INSERT INTO courses(id,owner_org_id,exam_code,code,title,status)
 VALUES ('dddddddd-0000-0000-0000-00000000000d',NULL,'IELTS','E9PUB1','c','PUBLISHED');

SET ROLE e9x_app;

-- a real AUTHOR with a legitimate staff flag
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000002',false),
       set_config('app.org_id','',false), set_config('app.e9x_staff','on',false), set_config('app.org_admin','',false);
DO $$
DECLARE n int;
BEGIN
  BEGIN
    INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('a0000000-0000-0000-0000-000000000002',NULL,NULL,'SYS_ADMIN');
    RAISE EXCEPTION 'AUTHOR must not grant themselves SYS_ADMIN';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('a0000000-0000-0000-0000-000000000004',NULL,NULL,'SYS_ADMIN');
    RAISE EXCEPTION 'AUTHOR must not grant SYS_ADMIN to anybody';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO org_memberships(org_id,user_id) VALUES ('a1a1a1a1-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000002');
    RAISE EXCEPTION 'AUTHOR must not add themselves to an org';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  UPDATE role_grants SET role = 'SYS_ADMIN', exam_code = NULL WHERE user_id = 'a0000000-0000-0000-0000-000000000002';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'AUTHOR must not upgrade their own grant to SYS_ADMIN';
  DELETE FROM role_grants WHERE user_id = 'a0000000-0000-0000-0000-000000000001';
  GET DIAGNOSTICS n = ROW_COUNT;
  ASSERT n = 0, 'AUTHOR must not delete the SYS_ADMIN grant';
  -- and step two stays closed
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_order_id)
     VALUES ('a0000000-0000-0000-0000-000000000002','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '999 days',gen_random_uuid());
    RAISE EXCEPTION 'AUTHOR must not mint an entitlement';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- a learner forging only app.e9x_staff (and org_admin in their own org)
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000003',false),
       set_config('app.e9x_staff','on',false);
DO $$
BEGIN
  BEGIN
    INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('a0000000-0000-0000-0000-000000000003',NULL,NULL,'SYS_ADMIN');
    RAISE EXCEPTION 'forged staff flag must not allow a SYS_ADMIN self-grant';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO entitlements(user_id,course_id,valid_from,valid_until,source_order_id)
     VALUES ('a0000000-0000-0000-0000-000000000003','dddddddd-0000-0000-0000-00000000000d',now(),now()+interval '999 days',gen_random_uuid());
    RAISE EXCEPTION 'forged staff flag must not mint an entitlement';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
SELECT set_config('app.e9x_staff','',false), set_config('app.org_id','a1a1a1a1-0000-0000-0000-00000000000a',false),
       set_config('app.org_admin','on',false);
DO $$
BEGIN
  BEGIN
    INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('a0000000-0000-0000-0000-000000000003','a1a1a1a1-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN');
    RAISE EXCEPTION 'forged org_admin flag must not allow an ORG_ADMIN self-grant';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO org_memberships(org_id,user_id) VALUES ('a1a1a1a1-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000004');
    RAISE EXCEPTION 'forged org_admin flag must not add members';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- the legitimate path still works: a real SYS_ADMIN grants roles and memberships
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000001',false),
       set_config('app.org_id','',false), set_config('app.e9x_staff','on',false), set_config('app.org_admin','',false);
INSERT INTO org_memberships(org_id,user_id) VALUES ('a1a1a1a1-0000-0000-0000-00000000000a','a0000000-0000-0000-0000-000000000004');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('a0000000-0000-0000-0000-000000000004','a1a1a1a1-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN');
-- ... and the new ORG_ADMIN (real grant) then manages their own org
SELECT set_config('app.user_id','a0000000-0000-0000-0000-000000000004',false),
       set_config('app.org_id','a1a1a1a1-0000-0000-0000-00000000000a',false), set_config('app.e9x_staff','',false), set_config('app.org_admin','on',false);
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('a0000000-0000-0000-0000-000000000003','a1a1a1a1-0000-0000-0000-00000000000a','IELTS','TEACHER');
