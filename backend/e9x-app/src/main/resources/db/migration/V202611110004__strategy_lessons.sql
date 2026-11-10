-- P2.4 strategy lessons (feature F14). The plan text names V202611110002; that number is P5.1's, 0003 is P2.3's.
--
-- Same shape as questions (V202611110003): a HEADER (owner, exam, creator: never changes) plus immutable VERSIONS. A version holds the title, the
-- ordered content blocks (jsonb array, validated against catalog/strategy-block.schema.json by the service) and, in the CHILD table
-- strategy_lesson_types, the question types the lesson teaches (>= 1, enforced by the service; each is a type of the lesson's exam, by foreign key).
-- A lesson has no solution side: what is in it is what the learner reads. An embedded question is a block that names a question VERSION; the workflow
-- dependency (VersionRef "question") stops the lesson publishing before that version is PUBLISHED, and the learner reads the question through
-- e9x_question_exam_view (no answer key).
--
-- Who sees what ("in the context" = e9x_workflow_in_context): the AUTHOR or REVIEWER of the row's exam, in the row's own context, every status.
-- Nobody else. A learner reads PUBLISHED / ARCHIVED lessons only through e9x_strategy_lesson_exam_view (whether they may read THIS lesson, an
-- entitlement, is the caller's rule: P3.2). Writes: an AUTHOR inserts a DRAFT as themselves and edits only a version they created (ledger 141 default);
-- a REVIEWER locks and decides; a version is decided only through its open review (e9x_guard_version_decision); at most one open version per lesson.

CREATE TABLE strategy_lessons (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  created_by uuid NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, exam_code)
);
CREATE INDEX strategy_lessons_owner_exam_idx ON strategy_lessons (owner_org_id, exam_code, created_at DESC);
ALTER TABLE strategy_lessons ENABLE ROW LEVEL SECURITY;

CREATE TABLE strategy_lesson_versions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  lesson_id uuid NOT NULL,
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  version_no integer NOT NULL CHECK (version_no > 0),
  status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED', 'ARCHIVED')),
  title text NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 200),
  blocks jsonb NOT NULL CHECK (jsonb_typeof(blocks) = 'array'),
  created_by uuid NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (lesson_id, exam_code) REFERENCES strategy_lessons(id, exam_code),
  UNIQUE (lesson_id, version_no),
  UNIQUE (id, exam_code)
);
CREATE UNIQUE INDEX strategy_lesson_versions_one_open_uq ON strategy_lesson_versions (lesson_id) WHERE status IN ('DRAFT', 'CHANGES_REQUESTED', 'IN_REVIEW');
CREATE INDEX strategy_lesson_versions_list_idx ON strategy_lesson_versions (owner_org_id, exam_code, status);
CREATE TRIGGER strategy_lesson_versions_lifecycle BEFORE INSERT OR UPDATE OR DELETE ON strategy_lesson_versions
  FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_lifecycle('updated_at');
CREATE TRIGGER strategy_lesson_versions_decision BEFORE UPDATE ON strategy_lesson_versions
  FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_decision('strategy_lesson');
ALTER TABLE strategy_lesson_versions ENABLE ROW LEVEL SECURITY;

-- The question types a version teaches: a CHILD of the version with its own guard. Owner and exam are copies equal to the parent's.
CREATE TABLE strategy_lesson_types (
  version_id uuid NOT NULL,
  type_code text NOT NULL,
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  PRIMARY KEY (version_id, type_code),
  FOREIGN KEY (version_id, exam_code) REFERENCES strategy_lesson_versions(id, exam_code),
  FOREIGN KEY (type_code, exam_code) REFERENCES question_types(code, exam_code)
);
CREATE INDEX strategy_lesson_types_type_idx ON strategy_lesson_types (type_code);
ALTER TABLE strategy_lesson_types ENABLE ROW LEVEL SECURITY;

-- SECURITY DEFINER so the guard sees the parent whatever the caller's policies show: a row it cannot find is refused, never allowed.
CREATE FUNCTION strategy_lesson_types_guard() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_status text;
  v_owner uuid;
  v_exam text;
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    SELECT v.status INTO v_status FROM strategy_lesson_versions v WHERE v.id = OLD.version_id;
    IF v_status IS NULL OR v_status NOT IN ('DRAFT', 'CHANGES_REQUESTED') THEN
      RAISE EXCEPTION 'the question types of a % lesson version cannot change', coalesce(v_status, 'missing') USING ERRCODE = '23000';
    END IF;
  END IF;
  IF TG_OP IN ('INSERT', 'UPDATE') THEN
    SELECT v.status, v.owner_org_id, v.exam_code INTO v_status, v_owner, v_exam FROM strategy_lesson_versions v WHERE v.id = NEW.version_id;
    IF v_status IS NULL OR v_status NOT IN ('DRAFT', 'CHANGES_REQUESTED') THEN
      RAISE EXCEPTION 'the question types of a % lesson version cannot change', coalesce(v_status, 'missing') USING ERRCODE = '23000';
    END IF;
    IF v_owner IS DISTINCT FROM NEW.owner_org_id OR v_exam <> NEW.exam_code THEN
      RAISE EXCEPTION 'a type row carries the owner and exam of its lesson version' USING ERRCODE = '23000';
    END IF;
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER strategy_lesson_types_guard_trg BEFORE INSERT OR UPDATE OR DELETE ON strategy_lesson_types
  FOR EACH ROW EXECUTE FUNCTION strategy_lesson_types_guard();

