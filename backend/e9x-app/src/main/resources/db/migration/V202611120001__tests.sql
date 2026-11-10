-- P2.6 tests (the builder of exams; feature F13). A TEST is assembled from PUBLISHED question groups, section by section, and is itself versioned
-- content that goes through the review workflow like a question group does (P2.3 pattern, spec 7.1).
--
-- Shape. A test is a HEADER (who owns it, which exam, which variant: never changes) plus immutable VERSIONS:
--   tests            owner_org_id (NULL = English9x), exam_code, variant (IELTS: AC | GT; TOEIC: none)
--   test_versions    title, max_resumes (NULL = the exam's default, e9x.exam.default-max-resumes), status
--   test_parts       the sections of a version (a TOEIC part, an IELTS skill), in order, each with its time limit
--   test_part_groups the ordered GROUP VERSIONS (P2.3) asked in a part. A version pins exact group versions, so an attempt that began on a test
--                    version keeps reading the very questions it began on (question and group versions are served PUBLISHED or ARCHIVED).
--
-- A test version is not a secret: it names groups, it holds none of the solution side (that lives in question_versions.answer_key / explanation and
-- question_group_versions.solution, which no learner reads). So a version, its parts and its groups are readable, once PUBLISHED or ARCHIVED, in the owner's
-- context by everybody (the exam room needs them, and so does the grader, which resolves the score table of the version's test). Drafts and versions under
-- review are readable by the AUTHOR or REVIEWER of the exam in the owner's context only. What the learner is SHOWN of the questions is the exam view
-- (QuestionVersions#examView, QuestionGroupVersions#examView): content side only.
--
-- Writes follow P2.3 exactly: an AUTHOR inserts a DRAFT version as themselves and edits only a version they created; a REVIEWER moves IN_REVIEW to PUBLISHED /
-- CHANGES_REQUESTED and PUBLISHED to ARCHIVED; the lifecycle trigger refuses every other move and every edit of a version that is not DRAFT / CHANGES_REQUESTED;
-- a decision needs its open review; at most one open version per test. The children have their own guard (P1.6 note): nothing is added, changed or removed
-- unless the parent version is DRAFT / CHANGES_REQUESTED. A test depends on its group versions in the workflow (it cannot be approved while one is not
-- PUBLISHED); that is the service's Reviewable, not a table.

CREATE TABLE tests (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  variant text CHECK (variant IS NULL OR variant ~ '^[A-Z]{1,8}$'),
  created_by uuid NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, exam_code)
);
ALTER TABLE tests ENABLE ROW LEVEL SECURITY;
CREATE INDEX tests_owner_exam_idx ON tests (owner_org_id, exam_code, created_at DESC);

CREATE TABLE test_versions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  test_id uuid NOT NULL,
  -- copies of the header's owner, exam and variant, forced equal by the INSERT policy and immutable (no UPDATE privilege): no policy needs a join
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  variant text,
  version_no integer NOT NULL CHECK (version_no > 0),
  status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED', 'ARCHIVED')),
  title text NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 200),
  -- how many times a learner may reopen an attempt before it is submitted by itself (spec 8, "N lần quay lại"); NULL = e9x.exam.default-max-resumes
  max_resumes smallint CHECK (max_resumes IS NULL OR max_resumes BETWEEN 0 AND 20),
  created_by uuid NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (test_id, exam_code) REFERENCES tests(id, exam_code),
  UNIQUE (test_id, version_no),
  UNIQUE (id, exam_code)
);
CREATE UNIQUE INDEX test_versions_one_open_uq ON test_versions (test_id) WHERE status IN ('DRAFT', 'CHANGES_REQUESTED', 'IN_REVIEW');
CREATE INDEX test_versions_list_idx ON test_versions (owner_org_id, exam_code, status);
CREATE TRIGGER test_versions_lifecycle BEFORE INSERT OR UPDATE OR DELETE ON test_versions
  FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_lifecycle('updated_at');
CREATE TRIGGER test_versions_decision BEFORE UPDATE ON test_versions
  FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_decision('test');
ALTER TABLE test_versions ENABLE ROW LEVEL SECURITY;

CREATE TABLE test_parts (
  test_version_id uuid NOT NULL,
  position smallint NOT NULL CHECK (position BETWEEN 1 AND 20),
  section_code text NOT NULL,
  duration_sec integer NOT NULL CHECK (duration_sec BETWEEN 1 AND 86400),
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  PRIMARY KEY (test_version_id, position),
  UNIQUE (test_version_id, section_code),
  FOREIGN KEY (test_version_id, exam_code) REFERENCES test_versions(id, exam_code),
  FOREIGN KEY (exam_code, section_code) REFERENCES exam_sections(exam_code, code)
);
ALTER TABLE test_parts ENABLE ROW LEVEL SECURITY;

