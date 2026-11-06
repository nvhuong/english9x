-- The runtime login e9x_app_login inherits e9x_app, owns nothing and cannot bypass RLS.
DO $$
DECLARE r pg_roles%ROWTYPE;
BEGIN
  SELECT * INTO r FROM pg_roles WHERE rolname = 'e9x_app_login';
  ASSERT FOUND, 'e9x_app_login must exist';
  ASSERT NOT r.rolsuper AND NOT r.rolbypassrls AND NOT r.rolcreaterole AND NOT r.rolcreatedb,
         'e9x_app_login must be an ordinary role';
  ASSERT r.rolinherit, 'e9x_app_login must inherit e9x_app';
  ASSERT pg_has_role('e9x_app_login', 'e9x_app', 'MEMBER'), 'e9x_app_login must be a member of e9x_app';
  ASSERT NOT pg_has_role('e9x_app_login', 'e9x_grader', 'MEMBER'), 'the app login must not be a grader';
  ASSERT NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname = 'public' AND tableowner IN ('e9x_app_login','e9x_app','e9x_grader')),
         'application roles must not own tables';
  ASSERT (SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname = 'e9x_app'), 'e9x_app must be subject to RLS';
END $$;

INSERT INTO organizations(id,name,type) VALUES
 ('a1a1a1a1-0000-0000-0000-00000000000a','A','CENTER'),
 ('b1b1b1b1-0000-0000-0000-00000000000b','B','CENTER');
INSERT INTO classes(id,owner_org_id,name) VALUES
 ('c1000000-0000-0000-0000-00000000000a','a1a1a1a1-0000-0000-0000-00000000000a','class A'),
 ('c2000000-0000-0000-0000-00000000000b','b1b1b1b1-0000-0000-0000-00000000000b','class B');

SET ROLE e9x_app_login;
DO $$
BEGIN
  ASSERT current_user = 'e9x_app_login';
  ASSERT (SELECT count(*) FROM classes) = 0, 'no context -> no rows through the login role';
END $$;
SELECT set_config('app.org_id','a1a1a1a1-0000-0000-0000-00000000000a',false);
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM classes) = 1 AND (SELECT name FROM classes) = 'class A', 'only the org of the context';
  BEGIN
    INSERT INTO classes(owner_org_id,name) VALUES ('b1b1b1b1-0000-0000-0000-00000000000b','x');
    RAISE EXCEPTION 'cross-org insert must fail';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    CREATE TABLE sneaky(id int);
    RAISE EXCEPTION 'the app login must not create tables';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
