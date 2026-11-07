-- P1.6 shared review workflow (features F17, F18): review_requests, review_bundles and the generic version lifecycle guard.
--
--  * A review belongs to the context it was submitted in: owner_org_id = the organisation, NULL = English9x. It is visible,
--    insertable and decidable only inside that context: an organisation's reviews in that organisation's context; English9x
--    reviews in the personal context with the English9x flag (e9x_is_staff(), which needs an English9x AUTHOR / REVIEWER /
--    TEACHER / SYS_ADMIN grant). No clause here reads "any staff": every policy also names the role AND the exam of the row.
--  * Seeing a review: its author, whoever submitted it, an AUTHOR or REVIEWER of its exam, the ORG_ADMIN of its organisation
--    (SYS_ADMIN for English9x reviews). Submitting: an AUTHOR of the exam. Deciding: a REVIEWER of the exam, recorded as
--    reviewer_id = the caller. The trigger refuses a decider who is the author or the submitter, whoever the caller is
--    (a SYS_ADMIN who also holds REVIEWER and wrote the version is refused too).
--  * A review is decided once. After that no column changes (the trigger), and there is no DELETE privilege at all.
--  * The grants (role_grants) are read as the caller (the policy of role_grants shows everyone their own rows), so these
--    helpers need no SECURITY DEFINER.
--
-- e9x_guard_version_lifecycle(): the generic trigger function P2 attaches to each of its *_versions tables.
--   CREATE TRIGGER <table>_lifecycle BEFORE INSERT OR UPDATE OR DELETE ON <table>
--     FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_lifecycle('updated_at');
-- The table needs a text column "status" holding a ContentStatus name. Trigger arguments name the columns that may change
-- together with a status move (bookkeeping such as updated_at); none by default. It enforces, for every writer
-- including the table owner and a compromised statement of the application role:
--     DRAFT -> IN_REVIEW,  CHANGES_REQUESTED -> IN_REVIEW,  IN_REVIEW -> CHANGES_REQUESTED | PUBLISHED,  PUBLISHED -> ARCHIVED
--   * DRAFT and CHANGES_REQUESTED rows are editable; IN_REVIEW rows are frozen (what the reviewer read is what is published);
--     PUBLISHED rows only move to ARCHIVED, changing nothing else; ARCHIVED rows never change.
--   * DELETE is refused for IN_REVIEW, PUBLISHED and ARCHIVED rows.
--   * A version cannot reach PUBLISHED except from IN_REVIEW. (That a review approved it is the workflow service's rule;
--     the database refuses the shortcut.)
--   * INSERT: the row must be DRAFT, with the status written or defaulted; a PUBLISHED / IN_REVIEW / NULL status is refused.
--     Only the owner (Flyway: migrations and seed data) and a superuser may insert any status, so the application, worker
--     and grader logins are all refused. Attach the trigger as BEFORE INSERT OR UPDATE OR DELETE.
--   * A table without a text/enum "status" column is refused on every operation (the guard never fails open).
--   * LIMIT: a stored GENERATED column is NULL in NEW inside a BEFORE trigger, so the "nothing else changed" comparison would
--     see a difference and refuse a legal move. Do not use stored generated columns on a guarded table (keep them in a view
--     or compute them in the application), or name them as trigger arguments.
--   * The status of a content row has no database link to a review_requests row: that a review approved the move to
--     PUBLISHED is the workflow service's rule (the database refuses the shortcuts, not a forged IN_REVIEW to PUBLISHED).

