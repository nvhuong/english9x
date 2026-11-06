-- P1.7: jobs queue privileges. No BYPASSRLS anywhere: e9x_app (requests) may only INSERT plain QUEUED rows;
-- e9x_worker has explicit policies on jobs and no grant on any other table.
INSERT INTO jobs(id,type,payload,status,run_at)
  VALUES ('00000000-0000-0000-0000-0000000000a1','test_ok','{"pii":"secret"}','QUEUED',now());

-- the worker login role is an ordinary role in the worker group
DO $$
DECLARE r pg_roles%ROWTYPE;
BEGIN
  SELECT * INTO r FROM pg_roles WHERE rolname = 'e9x_worker_login';
  ASSERT FOUND, 'e9x_worker_login must exist';
  ASSERT NOT r.rolsuper AND NOT r.rolbypassrls AND NOT r.rolcreaterole AND NOT r.rolcreatedb, 'worker login must be ordinary';
  ASSERT pg_has_role('e9x_worker_login', 'e9x_worker', 'MEMBER'), 'worker login must be a member of e9x_worker';
  ASSERT NOT pg_has_role('e9x_worker_login', 'e9x_app', 'MEMBER') AND NOT pg_has_role('e9x_worker_login', 'e9x_grader', 'MEMBER'),
         'worker login must not hold request-path privileges';
  ASSERT (SELECT NOT rolsuper AND NOT rolbypassrls FROM pg_roles WHERE rolname = 'e9x_worker'), 'e9x_worker is subject to RLS';
  ASSERT (SELECT relrowsecurity FROM pg_class WHERE oid = 'jobs'::regclass), 'RLS enabled on jobs';
END $$;

-- requests: insert only
SET ROLE e9x_app_login;
INSERT INTO jobs(id,type,payload,status,run_at) VALUES ('00000000-0000-0000-0000-0000000000a2','test_ok','{}','QUEUED',now());
DO $$
BEGIN
  BEGIN PERFORM count(*) FROM jobs; RAISE EXCEPTION 'app must not read jobs';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN UPDATE jobs SET status = 'FAILED'; RAISE EXCEPTION 'app must not update jobs';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN DELETE FROM jobs; RAISE EXCEPTION 'app must not delete jobs';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN
    INSERT INTO jobs(id,type,payload,status,run_at,attempts) VALUES (gen_random_uuid(),'x','{}','SUCCEEDED',now(),1);
    RAISE EXCEPTION 'app must not forge a finished job';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
RESET ROLE;

-- worker: read all, change bookkeeping columns only, nothing else of the system
SET ROLE e9x_worker_login;
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM jobs) = 2, 'worker sees all jobs';
  UPDATE jobs SET status = 'RUNNING', attempts = 1, locked_until = now(), lease_token = gen_random_uuid()
   WHERE id = '00000000-0000-0000-0000-0000000000a1';
  BEGIN UPDATE jobs SET payload = '{}'; RAISE EXCEPTION 'worker must not change payloads';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN UPDATE jobs SET type = 'other'; RAISE EXCEPTION 'worker must not change types';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN INSERT INTO jobs(id,type,payload,status,run_at) VALUES (gen_random_uuid(),'x','{}','QUEUED',now());
    RAISE EXCEPTION 'worker must not insert';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN DELETE FROM jobs; RAISE EXCEPTION 'worker must not delete';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN PERFORM count(*) FROM role_grants; RAISE EXCEPTION 'worker must not read role_grants';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN PERFORM count(*) FROM entitlements; RAISE EXCEPTION 'worker must not read entitlements';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
  BEGIN PERFORM count(*) FROM users; RAISE EXCEPTION 'worker must not read users';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
