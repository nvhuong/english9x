-- P4.1 automatic scoring (F33) and the score of an attempt (F25, F26, F15): scores, attempt_gradings, and the grader's way in.
--
-- WHO DOES WHAT.
--   * e9x_grader (through e9x_grader_login, the pool of grading.db) reads the solution side and WRITES scores. The request role e9x_app can
--     READ a learner's own scores and write none: a request, or an SQL statement of a hijacked request, cannot mint a score.
--   * The grader never sees an attempt it was not told about. Everything it reads of a learner is behind policies that need BOTH the user and the
--     organisation of the attempt in the session (app.user_id, app.org_id), and only a SUBMITTED attempt: the listener's hint (an event, a job
--     payload) chooses the context, the row proves it. A forged hint finds nothing. The grader has NO policy on any other learner's rows.
--   * exam_attempts_select was a PUBLIC policy (every role with a SELECT grant): it let anybody in an organisation's context read the organisation's
--     attempts. It is narrowed to e9x_app, the only role that used it, so granting SELECT to the grader does not widen it.
--   * Scores are insert-only. A new score of the same skill and kind is refused (unique), an update or delete is refused for every role, the owner included.
--
-- Roles and passwords belong to infrastructure (infra/postgres/init/01-roles.sh). Where the login role is missing (plain `psql` as superuser,
-- scripts/test-db.sh) it is created NOLOGIN so it cannot be used until infrastructure enables it.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'e9x_grader') THEN
    CREATE ROLE e9x_grader NOLOGIN NOSUPERUSER NOBYPASSRLS;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'e9x_grader_login') THEN
    CREATE ROLE e9x_grader_login NOLOGIN NOSUPERUSER NOBYPASSRLS NOCREATEROLE NOCREATEDB INHERIT;
  END IF;
  IF NOT pg_has_role('e9x_grader_login', 'e9x_grader', 'MEMBER') THEN
    GRANT e9x_grader TO e9x_grader_login;
  END IF;
  IF EXISTS (SELECT 1 FROM pg_roles
              WHERE rolname IN ('e9x_grader_login', 'e9x_grader')
                AND (rolsuper OR rolbypassrls OR rolcreaterole OR rolcreatedb)) THEN
    RAISE EXCEPTION 'grader roles must not be superuser / BYPASSRLS / CREATEROLE / CREATEDB';
  END IF;
  IF pg_has_role('e9x_grader_login', 'e9x_app', 'MEMBER') OR pg_has_role('e9x_grader_login', 'e9x_worker', 'MEMBER') THEN
    RAISE EXCEPTION 'the grader login must not be a member of e9x_app or e9x_worker';
  END IF;
END $$;

-- ---- helper functions the grader's policies reach ------------------------------------------------------------------------------------
-- Several policies of PUBLIC scope call these; they were granted to e9x_app only, so a grader that read a table with such a policy was refused at
-- EXECUTE. Nothing is widened: the helpers answer about the person bound to the session, and the grader has no role grants of anybody.
GRANT EXECUTE ON FUNCTION e9x_caller_is_sys_admin(), e9x_workflow_in_context(uuid), e9x_workflow_has_role(uuid, text, text[]) TO e9x_grader;

-- ---- the attempt, as far as the grader may read it -----------------------------------------------------------------------------------
ALTER POLICY exam_attempts_select ON exam_attempts TO e9x_app;

CREATE POLICY exam_attempts_select_grader ON exam_attempts FOR SELECT TO e9x_grader
  USING (status = 'SUBMITTED' AND user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org());
-- the columns the scoring needs; the rest of the row (resume counters, deadline) is the exam room's
GRANT SELECT (id, user_id, owner_org_id, test_version_id, exam_code, status, submitted_at, submit_reason, interrupted) ON exam_attempts TO e9x_grader;

