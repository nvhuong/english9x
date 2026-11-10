-- P2.7 Excel import (feature F11). The plan text names V202611120002; that number is P2.6's (test scope enable) and 120003 is P5.3's.
--
-- Shape. The client's job id is import_jobs.id, NOT jobs.id: the application role cannot read `jobs` (P1.7). The background job `excel_import`
-- carries this id in its payload and keeps import_jobs up to date; everything an author sees about an import comes from these three tables.
--   import_jobs          one upload: who, which type, the state (QUEUED, RUNNING, SUCCEEDED, REJECTED, FAILED), the counters, and the staged files
--   import_errors        one row per problem found (row, column, message); at most 1000 per import (the job stops collecting there)
--   import_job_versions  the DRAFT versions an import created: THIS is the "lot" (bundle) of drafts. No review_bundles row exists until the
--                        author submits them together (ReviewService.submit(BundleRef), spec 7.1); until then every version is a plain DRAFT.
-- All or nothing: REJECTED means at least one error and NO question was created; SUCCEEDED means every row became a DRAFT version, in one transaction.
--
-- Who sees what: the person who uploaded, in the context (organisation or English9x) they uploaded in, while still an AUTHOR of the exam there.
-- (A person removed from the organisation no longer sees the import; the job's failure path does not depend on that: see import_job_mark_failed.)
-- The worker login gets NO table grant. Its one door is import_job_mark_failed(): "mark failed + record the reason", nothing else, only for an
-- import that is still QUEUED / RUNNING. It exists because JobExecutor#onExhausted must not depend on the user's binding: a member removed from
-- the organisation makes every binding fail (OrgContextDeniedException) and the import would stick in RUNNING for ever.

CREATE TABLE import_jobs (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_org_id   uuid REFERENCES organizations(id),
  user_id        uuid NOT NULL REFERENCES users(id),
  exam_code      text NOT NULL REFERENCES exams(code),
  type_code      text NOT NULL,
  status         text NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'REJECTED', 'FAILED')),
  source_name    text NOT NULL CHECK (char_length(source_name) BETWEEN 1 AND 255),
  has_media      boolean NOT NULL DEFAULT false,
  total_rows     integer NOT NULL DEFAULT 0 CHECK (total_rows >= 0),
  processed_rows integer NOT NULL DEFAULT 0 CHECK (processed_rows >= 0),
  error_count    integer NOT NULL DEFAULT 0 CHECK (error_count >= 0),
  created_count  integer NOT NULL DEFAULT 0 CHECK (created_count >= 0),
  failure_reason text CHECK (failure_reason IS NULL OR char_length(failure_reason) <= 200),
  has_report     boolean NOT NULL DEFAULT false,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  finished_at    timestamptz,
  FOREIGN KEY (type_code, exam_code) REFERENCES question_types(code, exam_code),
  -- a finished import is finished: the counters of a REJECTED one say it created nothing
  CHECK (status <> 'REJECTED' OR (created_count = 0 AND error_count > 0)),
  CHECK (status NOT IN ('SUCCEEDED', 'REJECTED', 'FAILED') OR finished_at IS NOT NULL)
);
CREATE INDEX import_jobs_user_idx ON import_jobs (user_id, created_at DESC);
ALTER TABLE import_jobs ENABLE ROW LEVEL SECURITY;

CREATE TABLE import_errors (
  id            bigserial PRIMARY KEY,
  import_job_id uuid NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,
  row_no        integer NOT NULL CHECK (row_no >= 0),            -- the row of the sheet (1 = header); 0 = the file as a whole
  column_name   text CHECK (column_name IS NULL OR char_length(column_name) <= 200),
  message       text NOT NULL CHECK (char_length(message) BETWEEN 1 AND 1000)
);
CREATE INDEX import_errors_job_idx ON import_errors (import_job_id, row_no, id);
ALTER TABLE import_errors ENABLE ROW LEVEL SECURITY;

