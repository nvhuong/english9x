-- P1.9: the audit log is append-only for everybody, written only as the person bound to the session, read only by a real SYS_ADMIN.
-- Runs as the superuser first (the trigger must hold even for the owner / a superuser), then as the real request role e9x_app.
INSERT INTO users(id,email,email_verified_at) VALUES
 ('f0000000-0000-0000-0000-000000000001','sys@x.test', now()),
 ('f0000000-0000-0000-0000-000000000002','admin-a@x.test', now()),
 ('f0000000-0000-0000-0000-000000000003','author@x.test', now());
INSERT INTO organizations(id,name,type) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','Center A','CENTER'),
 ('bbbbbbbb-0000-0000-0000-00000000000b','Center B','CENTER');
INSERT INTO org_memberships(org_id,user_id) VALUES
 ('aaaaaaaa-0000-0000-0000-00000000000a','f0000000-0000-0000-0000-000000000002');
INSERT INTO role_grants(user_id,org_id,exam_code,role) VALUES
 ('f0000000-0000-0000-0000-000000000001',NULL,NULL,'SYS_ADMIN'),
 ('f0000000-0000-0000-0000-000000000002','aaaaaaaa-0000-0000-0000-00000000000a',NULL,'ORG_ADMIN'),
 ('f0000000-0000-0000-0000-000000000003',NULL,'IELTS','AUTHOR');

-- ---- as the owner / superuser: rows exist (the operator script writes like this), nothing can be changed afterwards ----
INSERT INTO audit_log(actor_kind, actor_id, org_id, action, target_type, target_id, detail) VALUES
 ('USER','f0000000-0000-0000-0000-000000000003','bbbbbbbb-0000-0000-0000-00000000000b','org.create','organization','b-row','{"name":"B"}'),
 ('OPERATOR',NULL,NULL,'sys-admin.grant','user','f0000000-0000-0000-0000-000000000001','{"via":"grant-sys-admin.sh"}');
DO $$
DECLARE failed boolean;
BEGIN
  failed := false;
  BEGIN UPDATE audit_log SET action = 'org.delete'; EXCEPTION WHEN restrict_violation THEN failed := true; END;
  ASSERT failed, 'UPDATE is refused even for the owner / a superuser';
  failed := false;
  BEGIN DELETE FROM audit_log; EXCEPTION WHEN restrict_violation THEN failed := true; END;
  ASSERT failed, 'DELETE is refused even for the owner / a superuser';
  failed := false;
  BEGIN TRUNCATE audit_log; EXCEPTION WHEN restrict_violation THEN failed := true; END;
  ASSERT failed, 'TRUNCATE is refused even for the owner / a superuser';
  failed := false;
  BEGIN TRUNCATE audit_log, users CASCADE; EXCEPTION WHEN restrict_violation THEN failed := true; END;
  ASSERT failed, 'TRUNCATE ... CASCADE through another table is refused too';
  -- the triggers hold when replication-style bypass is attempted
  SET LOCAL session_replication_role = replica;
  failed := false;
  BEGIN DELETE FROM audit_log; EXCEPTION WHEN restrict_violation THEN failed := true; END;
  ASSERT failed, 'the triggers are ENABLE ALWAYS: session_replication_role = replica does not switch them off';
  ASSERT (SELECT count(*) FROM audit_log) = 2, 'both rows are still there';
  -- shape
  failed := false;
  BEGIN INSERT INTO audit_log(actor_kind, actor_id, action, target_type, target_id) VALUES ('USER', NULL, 'a.b', 't', 'x');
  EXCEPTION WHEN check_violation THEN failed := true; END;
  ASSERT failed, 'a USER record needs an actor';
  failed := false;
  BEGIN INSERT INTO audit_log(actor_kind, actor_id, action, target_type, target_id) VALUES ('OPERATOR', 'f0000000-0000-0000-0000-000000000001', 'a.b', 't', 'x');
  EXCEPTION WHEN check_violation THEN failed := true; END;
  ASSERT failed, 'an OPERATOR record has no actor';
  failed := false;
  BEGIN INSERT INTO audit_log(actor_kind, actor_id, action, target_type, target_id) VALUES ('USER', 'f0000000-0000-0000-0000-000000000001', 'Bad Action', 't', 'x');
  EXCEPTION WHEN check_violation THEN failed := true; END;
  ASSERT failed, 'an action has a fixed lower-case shape';
  failed := false;
  BEGIN INSERT INTO audit_log(actor_kind, actor_id, action, target_type, target_id, detail) VALUES ('USER', 'f0000000-0000-0000-0000-000000000001', 'a.b', 't', 'x', '[1]');
  EXCEPTION WHEN check_violation THEN failed := true; END;
  ASSERT failed, 'detail is a JSON object';
  failed := false;
  BEGIN INSERT INTO audit_log(actor_kind, actor_id, action, target_type, target_id) VALUES ('USER', 'f0000000-0000-0000-0000-000000000001', 'a.b', 't', '');
  EXCEPTION WHEN check_violation THEN failed := true; END;
  ASSERT failed, 'a target id is not empty';
END $$;
-- the log of the user survives the user: no foreign key may cascade into the log
DO $$
BEGIN
  ASSERT NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'audit_log'::regclass AND contype = 'f'), 'audit_log has no foreign key (a log outlives people and organisations)';
  ASSERT (SELECT relrowsecurity FROM pg_class WHERE oid = 'audit_log'::regclass), 'RLS is enabled';
  ASSERT (SELECT bool_and(tgenabled = 'A') FROM pg_trigger WHERE tgrelid = 'audit_log'::regclass AND NOT tgisinternal), 'every audit_log trigger is ENABLE ALWAYS';
