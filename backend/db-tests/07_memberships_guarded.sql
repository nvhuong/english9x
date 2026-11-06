INSERT INTO users(id,email) VALUES
 ('11111111-0000-0000-0000-000000000001','u1@x.test'),
 ('22222222-0000-0000-0000-000000000002','u2@x.test');
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-000000000002','Center B','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('aaaaaaaa-0000-0000-0000-000000000001','11111111-0000-0000-0000-000000000001'),
 ('bbbbbbbb-0000-0000-0000-000000000002','22222222-0000-0000-0000-000000000002');
-- user 1 is the real ORG_ADMIN of A (the org_admin flag alone no longer authorises writes)
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('11111111-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001',NULL,'ORG_ADMIN');

SET ROLE e9x_app;
SELECT set_config('app.user_id','11111111-0000-0000-0000-000000000001',false);
SELECT set_config('app.org_id','',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM org_memberships) = 1, 'user sees only own membership';
  ASSERT (SELECT org_id FROM org_memberships) = 'aaaaaaaa-0000-0000-0000-000000000001', 'own membership is A';
  ASSERT (SELECT count(*) FROM organizations) = 1, 'only member org visible';
  ASSERT (SELECT id FROM organizations) = 'aaaaaaaa-0000-0000-0000-000000000001', 'org A visible via membership';
  BEGIN
    INSERT INTO org_memberships(org_id,user_id) VALUES ('bbbbbbbb-0000-0000-0000-000000000002','11111111-0000-0000-0000-000000000001');
    RAISE EXCEPTION 'plain user self-membership should fail';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO role_grants(user_id,org_id,role) VALUES ('11111111-0000-0000-0000-000000000001',NULL,'SYS_ADMIN');
    RAISE EXCEPTION 'plain user self role grant should fail';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO role_grants(user_id,org_id,role) VALUES ('11111111-0000-0000-0000-000000000001','aaaaaaaa-0000-0000-0000-000000000001','ORG_ADMIN');
    RAISE EXCEPTION 'plain user self org role grant should fail';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;

-- org admin of A
SELECT set_config('app.org_id','aaaaaaaa-0000-0000-0000-000000000001',false);
SELECT set_config('app.org_admin','on',false);
INSERT INTO org_memberships(org_id,user_id) VALUES ('aaaaaaaa-0000-0000-0000-000000000001','22222222-0000-0000-0000-000000000002');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('22222222-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-000000000001','IELTS','TEACHER');
DO $$
BEGIN
  BEGIN
    INSERT INTO org_memberships(org_id,user_id) VALUES ('bbbbbbbb-0000-0000-0000-000000000002','11111111-0000-0000-0000-000000000001');
    RAISE EXCEPTION 'org admin of A must not add member to B';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('11111111-0000-0000-0000-000000000001','bbbbbbbb-0000-0000-0000-000000000002','IELTS','TEACHER');
    RAISE EXCEPTION 'org admin of A must not grant role in B';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO role_grants(user_id,org_id,role) VALUES ('11111111-0000-0000-0000-000000000001',NULL,'SYS_ADMIN');
    RAISE EXCEPTION 'org admin must not grant global role';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  ASSERT (SELECT count(*) FROM org_memberships) = 2, 'org A members visible to A admin, not B';
END $$;