CREATE FUNCTION e9x_guard_version_lifecycle() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_old_status text := to_jsonb(OLD) ->> 'status';
  v_new_status text;
  v_old jsonb;
  v_new jsonb;
  v_col text;
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF current_user <> 'e9x_owner'
       AND NOT (SELECT r.rolsuper FROM pg_roles r WHERE r.rolname = current_user)
       AND to_jsonb(NEW) ->> 'status' IS DISTINCT FROM 'DRAFT' THEN
      RAISE EXCEPTION 'a new version starts as DRAFT' USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
  END IF;
  IF v_old_status IS NULL THEN
    RAISE EXCEPTION 'the guarded table has no usable status column' USING ERRCODE = '23514';
  END IF;

  IF TG_OP = 'DELETE' THEN
    IF v_old_status IN ('IN_REVIEW', 'PUBLISHED', 'ARCHIVED') THEN
      RAISE EXCEPTION 'a % version cannot be deleted', v_old_status USING ERRCODE = '23000';
    END IF;
    RETURN OLD;
  END IF;

  v_new_status := to_jsonb(NEW) ->> 'status';
  IF v_new_status IS NULL OR v_new_status NOT IN ('DRAFT','IN_REVIEW','CHANGES_REQUESTED','PUBLISHED','ARCHIVED') THEN
    RAISE EXCEPTION 'unknown version status' USING ERRCODE = '23514';
  END IF;

  IF v_old_status IN ('DRAFT', 'CHANGES_REQUESTED') THEN
    IF v_new_status <> v_old_status AND v_new_status <> 'IN_REVIEW' THEN
      RAISE EXCEPTION 'a % version can only go to IN_REVIEW', v_old_status USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
  END IF;

  IF v_old_status = 'IN_REVIEW' AND v_new_status NOT IN ('IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED') THEN
    RAISE EXCEPTION 'a version under review ends as CHANGES_REQUESTED or PUBLISHED' USING ERRCODE = '23000';
  END IF;
  IF v_old_status = 'PUBLISHED' AND v_new_status <> 'ARCHIVED' THEN
    RAISE EXCEPTION 'a published version is immutable (it can only be archived)' USING ERRCODE = '23000';
  END IF;
  IF v_old_status = 'ARCHIVED' THEN
    RAISE EXCEPTION 'an archived version is immutable' USING ERRCODE = '23000';
  END IF;

  -- IN_REVIEW stays frozen (and may become CHANGES_REQUESTED / PUBLISHED); PUBLISHED may become ARCHIVED: in every case
  -- nothing except the status (and the named bookkeeping columns) may differ.
  v_old := to_jsonb(OLD) - 'status';
  v_new := to_jsonb(NEW) - 'status';
  FOR i IN 0 .. TG_NARGS - 1 LOOP
    v_col := TG_ARGV[i];
    v_old := v_old - v_col;
    v_new := v_new - v_col;
  END LOOP;
  IF v_old IS DISTINCT FROM v_new THEN
    RAISE EXCEPTION 'a % version is immutable', v_old_status USING ERRCODE = '23000';
  END IF;
  RETURN NEW;
END $$;

