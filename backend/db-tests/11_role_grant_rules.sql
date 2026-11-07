INSERT INTO users(id,email) VALUES ('11111111-0000-0000-0000-000000000001','u1@x.test');
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001','Center A','CENTER'),
 ('ffffffff-0000-0000-0000-00000000000f','Enterprise E','ENTERPRISE');
-- actors: a real SYS_ADMIN (staff side) and a real ORG_ADMIN of A and E (org side); flags alone no longer authorise
INSERT INTO users(id,email) VALUES
 ('33333333-0000-0000-0000-000000000003','sys@x.test'),
 ('44444444-0000-0000-0000-000000000004','orgadmin@x.test');
-- a grant in an organisation needs a membership of that user first (P1.5)
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001','44444444-0000-0000-0000-000000000004'),
 ('ffffffff-0000-0000-0000-00000000000f','44444444-0000-0000-0000-000000000004'),
 ('aaaaaaaa-0000-0000-0000-000000000001','11111111-0000-0000-0000-000000000001'),
 ('ffffffff-0000-0000-0000-00000000000f','11111111-0000-0000-0000-000000000001');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('33333333-0000-0000-0000-000000000003',NULL,NULL,'SYS_ADMIN'),
 ('44444444-0000-0000-0000-000000000004','aaaaaaaa-0000-0000-0000-000000000001',NULL,'ORG_ADMIN'),
 ('44444444-0000-0000-0000-000000000004','ffffffff-0000-0000-0000-00000000000f',NULL,'ORG_ADMIN');

-- staff in English9x context (cannot see organizations through RLS)
SET ROLE e9x_app;
SELECT set_config('app.org_id','',false);
SELECT set_config('app.user_id','33333333-0000-0000-0000-000000000003',false);
SELECT set_config('app.e9x_staff','on',false);
DO $$
DECLARE r text;
BEGIN
  BEGIN
    INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('11111111-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001',NULL,'SYS_ADMIN');
    RAISE EXCEPTION 'org-scoped SYS_ADMIN should fail';
  EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN
    INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('11111111-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001','IELTS','SYS_ADMIN');
    RAISE EXCEPTION 'org-scoped exam SYS_ADMIN should fail';
  EXCEPTION WHEN check_violation THEN NULL; END;
  FOREACH r IN ARRAY ARRAY['AUTHOR','REVIEWER','TEACHER'] LOOP
    BEGIN
      INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('11111111-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001',NULL,r);
      RAISE EXCEPTION '% without exam should fail', r;
    EXCEPTION WHEN check_violation THEN NULL; END;
    BEGIN
      INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('11111111-0000-0000-0000-000000000001','ffffffff-0000-0000-0000-00000000000f','IELTS',r);
      RAISE EXCEPTION '% in ENTERPRISE should fail', r;
    EXCEPTION WHEN check_violation THEN NULL; END;
  END LOOP;
END $$;
-- allowed shapes
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('11111111-0000-0000-0000-000000000001',NULL,NULL,'SYS_ADMIN'),
 ('11111111-0000-0000-0000-000000000001',NULL,NULL,'LEARNER'),
 ('11111111-0000-0000-0000-000000000001',NULL,'IELTS','AUTHOR'),
 ('11111111-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001',NULL,'ORG_ADMIN'),
 ('11111111-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001','IELTS','TEACHER'),
 ('11111111-0000-0000-0000-000000000001','ffffffff-0000-0000-0000-00000000000f',NULL,'ORG_ADMIN'),
 ('11111111-0000-0000-0000-000000000001','ffffffff-0000-0000-0000-00000000000f','IELTS','LEARNER');
DO $$
BEGIN
  BEGIN
    UPDATE role_grants SET role='TEACHER'
     WHERE org_id='ffffffff-0000-0000-0000-00000000000f' AND role='LEARNER';
    RAISE EXCEPTION 'update to TEACHER in ENTERPRISE should fail';
  EXCEPTION WHEN check_violation THEN NULL; END;
END $$;

-- org admin of A cannot mint an org-scoped SYS_ADMIN
SELECT set_config('app.user_id','44444444-0000-0000-0000-000000000004',false);
SELECT set_config('app.e9x_staff','',false);
SELECT set_config('app.org_id','aaaaaaaa-0000-0000-0000-000000000001',false);
SELECT set_config('app.org_admin','on',false);
DO $$
BEGIN
  BEGIN
    INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('11111111-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001',NULL,'SYS_ADMIN');
    RAISE EXCEPTION 'org admin must not insert (user, A, NULL, SYS_ADMIN)';
  EXCEPTION WHEN check_violation THEN NULL; END;
END $$;

-- org admin of E cannot add a TEACHER to the enterprise
SELECT set_config('app.org_id','ffffffff-0000-0000-0000-00000000000f',false);
DO $$
BEGIN
  BEGIN
    INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('11111111-0000-0000-0000-000000000001','ffffffff-0000-0000-0000-00000000000f','IELTS','TEACHER');
    RAISE EXCEPTION 'enterprise admin must not grant TEACHER';
  EXCEPTION WHEN check_violation THEN NULL; END;
END $$;