CREATE TABLE test_part_groups (
  test_version_id uuid NOT NULL,
  part_position smallint NOT NULL,
  position smallint NOT NULL CHECK (position BETWEEN 1 AND 500),
  group_version_id uuid NOT NULL,
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  PRIMARY KEY (test_version_id, part_position, position),
  -- one group version once per test: asking the same stimulus twice would count its questions twice
  UNIQUE (test_version_id, group_version_id),
  FOREIGN KEY (test_version_id, part_position) REFERENCES test_parts(test_version_id, position),
  FOREIGN KEY (group_version_id, exam_code) REFERENCES question_group_versions(id, exam_code)
);
CREATE INDEX test_part_groups_group_idx ON test_part_groups (group_version_id);
ALTER TABLE test_part_groups ENABLE ROW LEVEL SECURITY;

-- ---- the guards of the children ---------------------------------------------------------------------------------------------------
-- SECURITY DEFINER so the guard sees the parent whatever the caller's policies show: a row it cannot find is refused, never allowed. One function for
-- both tables: both carry test_version_id, owner_org_id and exam_code; the groups additionally name the group version.
CREATE FUNCTION test_children_guard() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_status text;
  v_owner uuid;
  v_exam text;
  v_group_owner uuid;
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    SELECT t.status INTO v_status FROM test_versions t WHERE t.id = OLD.test_version_id;
    IF v_status IS NULL OR v_status NOT IN ('DRAFT', 'CHANGES_REQUESTED') THEN
      RAISE EXCEPTION 'the parts of a % test version cannot change', coalesce(v_status, 'missing') USING ERRCODE = '23000';
    END IF;
  END IF;
  IF TG_OP IN ('INSERT', 'UPDATE') THEN
    SELECT t.status, t.owner_org_id, t.exam_code INTO v_status, v_owner, v_exam FROM test_versions t WHERE t.id = NEW.test_version_id;
    IF v_status IS NULL OR v_status NOT IN ('DRAFT', 'CHANGES_REQUESTED') THEN
      RAISE EXCEPTION 'the parts of a % test version cannot change', coalesce(v_status, 'missing') USING ERRCODE = '23000';
    END IF;
    IF v_owner IS DISTINCT FROM NEW.owner_org_id OR v_exam <> NEW.exam_code THEN
      RAISE EXCEPTION 'a part row carries the owner and exam of its test version' USING ERRCODE = '23000';
    END IF;
    IF TG_TABLE_NAME = 'test_part_groups' THEN
      SELECT g.owner_org_id INTO v_group_owner FROM question_group_versions g WHERE g.id = NEW.group_version_id;
      IF NOT FOUND OR v_group_owner IS DISTINCT FROM v_owner THEN
        RAISE EXCEPTION 'a test holds question groups of its own owner' USING ERRCODE = '23000';
      END IF;
    END IF;
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER test_parts_guard BEFORE INSERT OR UPDATE OR DELETE ON test_parts
  FOR EACH ROW EXECUTE FUNCTION test_children_guard();
CREATE TRIGGER test_part_groups_guard BEFORE INSERT OR UPDATE OR DELETE ON test_part_groups
  FOR EACH ROW EXECUTE FUNCTION test_children_guard();

-- ==== policies ==================================================================================================================
-- ---- tests (the header: insert-only for the application; read by the exam's staff)
CREATE POLICY tests_select ON tests FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']));
CREATE POLICY tests_insert ON tests FOR INSERT TO e9x_app
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR']) AND created_by = e9x_current_user());

-- ---- test_versions
CREATE POLICY test_versions_select_staff ON test_versions FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']));
CREATE POLICY test_versions_select_public ON test_versions FOR SELECT TO e9x_app, e9x_grader
  USING (status IN ('PUBLISHED', 'ARCHIVED') AND (owner_org_id IS NULL OR owner_org_id = e9x_current_org()));
CREATE POLICY test_versions_insert ON test_versions FOR INSERT TO e9x_app WITH CHECK (
  e9x_workflow_in_context(owner_org_id)
  AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
  AND status = 'DRAFT' AND created_by = e9x_current_user()
  AND EXISTS (SELECT 1 FROM tests t WHERE t.id = test_id AND t.exam_code = test_versions.exam_code
                 AND t.owner_org_id IS NOT DISTINCT FROM test_versions.owner_org_id AND t.variant IS NOT DISTINCT FROM test_versions.variant));
CREATE POLICY test_versions_update_author ON test_versions FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
         AND created_by = e9x_current_user() AND status IN ('DRAFT', 'CHANGES_REQUESTED'))
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
              AND created_by = e9x_current_user() AND status IN ('DRAFT', 'CHANGES_REQUESTED', 'IN_REVIEW'));
