INSERT INTO users(id,email) VALUES ('11111111-0000-0000-0000-000000000001','u1@x.test');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('11111111-0000-0000-0000-000000000001',NULL,NULL,'SYS_ADMIN');
DO $$
BEGIN
  BEGIN
    INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES ('11111111-0000-0000-0000-000000000001',NULL,NULL,'SYS_ADMIN');
    RAISE EXCEPTION 'duplicate NULL-scoped grant should fail';
  EXCEPTION WHEN unique_violation THEN NULL; END;
END $$;