END $$;

-- ---- as the request role ----------------------------------------------------------------------------------------------
SET ROLE e9x_app;
SELECT set_config('app.user_id','f0000000-0000-0000-0000-000000000002',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','on',false);
DO $$
DECLARE failed boolean;
BEGIN
  -- the privileges: no UPDATE, DELETE, TRUNCATE; INSERT only on the four columns that are the caller's to say
  ASSERT NOT has_table_privilege('e9x_app','audit_log','UPDATE'), 'no UPDATE';
  ASSERT NOT has_table_privilege('e9x_app','audit_log','DELETE'), 'no DELETE';
  ASSERT NOT has_table_privilege('e9x_app','audit_log','TRUNCATE'), 'no TRUNCATE';
  ASSERT NOT has_column_privilege('e9x_app','audit_log','actor_id','INSERT'), 'the actor is not a column the request role can name';
  ASSERT NOT has_column_privilege('e9x_app','audit_log','org_id','INSERT'), 'nor the context';
  ASSERT NOT has_column_privilege('e9x_app','audit_log','created_at','INSERT'), 'nor the time';
  ASSERT has_column_privilege('e9x_app','audit_log','detail','INSERT'), 'detail is the caller''s';
  failed := false;
  BEGIN UPDATE audit_log SET detail = '{}'; EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'UPDATE refused for the request role';
  failed := false;
  BEGIN DELETE FROM audit_log; EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'DELETE refused for the request role';
  failed := false;
  BEGIN TRUNCATE audit_log; EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'TRUNCATE refused for the request role';
  failed := false;
  BEGIN INSERT INTO audit_log(actor_id, action, target_type, target_id) VALUES ('f0000000-0000-0000-0000-000000000001','org.create','organization','x');
  EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'a statement cannot name the actor';
  failed := false;
  BEGIN INSERT INTO audit_log(org_id, action, target_type, target_id) VALUES ('bbbbbbbb-0000-0000-0000-00000000000b','org.create','organization','x');
  EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'a statement cannot name the context';
  failed := false;
  BEGIN INSERT INTO audit_log(actor_kind, action, target_type, target_id) VALUES ('OPERATOR','org.create','organization','x');
  EXCEPTION WHEN insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'a statement cannot claim to be the operator';
END $$;
-- a record carries the bound person and context
INSERT INTO audit_log(action, target_type, target_id, detail) VALUES ('role.change','user','f0000000-0000-0000-0000-000000000003','{"before":[],"after":["AUTHOR/IELTS"]}');
RESET ROLE;
DO $$
DECLARE r audit_log;
BEGIN
  SELECT * INTO r FROM audit_log WHERE action = 'role.change';
  ASSERT r.actor_kind = 'USER' AND r.actor_id = 'f0000000-0000-0000-0000-000000000002', 'the actor is the bound user';
  ASSERT r.org_id = 'aaaaaaaa-0000-0000-0000-00000000000a', 'the owner organisation is the bound context';
  ASSERT r.created_at IS NOT NULL AND r.seq IS NOT NULL;
END $$;

-- no user bound: nothing can be written (a background job without a person)
SET ROLE e9x_app;
SELECT set_config('app.user_id','',false), set_config('app.org_id','',false), set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$
DECLARE failed boolean := false;
BEGIN
  BEGIN INSERT INTO audit_log(action, target_type, target_id) VALUES ('org.create','organization','x');
  EXCEPTION WHEN check_violation OR insufficient_privilege THEN failed := true; END;
  ASSERT failed, 'no bound user, no record';
END $$;

-- ---- who reads -------------------------------------------------------------------------------------------------------
-- an ORG_ADMIN reads nothing, not even the rows of their own organisation
SELECT set_config('app.user_id','f0000000-0000-0000-0000-000000000002',false), set_config('app.org_id','aaaaaaaa-0000-0000-0000-00000000000a',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','on',false);
DO $$ BEGIN ASSERT (SELECT count(*) FROM audit_log) = 0, 'an ORG_ADMIN reads no audit rows'; END $$;
-- an AUTHOR carries the English9x staff flag but no SYS_ADMIN grant: nothing (no staff-wide clause)
SELECT set_config('app.user_id','f0000000-0000-0000-0000-000000000003',false), set_config('app.org_id','',false),
       set_config('app.e9x_staff','on',false), set_config('app.org_admin','',false);
DO $$ BEGIN ASSERT (SELECT count(*) FROM audit_log) = 0, 'the staff flag alone reads nothing'; END $$;
-- a SYS_ADMIN grant WITHOUT the flag (a SYS_ADMIN inside an organisation context): nothing
SELECT set_config('app.user_id','f0000000-0000-0000-0000-000000000001',false), set_config('app.org_id','',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);
DO $$ BEGIN ASSERT (SELECT count(*) FROM audit_log) = 0, 'the grant without the English9x flag reads nothing'; END $$;
-- the real SYS_ADMIN reads everything, whichever organisation wrote it
SELECT set_config('app.user_id','f0000000-0000-0000-0000-000000000001',false), set_config('app.org_id','',false),
       set_config('app.e9x_staff','on',false), set_config('app.org_admin','',false);
DO $$ BEGIN ASSERT (SELECT count(*) FROM audit_log) = 3, 'a real SYS_ADMIN reads all rows, got ' || (SELECT count(*) FROM audit_log); END $$;