CREATE POLICY test_versions_update_reviewer ON test_versions FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['REVIEWER'])
         AND status IN ('IN_REVIEW', 'PUBLISHED'))
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['REVIEWER'])
              AND status IN ('IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED', 'ARCHIVED'));
-- SELECT ... FOR SHARE (a dependency locked while a review decides) applies the UPDATE policy: an author or reviewer may lock a PUBLISHED row, change nothing.
CREATE POLICY test_versions_lock_published ON test_versions FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']) AND status = 'PUBLISHED')
  WITH CHECK (false);

-- ---- test_parts and test_part_groups: read like their version (staff always; everybody once the version is PUBLISHED / ARCHIVED); written (INSERT, DELETE)
-- by the creator of the parent version while it is DRAFT / CHANGES_REQUESTED (the guard above says the second half)
CREATE POLICY test_parts_select_staff ON test_parts FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']));
CREATE POLICY test_parts_select_public ON test_parts FOR SELECT TO e9x_app, e9x_grader
  USING (EXISTS (SELECT 1 FROM test_versions v WHERE v.id = test_version_id AND v.status IN ('PUBLISHED', 'ARCHIVED')
                    AND (v.owner_org_id IS NULL OR v.owner_org_id = e9x_current_org())));
CREATE POLICY test_parts_insert ON test_parts FOR INSERT TO e9x_app WITH CHECK (
  e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
  AND EXISTS (SELECT 1 FROM test_versions v WHERE v.id = test_version_id AND v.created_by = e9x_current_user()));
CREATE POLICY test_parts_delete ON test_parts FOR DELETE TO e9x_app USING (
  e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
  AND EXISTS (SELECT 1 FROM test_versions v WHERE v.id = test_version_id AND v.created_by = e9x_current_user()));

CREATE POLICY test_part_groups_select_staff ON test_part_groups FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']));
CREATE POLICY test_part_groups_select_public ON test_part_groups FOR SELECT TO e9x_app, e9x_grader
  USING (EXISTS (SELECT 1 FROM test_versions v WHERE v.id = test_version_id AND v.status IN ('PUBLISHED', 'ARCHIVED')
                    AND (v.owner_org_id IS NULL OR v.owner_org_id = e9x_current_org())));
CREATE POLICY test_part_groups_insert ON test_part_groups FOR INSERT TO e9x_app WITH CHECK (
  e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
  AND EXISTS (SELECT 1 FROM test_versions v WHERE v.id = test_version_id AND v.created_by = e9x_current_user()));
CREATE POLICY test_part_groups_delete ON test_part_groups FOR DELETE TO e9x_app USING (
  e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
  AND EXISTS (SELECT 1 FROM test_versions v WHERE v.id = test_version_id AND v.created_by = e9x_current_user()));

-- ==== privileges ================================================================================================================
-- The header is insert-only. A version's identity (parent, owner, exam, variant, number, author) never changes: only its title, time and status do.
-- The parts are replaced (DELETE then INSERT), never updated.
GRANT SELECT ON tests, test_versions, test_parts, test_part_groups TO e9x_app;
GRANT SELECT ON test_versions, test_parts, test_part_groups TO e9x_grader;
GRANT INSERT (id, owner_org_id, exam_code, variant, created_by) ON tests TO e9x_app;
GRANT INSERT (id, test_id, owner_org_id, exam_code, variant, version_no, title, max_resumes, created_by) ON test_versions TO e9x_app;
GRANT UPDATE (status, title, max_resumes, updated_at) ON test_versions TO e9x_app;
GRANT INSERT (test_version_id, position, section_code, duration_sec, owner_org_id, exam_code) ON test_parts TO e9x_app;
GRANT DELETE ON test_parts TO e9x_app;
GRANT INSERT (test_version_id, part_position, position, group_version_id, owner_org_id, exam_code) ON test_part_groups TO e9x_app;
GRANT DELETE ON test_part_groups TO e9x_app;