CREATE TABLE import_job_versions (
  import_job_id       uuid NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,
  position            integer NOT NULL CHECK (position >= 1),   -- creation order = row order
  row_no              integer NOT NULL CHECK (row_no >= 2),
  question_version_id uuid NOT NULL REFERENCES question_versions(id),
  PRIMARY KEY (import_job_id, position),
  UNIQUE (question_version_id)
);
ALTER TABLE import_job_versions ENABLE ROW LEVEL SECURITY;

-- ---- policies: the uploader, in the context of the upload, still an AUTHOR of the exam there ------------------------------------
CREATE POLICY import_jobs_select ON import_jobs FOR SELECT TO e9x_app
  USING (user_id = e9x_current_user() AND e9x_workflow_in_context(owner_org_id)
         AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR']));
CREATE POLICY import_jobs_insert ON import_jobs FOR INSERT TO e9x_app
  WITH CHECK (user_id = e9x_current_user() AND e9x_workflow_in_context(owner_org_id)
              AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR']) AND status = 'QUEUED');
CREATE POLICY import_jobs_update ON import_jobs FOR UPDATE TO e9x_app
  USING (user_id = e9x_current_user() AND e9x_workflow_in_context(owner_org_id)
         AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR']))
  WITH CHECK (user_id = e9x_current_user() AND e9x_workflow_in_context(owner_org_id)
              AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR']));

-- the children are visible / writable exactly while their import is (the sub-select runs under import_jobs' own policies: invoker rights)
CREATE POLICY import_errors_select ON import_errors FOR SELECT TO e9x_app
  USING (EXISTS (SELECT 1 FROM import_jobs j WHERE j.id = import_job_id));
CREATE POLICY import_errors_insert ON import_errors FOR INSERT TO e9x_app
  WITH CHECK (EXISTS (SELECT 1 FROM import_jobs j WHERE j.id = import_job_id));
CREATE POLICY import_job_versions_select ON import_job_versions FOR SELECT TO e9x_app
  USING (EXISTS (SELECT 1 FROM import_jobs j WHERE j.id = import_job_id));
CREATE POLICY import_job_versions_insert ON import_job_versions FOR INSERT TO e9x_app
  WITH CHECK (EXISTS (SELECT 1 FROM import_jobs j WHERE j.id = import_job_id));

GRANT SELECT ON import_jobs, import_errors, import_job_versions TO e9x_app;
GRANT INSERT (id, owner_org_id, user_id, exam_code, type_code, source_name, has_media) ON import_jobs TO e9x_app;
GRANT UPDATE (status, total_rows, processed_rows, error_count, created_count, failure_reason, has_report, updated_at, finished_at) ON import_jobs TO e9x_app;
GRANT INSERT (import_job_id, row_no, column_name, message) ON import_errors TO e9x_app;
GRANT INSERT (import_job_id, position, row_no, question_version_id) ON import_job_versions TO e9x_app;
GRANT USAGE ON SEQUENCE import_errors_id_seq TO e9x_app;

-- ---- the worker's one door -----------------------------------------------------------------------------------------------------
-- Marks an import that is still QUEUED / RUNNING as FAILED and records why. Returns whether it moved. Nothing else: no read, no other state, no
-- other column. The reason is cut to 200 characters and is a fixed phrase of the job, never a message of an exception.
CREATE FUNCTION import_job_mark_failed(p_import uuid, p_reason text) RETURNS boolean
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_rows integer;
BEGIN
  UPDATE import_jobs
     SET status = 'FAILED', failure_reason = left(coalesce(nullif(btrim(p_reason), ''), 'failed'), 200), updated_at = now(), finished_at = now()
   WHERE id = p_import AND status IN ('QUEUED', 'RUNNING');
  GET DIAGNOSTICS v_rows = ROW_COUNT;
  RETURN v_rows = 1;
END $$;
REVOKE ALL ON FUNCTION import_job_mark_failed(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION import_job_mark_failed(uuid, text) TO e9x_worker;