-- ==== policies ==================================================================================================================
CREATE POLICY strategy_lessons_select ON strategy_lessons FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']));
CREATE POLICY strategy_lessons_insert ON strategy_lessons FOR INSERT TO e9x_app
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR']) AND created_by = e9x_current_user());

CREATE POLICY strategy_lesson_versions_select ON strategy_lesson_versions FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']));
CREATE POLICY strategy_lesson_versions_insert ON strategy_lesson_versions FOR INSERT TO e9x_app WITH CHECK (
  e9x_workflow_in_context(owner_org_id)
  AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
  AND status = 'DRAFT' AND created_by = e9x_current_user()
  AND EXISTS (SELECT 1 FROM strategy_lessons l WHERE l.id = lesson_id AND l.exam_code = strategy_lesson_versions.exam_code
                 AND l.owner_org_id IS NOT DISTINCT FROM strategy_lesson_versions.owner_org_id));
CREATE POLICY strategy_lesson_versions_update_author ON strategy_lesson_versions FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
         AND created_by = e9x_current_user() AND status IN ('DRAFT', 'CHANGES_REQUESTED'))
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
              AND created_by = e9x_current_user() AND status IN ('DRAFT', 'CHANGES_REQUESTED', 'IN_REVIEW'));
CREATE POLICY strategy_lesson_versions_update_reviewer ON strategy_lesson_versions FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['REVIEWER'])
         AND status IN ('IN_REVIEW', 'PUBLISHED'))
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['REVIEWER'])
              AND status IN ('IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED', 'ARCHIVED'));
-- SELECT ... FOR SHARE (a dependency lock, e.g. a course locking its lessons) applies the UPDATE policy: lock-only, changes nothing.
CREATE POLICY strategy_lesson_versions_lock_published ON strategy_lesson_versions FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']) AND status = 'PUBLISHED')
  WITH CHECK (false);

CREATE POLICY strategy_lesson_types_select ON strategy_lesson_types FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']));
CREATE POLICY strategy_lesson_types_insert ON strategy_lesson_types FOR INSERT TO e9x_app WITH CHECK (
  e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
  AND EXISTS (SELECT 1 FROM strategy_lesson_versions v WHERE v.id = version_id AND v.created_by = e9x_current_user()));
CREATE POLICY strategy_lesson_types_delete ON strategy_lesson_types FOR DELETE TO e9x_app USING (
  e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
  AND EXISTS (SELECT 1 FROM strategy_lesson_versions v WHERE v.id = version_id AND v.created_by = e9x_current_user()));

-- ==== the learner's way in ======================================================================================================
-- The content of a PUBLISHED (or later ARCHIVED) lesson version of English9x or of the current organisation. SECURITY DEFINER because a learner has no
-- policy on the version tables. Whether this person may open this lesson is the caller's rule (P3.2). Blocks name embedded questions by version id only;
-- the question itself is read through e9x_question_exam_view, which has no answer side.
CREATE FUNCTION e9x_strategy_lesson_exam_view(p_version uuid)
  RETURNS TABLE (version_id uuid, lesson_id uuid, exam_code text, title text, blocks jsonb, question_type_codes text[])
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT v.id, v.lesson_id, v.exam_code, v.title, v.blocks,
         coalesce((SELECT array_agg(t.type_code ORDER BY t.type_code) FROM strategy_lesson_types t WHERE t.version_id = v.id), '{}')
    FROM strategy_lesson_versions v
   WHERE v.id = p_version AND e9x_current_user() IS NOT NULL AND v.status IN ('PUBLISHED', 'ARCHIVED')
     AND (v.owner_org_id IS NULL OR v.owner_org_id = e9x_current_org()) $$;

-- Status and owner of a published or archived version of English9x or of the current organisation (ReviewableType#readStatus).
CREATE FUNCTION e9x_strategy_lesson_version_status(p_version uuid) RETURNS TABLE (status text, owner_org_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT v.status, v.owner_org_id FROM strategy_lesson_versions v
   WHERE v.id = p_version AND e9x_current_user() IS NOT NULL AND v.status IN ('PUBLISHED', 'ARCHIVED')
     AND (v.owner_org_id IS NULL OR v.owner_org_id = e9x_current_org()) $$;
REVOKE ALL ON FUNCTION e9x_strategy_lesson_exam_view(uuid), e9x_strategy_lesson_version_status(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION e9x_strategy_lesson_exam_view(uuid), e9x_strategy_lesson_version_status(uuid) TO e9x_app;

-- ==== privileges ================================================================================================================
GRANT SELECT ON strategy_lessons, strategy_lesson_versions, strategy_lesson_types TO e9x_app;
GRANT INSERT (id, owner_org_id, exam_code, created_by) ON strategy_lessons TO e9x_app;
GRANT INSERT (id, lesson_id, owner_org_id, exam_code, version_no, title, blocks, created_by) ON strategy_lesson_versions TO e9x_app;
GRANT UPDATE (status, title, blocks, updated_at) ON strategy_lesson_versions TO e9x_app;
GRANT INSERT (version_id, type_code, owner_org_id, exam_code) ON strategy_lesson_types TO e9x_app;
GRANT DELETE ON strategy_lesson_types TO e9x_app;