-- ---- policy helpers (invoker: they read the caller's own grants) --------------------------------------------------------------
-- Is the row's context the one the session is in? English9x rows (owner NULL) additionally need the English9x flag.
CREATE FUNCTION e9x_workflow_in_context(p_owner uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT p_owner IS NOT DISTINCT FROM e9x_current_org() AND (p_owner IS NOT NULL OR e9x_is_staff()) $$;

-- Does the caller hold one of the roles in exactly that owner (organisation, or English9x for NULL) for that exam?
-- ORG_ADMIN / SYS_ADMIN grants have no exam and cover every exam.
CREATE FUNCTION e9x_workflow_has_role(p_owner uuid, p_exam text, p_roles text[]) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT EXISTS (SELECT 1 FROM role_grants g
                  WHERE g.user_id = e9x_current_user()
                    AND g.org_id IS NOT DISTINCT FROM p_owner
                    AND g.role = ANY (p_roles)
                    AND (g.exam_code = p_exam OR (g.exam_code IS NULL AND g.role IN ('ORG_ADMIN', 'SYS_ADMIN')))) $$;
REVOKE ALL ON FUNCTION e9x_workflow_in_context(uuid), e9x_workflow_has_role(uuid, text, text[]) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION e9x_workflow_in_context(uuid), e9x_workflow_has_role(uuid, text, text[]) TO e9x_app;

-- ---- review_bundles ------------------------------------------------------------------------------------------------------------
CREATE TABLE review_bundles (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_org_id uuid REFERENCES organizations(id),
  submitted_by uuid NOT NULL REFERENCES users(id),
  -- how many reviews the bundle holds. A reviewer sees only the members of their own exams, so this is how the service knows
  -- that a bundle holds members the caller cannot decide, and refuses the whole bundle instead of approving a part of it.
  member_count integer NOT NULL CHECK (member_count BETWEEN 1 AND 5000),
  created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE review_bundles ENABLE ROW LEVEL SECURITY;

-- ---- review_requests ----------------------------------------------------------------------------------------------------------
CREATE TABLE review_requests (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  bundle_id uuid REFERENCES review_bundles(id),
  content_type text NOT NULL CHECK (content_type ~ '^[a-z][a-z0-9_]{0,63}$'),
  version_id uuid NOT NULL,
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  author_id uuid NOT NULL REFERENCES users(id),
  submitted_by uuid NOT NULL REFERENCES users(id),
  submitted_at timestamptz NOT NULL DEFAULT now(),
  status text NOT NULL DEFAULT 'IN_REVIEW' CHECK (status IN ('IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED')),
  reviewer_id uuid REFERENCES users(id),
  decided_at timestamptz,
  comment text CHECK (comment IS NULL OR length(comment) <= 4000),
  CONSTRAINT review_requests_decision_ck CHECK (
    (status = 'IN_REVIEW' AND reviewer_id IS NULL AND decided_at IS NULL AND comment IS NULL)
    OR (status = 'CHANGES_REQUESTED' AND reviewer_id IS NOT NULL AND decided_at IS NOT NULL AND length(btrim(comment)) > 0)
    OR (status = 'PUBLISHED' AND reviewer_id IS NOT NULL AND decided_at IS NOT NULL))
);
-- One open review per version, and one approval per version (so ContentPublished is emitted once per version).
CREATE UNIQUE INDEX review_requests_one_open_uq ON review_requests (content_type, version_id) WHERE status = 'IN_REVIEW';
CREATE UNIQUE INDEX review_requests_one_published_uq ON review_requests (content_type, version_id) WHERE status = 'PUBLISHED';
CREATE INDEX review_requests_queue_idx ON review_requests (owner_org_id, status, submitted_at);
CREATE INDEX review_requests_bundle_idx ON review_requests (bundle_id) WHERE bundle_id IS NOT NULL;
ALTER TABLE review_requests ENABLE ROW LEVEL SECURITY;

CREATE FUNCTION review_requests_guard() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_bundle review_bundles%ROWTYPE;
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.bundle_id IS NOT NULL THEN
      SELECT * INTO v_bundle FROM review_bundles WHERE id = NEW.bundle_id;
      IF NOT FOUND OR v_bundle.owner_org_id IS DISTINCT FROM NEW.owner_org_id OR v_bundle.submitted_by <> NEW.submitted_by THEN
        RAISE EXCEPTION 'a bundle holds the reviews of one organisation submitted by one person' USING ERRCODE = '23000';
      END IF;
    END IF;
    RETURN NEW;
  END IF;
  -- UPDATE: one decision, once; nothing else about the review changes.
  IF OLD.status <> 'IN_REVIEW' THEN
    RAISE EXCEPTION 'a decided review is final' USING ERRCODE = '23000';
  END IF;
  IF NEW.status NOT IN ('PUBLISHED', 'CHANGES_REQUESTED') THEN
    RAISE EXCEPTION 'a review is decided as PUBLISHED or CHANGES_REQUESTED' USING ERRCODE = '23000';
  END IF;
  IF (to_jsonb(NEW) - 'status' - 'reviewer_id' - 'decided_at' - 'comment')
     IS DISTINCT FROM (to_jsonb(OLD) - 'status' - 'reviewer_id' - 'decided_at' - 'comment') THEN
    RAISE EXCEPTION 'only the decision of a review can change' USING ERRCODE = '23000';
  END IF;
  IF NEW.reviewer_id IS NULL OR NEW.reviewer_id = OLD.author_id OR NEW.reviewer_id = OLD.submitted_by THEN
    RAISE EXCEPTION 'nobody decides on a version they wrote or submitted' USING ERRCODE = '23000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER review_requests_guard_trg BEFORE INSERT OR UPDATE ON review_requests
  FOR EACH ROW EXECUTE FUNCTION review_requests_guard();

CREATE POLICY review_requests_select ON review_requests FOR SELECT USING (
  e9x_workflow_in_context(owner_org_id)
  AND (author_id = e9x_current_user() OR submitted_by = e9x_current_user()
       OR e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER', 'ORG_ADMIN', 'SYS_ADMIN'])));
CREATE POLICY review_requests_insert ON review_requests FOR INSERT WITH CHECK (
  e9x_workflow_in_context(owner_org_id)
  AND submitted_by = e9x_current_user()
  AND status = 'IN_REVIEW'
  AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR']));
-- The USING clause does not look at the status: SELECT ... FOR UPDATE applies it, and the loser of a race must still find the
-- row (already decided) to say "conflict" rather than "not found". The trigger is what makes a decided review final.
CREATE POLICY review_requests_decide ON review_requests FOR UPDATE USING (
  e9x_workflow_in_context(owner_org_id)
  AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['REVIEWER']))
  WITH CHECK (
  e9x_workflow_in_context(owner_org_id) AND reviewer_id = e9x_current_user()
  AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['REVIEWER']));

CREATE POLICY review_bundles_select ON review_bundles FOR SELECT USING (
  e9x_workflow_in_context(owner_org_id)
  AND (submitted_by = e9x_current_user() OR EXISTS (SELECT 1 FROM review_requests r WHERE r.bundle_id = review_bundles.id)));
CREATE POLICY review_bundles_insert ON review_bundles FOR INSERT WITH CHECK (
  e9x_workflow_in_context(owner_org_id) AND submitted_by = e9x_current_user());

-- No DELETE anywhere, no UPDATE of bundles; a review's only updatable columns are the decision.
GRANT SELECT ON review_requests, review_bundles TO e9x_app;
GRANT INSERT (id, bundle_id, content_type, version_id, owner_org_id, exam_code, author_id, submitted_by) ON review_requests TO e9x_app;
GRANT UPDATE (status, reviewer_id, decided_at, comment) ON review_requests TO e9x_app;
GRANT INSERT (id, owner_org_id, submitted_by, member_count) ON review_bundles TO e9x_app;
