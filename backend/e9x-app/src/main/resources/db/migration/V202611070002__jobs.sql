-- P1.7 (spec §5.3): background job queue.
--
-- Two database logins touch this table, both without BYPASSRLS (no policy-free path around RLS):
--   * e9x_app (requests, via e9x_app_login) may only INSERT rows that are plain QUEUED jobs. It has no SELECT,
--     UPDATE or DELETE grant, so it can neither read anyone's payload (which may hold user data) nor tamper with state.
--   * e9x_worker (the worker, via e9x_worker_login) has explicit policies on this table and no grant on any other.
--     Handlers added by later tasks widen it in their own migrations (grant or policy), never here.
-- jobs carries no owner_org_id: it is infrastructure, not tenant data, and no tenant-facing role can read it.
-- Roles and passwords belong to infrastructure (infra/postgres/init/01-roles.sh). Where a role is missing (plain
-- `psql` as superuser, scripts/test-db.sh) it is created NOLOGIN so it cannot be used until infrastructure enables it.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'e9x_worker') THEN
    CREATE ROLE e9x_worker NOLOGIN NOSUPERUSER NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'e9x_worker_login') THEN
    CREATE ROLE e9x_worker_login NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB INHERIT;
  END IF;
  IF NOT pg_has_role('e9x_worker_login', 'e9x_worker', 'MEMBER') THEN
    GRANT e9x_worker TO e9x_worker_login;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles
              WHERE rolname IN ('e9x_worker_login', 'e9x_worker')
                AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb)) THEN
    RAISE EXCEPTION 'worker roles must not be superuser / BYPASSRLS / CREATEROLE / CREATEDB';
  END IF;
  IF pg_has_role('e9x_worker_login', 'e9x_app', 'MEMBER') OR pg_has_role('e9x_worker_login', 'e9x_grader', 'MEMBER') THEN
    RAISE EXCEPTION 'the worker login must not be a member of e9x_app or e9x_grader';
  END IF;
END $$;

CREATE TABLE jobs (
  id           uuid PRIMARY KEY,
  type         text        NOT NULL CHECK (type ~ '^[a-z][a-z0-9_]{0,63}$'),
  payload      jsonb       NOT NULL DEFAULT '{}'::jsonb,
  status       text        NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
  run_at       timestamptz NOT NULL,
  -- runs started so far (a run whose lease expired counts); retries are attempts after the first
  attempts     integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  -- RUNNING only: the lease. A RUNNING row past locked_until is claimable again; lease_token fences stale results.
  locked_until timestamptz,
  lease_token  uuid,
  last_error   text        CHECK (char_length(last_error) <= 1000),
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  finished_at  timestamptz,
  CONSTRAINT jobs_running_has_lease_ck CHECK (status <> 'RUNNING' OR (locked_until IS NOT NULL AND lease_token IS NOT NULL))
);

CREATE INDEX jobs_due_idx ON jobs (run_at, created_at) WHERE status = 'QUEUED';
CREATE INDEX jobs_lease_idx ON jobs (locked_until) WHERE status = 'RUNNING';

ALTER TABLE jobs ENABLE ROW LEVEL SECURITY;

-- Requests: insert plain QUEUED jobs only. No SELECT policy and no SELECT grant, hence no INSERT ... RETURNING.
GRANT INSERT ON jobs TO e9x_app;
CREATE POLICY jobs_app_insert ON jobs FOR INSERT TO e9x_app
  WITH CHECK (status = 'QUEUED' AND attempts = 0 AND locked_until IS NULL AND lease_token IS NULL
              AND last_error IS NULL AND finished_at IS NULL);

-- Worker: sees every job, changes only the bookkeeping columns (never type or payload), creates and deletes nothing.
GRANT SELECT ON jobs TO e9x_worker;
GRANT UPDATE (status, run_at, attempts, locked_until, lease_token, last_error, updated_at, finished_at) ON jobs TO e9x_worker;
CREATE POLICY jobs_worker_select ON jobs FOR SELECT TO e9x_worker USING (true);
CREATE POLICY jobs_worker_update ON jobs FOR UPDATE TO e9x_worker USING (true) WITH CHECK (true);