-- what hangs on the attempt: visible only while the attempt itself is (the sub-select is evaluated under the grader's own policy above)
CREATE POLICY attempt_sections_select_grader ON attempt_sections FOR SELECT TO e9x_grader
  USING (user_id = e9x_current_user() AND EXISTS (SELECT 1 FROM exam_attempts a WHERE a.id = attempt_sections.attempt_id AND a.user_id = attempt_sections.user_id));
CREATE POLICY attempt_questions_select_grader ON attempt_questions FOR SELECT TO e9x_grader
  USING (user_id = e9x_current_user() AND EXISTS (SELECT 1 FROM exam_attempts a WHERE a.id = attempt_questions.attempt_id AND a.user_id = attempt_questions.user_id));
CREATE POLICY responses_select_grader ON responses FOR SELECT TO e9x_grader
  USING (user_id = e9x_current_user() AND EXISTS (SELECT 1 FROM exam_attempts a WHERE a.id = responses.attempt_id AND a.user_id = responses.user_id));
GRANT SELECT ON attempt_sections, attempt_questions, responses TO e9x_grader;

-- ---- scores ---------------------------------------------------------------------------------------------------------------------------
-- One row per (attempt, skill, kind). `skill` is the skill of the SECTION (LISTENING, READING, WRITING, SPEAKING) or the aggregate the exam keeps
-- (TOTAL for TOEIC, OVERALL for IELTS). `raw` / `raw_max` are the points earned and the points there were (partial scoring makes raw fractional);
-- `scaled` is the displayed score after conversion and is NULL while no PUBLISHED table existed (never a guess); score_table_version_id is the exact
-- version used, so editing a table later never changes an old result. source AUTO = scored by the objective scorers (always OFFICIAL: nothing waits
-- for anybody); AI and TEACHER rows come from P4.2 and P4.3 (PRELIMINARY or OFFICIAL).
CREATE TABLE scores (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  attempt_id uuid NOT NULL REFERENCES exam_attempts(id),
  user_id uuid NOT NULL REFERENCES users(id),
  owner_org_id uuid REFERENCES organizations(id),
  skill text NOT NULL CHECK (skill ~ '^[A-Z][A-Z0-9_]{0,31}$'),
  kind text NOT NULL CHECK (kind IN ('PRELIMINARY', 'OFFICIAL')),
  source text NOT NULL CHECK (source IN ('AUTO', 'AI', 'TEACHER')),
  raw numeric(9, 2) CHECK (raw >= 0),
  raw_max numeric(9, 2) CHECK (raw_max >= 0),
  scaled numeric(6, 2) CHECK (scaled >= 0),
  score_table_version_id uuid REFERENCES score_table_versions(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (attempt_id, skill, kind),
  CONSTRAINT scores_auto_official_ck CHECK (source <> 'AUTO' OR kind = 'OFFICIAL'),
  CONSTRAINT scores_table_version_needs_score_ck CHECK (score_table_version_id IS NULL OR scaled IS NOT NULL),
  CONSTRAINT scores_raw_within_max_ck CHECK (raw IS NULL OR raw_max IS NULL OR raw <= raw_max)
);
CREATE INDEX scores_user_idx ON scores (user_id, created_at DESC);
ALTER TABLE scores ENABLE ROW LEVEL SECURITY;

-- the learner reads their own (the results page, P4.4, is the learner's); nobody else reads a score here: teachers and administrators get their own
-- policies in the task that gives them a screen
CREATE POLICY scores_select_own ON scores FOR SELECT TO e9x_app USING (user_id = e9x_current_user());
CREATE POLICY scores_select_grader ON scores FOR SELECT TO e9x_grader
  USING (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org());
-- a score is written only for a SUBMITTED attempt of the person in the session, in the organisation of the session (the sub-select is the grader's own view)
CREATE POLICY scores_insert_grader ON scores FOR INSERT TO e9x_grader
  WITH CHECK (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org()
              AND EXISTS (SELECT 1 FROM exam_attempts a WHERE a.id = scores.attempt_id AND a.user_id = scores.user_id
                             AND a.owner_org_id IS NOT DISTINCT FROM scores.owner_org_id));

CREATE FUNCTION scores_immutable() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  RAISE EXCEPTION 'a score is never changed or deleted; a new kind or a new skill is a new row' USING ERRCODE = '42501';
END $$;
CREATE TRIGGER scores_immutable BEFORE UPDATE OR DELETE ON scores FOR EACH ROW EXECUTE FUNCTION scores_immutable();

GRANT SELECT ON scores TO e9x_app;
GRANT SELECT ON scores TO e9x_grader;
GRANT INSERT (attempt_id, user_id, owner_org_id, skill, kind, source, raw, raw_max, scaled, score_table_version_id) ON scores TO e9x_grader;

-- ---- attempt_gradings -----------------------------------------------------------------------------------------------------------------
-- One row per attempt the automatic scoring has looked at: the key of idempotency (a second run of the same attempt finds the row and writes no score),
-- the interrupted flag the result page shows (copied from the attempt when it was graded), and when AttemptScored was announced (NULL until it was; a
-- run that dies between the scores and the announcement is repeated, so the announcement is at least once and never skipped).
CREATE TABLE attempt_gradings (
  attempt_id uuid PRIMARY KEY REFERENCES exam_attempts(id),
  user_id uuid NOT NULL REFERENCES users(id),
  owner_org_id uuid REFERENCES organizations(id),
  interrupted boolean NOT NULL,
  graded_at timestamptz NOT NULL DEFAULT now(),
  announced_at timestamptz
);
ALTER TABLE attempt_gradings ENABLE ROW LEVEL SECURITY;
CREATE POLICY attempt_gradings_select_own ON attempt_gradings FOR SELECT TO e9x_app USING (user_id = e9x_current_user());
CREATE POLICY attempt_gradings_select_grader ON attempt_gradings FOR SELECT TO e9x_grader
  USING (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org());
CREATE POLICY attempt_gradings_insert_grader ON attempt_gradings FOR INSERT TO e9x_grader
  WITH CHECK (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org()
              AND EXISTS (SELECT 1 FROM exam_attempts a WHERE a.id = attempt_gradings.attempt_id AND a.user_id = attempt_gradings.user_id
                             AND a.owner_org_id IS NOT DISTINCT FROM attempt_gradings.owner_org_id));
CREATE POLICY attempt_gradings_update_grader ON attempt_gradings FOR UPDATE TO e9x_grader
  USING (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org() AND announced_at IS NULL)
  WITH CHECK (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org());

CREATE FUNCTION attempt_gradings_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'a grading record is never deleted' USING ERRCODE = '42501';
  END IF;
  IF NEW.attempt_id IS DISTINCT FROM OLD.attempt_id OR NEW.user_id IS DISTINCT FROM OLD.user_id OR NEW.owner_org_id IS DISTINCT FROM OLD.owner_org_id
     OR NEW.interrupted IS DISTINCT FROM OLD.interrupted OR NEW.graded_at IS DISTINCT FROM OLD.graded_at OR OLD.announced_at IS NOT NULL THEN
    RAISE EXCEPTION 'a grading record only gains its announcement time, once' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER attempt_gradings_guard BEFORE UPDATE OR DELETE ON attempt_gradings FOR EACH ROW EXECUTE FUNCTION attempt_gradings_guard();

GRANT SELECT ON attempt_gradings TO e9x_app;
GRANT SELECT ON attempt_gradings TO e9x_grader;
GRANT INSERT (attempt_id, user_id, owner_org_id, interrupted) ON attempt_gradings TO e9x_grader;
GRANT UPDATE (announced_at) ON attempt_gradings TO e9x_grader;
