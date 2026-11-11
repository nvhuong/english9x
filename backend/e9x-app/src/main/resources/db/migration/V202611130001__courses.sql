-- P3.1 courses (features F19, F20). A course is a HEADER (`courses`, from the baseline: owner, exam, the six-character code, shared-pool status) plus immutable
-- VERSIONS (course_versions: title, description, author line, unlock rule) whose CHAPTERS and LESSONS are children of the version. A lesson points at a
-- PUBLISHED version of a strategy lesson (kind LESSON) or of a test (CHAPTER_TEST, MOCK_TEST); the course depends on every one of them in the review workflow.
--
-- MANDATORY (review of P1, M1 / M2): the baseline policies of `courses` were wide (any staff could set status = PUBLISHED / shared_status = APPROVED). They are
-- replaced here, before any endpoint reads or writes a course, by role-and-exam policies:
--   * the header is insert-only for the application except shared_status; its status and title are a mirror of the versions, kept by a SECURITY DEFINER trigger;
--   * the AUTHOR of the exam in the owner's context inserts; the owner's AUTHOR / ORG_ADMIN may ask for sharing (NONE <-> PENDING); only an English9x REVIEWER of
--     the exam moves a course in or out of the shared pool (PENDING -> APPROVED | NONE; the cross-organisation shared pool itself is P6.5's);
--   * everybody (a guest too) reads a PUBLISHED English9x or APPROVED shared course; the owner's context reads its own PUBLISHED ones; the exam's AUTHOR and
--     REVIEWER read every status in the owner's context.

ALTER TABLE courses ADD COLUMN created_by uuid REFERENCES users(id);
ALTER TABLE courses ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE courses ADD CONSTRAINT courses_id_exam_uq UNIQUE (id, exam_code);

CREATE TABLE course_versions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  course_id uuid NOT NULL,
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  version_no integer NOT NULL CHECK (version_no > 0),
  status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED', 'ARCHIVED')),
  title text NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 200),
  description text NOT NULL DEFAULT '' CHECK (length(description) <= 5000),
  author_display text NOT NULL DEFAULT '' CHECK (length(author_display) <= 200),
  unlock_rule text NOT NULL CHECK (unlock_rule IN ('STRICT', 'COMPLETION', 'FREE')),
  created_by uuid NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (course_id, exam_code) REFERENCES courses(id, exam_code),
  UNIQUE (course_id, version_no),
  UNIQUE (id, exam_code)
);
CREATE UNIQUE INDEX course_versions_one_open_uq ON course_versions (course_id) WHERE status IN ('DRAFT', 'CHANGES_REQUESTED', 'IN_REVIEW');
CREATE INDEX course_versions_list_idx ON course_versions (owner_org_id, exam_code, status);
CREATE TRIGGER course_versions_lifecycle BEFORE INSERT OR UPDATE OR DELETE ON course_versions
  FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_lifecycle('updated_at');
CREATE TRIGGER course_versions_decision BEFORE UPDATE ON course_versions
  FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_decision('course');
ALTER TABLE course_versions ENABLE ROW LEVEL SECURITY;

CREATE TABLE course_chapters (
  id uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  course_version_id uuid NOT NULL,
  position smallint NOT NULL CHECK (position BETWEEN 1 AND 100),
  title text NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 200),
  pass_threshold numeric(4,3) NOT NULL DEFAULT 0.7 CHECK (pass_threshold >= 0 AND pass_threshold <= 1),
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  PRIMARY KEY (course_version_id, position),
  FOREIGN KEY (course_version_id, exam_code) REFERENCES course_versions(id, exam_code)
);
ALTER TABLE course_chapters ENABLE ROW LEVEL SECURITY;

CREATE TABLE course_lessons (
  id uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
  course_version_id uuid NOT NULL,
  chapter_position smallint NOT NULL,
  position smallint NOT NULL CHECK (position BETWEEN 1 AND 500),
  kind text NOT NULL CHECK (kind IN ('LESSON', 'CHAPTER_TEST', 'MOCK_TEST')),
  -- the version of the strategy lesson (LESSON) or of the test (CHAPTER_TEST, MOCK_TEST) the learner opens
  ref_id uuid NOT NULL,
  -- a copy of the title of what it points at, so the public outline needs no read of the content
  title text NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 200),
  max_attempts smallint CHECK (max_attempts IS NULL OR max_attempts BETWEEN 1 AND 100),
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  PRIMARY KEY (course_version_id, chapter_position, position),
  FOREIGN KEY (course_version_id, chapter_position) REFERENCES course_chapters(course_version_id, position),
  FOREIGN KEY (course_version_id, exam_code) REFERENCES course_versions(id, exam_code)
);
CREATE INDEX course_lessons_ref_idx ON course_lessons (ref_id);
ALTER TABLE course_lessons ENABLE ROW LEVEL SECURITY;

-- ---- guards of the children (SECURITY DEFINER: a parent it cannot see is a refusal, never a pass) ------------------------------------
CREATE FUNCTION course_children_guard() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_status text;
  v_owner uuid;
  v_exam text;
  v_ref_owner uuid;
  v_ref_found boolean;
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    SELECT c.status INTO v_status FROM course_versions c WHERE c.id = OLD.course_version_id;
    IF v_status IS NULL OR v_status NOT IN ('DRAFT', 'CHANGES_REQUESTED') THEN
      RAISE EXCEPTION 'the chapters of a % course version cannot change', coalesce(v_status, 'missing') USING ERRCODE = '23000';
    END IF;
  END IF;
  IF TG_OP IN ('INSERT', 'UPDATE') THEN
    SELECT c.status, c.owner_org_id, c.exam_code INTO v_status, v_owner, v_exam FROM course_versions c WHERE c.id = NEW.course_version_id;
    IF v_status IS NULL OR v_status NOT IN ('DRAFT', 'CHANGES_REQUESTED') THEN
      RAISE EXCEPTION 'the chapters of a % course version cannot change', coalesce(v_status, 'missing') USING ERRCODE = '23000';
    END IF;
    IF v_owner IS DISTINCT FROM NEW.owner_org_id OR v_exam <> NEW.exam_code THEN
      RAISE EXCEPTION 'a chapter row carries the owner and exam of its course version' USING ERRCODE = '23000';
    END IF;
    IF TG_TABLE_NAME = 'course_lessons' THEN
      IF NEW.kind = 'LESSON' THEN
        SELECT true, s.owner_org_id INTO v_ref_found, v_ref_owner FROM strategy_lesson_versions s WHERE s.id = NEW.ref_id AND s.exam_code = v_exam;
      ELSE
        SELECT true, t.owner_org_id INTO v_ref_found, v_ref_owner FROM test_versions t WHERE t.id = NEW.ref_id AND t.exam_code = v_exam;
      END IF;
      IF v_ref_found IS NOT TRUE OR v_ref_owner IS DISTINCT FROM v_owner THEN
        RAISE EXCEPTION 'a course holds lessons and tests of its own owner and exam' USING ERRCODE = '23000';
      END IF;
    END IF;
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER course_chapters_guard BEFORE INSERT OR UPDATE OR DELETE ON course_chapters
  FOR EACH ROW EXECUTE FUNCTION course_children_guard();
CREATE TRIGGER course_lessons_guard BEFORE INSERT OR UPDATE OR DELETE ON course_lessons
  FOR EACH ROW EXECUTE FUNCTION course_children_guard();

-- ---- the header mirrors its versions: PUBLISHED while one is, otherwise the status of the latest; the title of the version in force ------
CREATE FUNCTION course_versions_sync_header() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_status text;
  v_title text;
BEGIN
  SELECT v.status, v.title INTO v_status, v_title FROM course_versions v WHERE v.course_id = NEW.course_id AND v.status = 'PUBLISHED'
   ORDER BY v.version_no DESC LIMIT 1;
  IF NOT FOUND THEN
    SELECT v.status, v.title INTO v_status, v_title FROM course_versions v WHERE v.course_id = NEW.course_id ORDER BY v.version_no DESC LIMIT 1;
  END IF;
  UPDATE courses SET status = v_status, title = v_title WHERE id = NEW.course_id AND (status <> v_status OR title <> v_title);
  RETURN NEW;
END $$;
CREATE TRIGGER course_versions_sync AFTER INSERT OR UPDATE ON course_versions
  FOR EACH ROW EXECUTE FUNCTION course_versions_sync_header();

-- ---- the media of a course: which courses hold a file (so that an entitled learner may listen to it) ------------------------------------
-- Returns the ids of the courses whose PUBLISHED or ARCHIVED version has a lesson (or a test) that names the file. Takes no person and answers the same for
-- everybody; the caller (the course module) asks Entitlements whether THIS person is entitled to one of them. SECURITY DEFINER because the content tables
-- are not readable by a learner.
CREATE FUNCTION course_ids_with_media(p_media uuid) RETURNS SETOF uuid
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT DISTINCT v.course_id
    FROM course_versions v JOIN course_lessons l ON l.course_version_id = v.id
   WHERE v.status IN ('PUBLISHED', 'ARCHIVED')
     AND ((l.kind = 'LESSON' AND EXISTS (SELECT 1 FROM strategy_lesson_versions s WHERE s.id = l.ref_id
                                           AND position(p_media::text IN s.blocks::text) > 0))
          OR (l.kind <> 'LESSON' AND EXISTS (
                SELECT 1 FROM test_part_groups pg
                  JOIN question_group_versions g ON g.id = pg.group_version_id
                 WHERE pg.test_version_id = l.ref_id
                   AND (position(p_media::text IN g.content::text) > 0
                        OR EXISTS (SELECT 1 FROM question_group_items i JOIN question_versions q ON q.id = i.question_version_id
                                    WHERE i.group_version_id = g.id AND position(p_media::text IN q.content::text) > 0)))))
$$;
REVOKE ALL ON FUNCTION course_ids_with_media(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION course_ids_with_media(uuid) TO e9x_app;

-- ==== policies ====================================================================================================================
DROP POLICY courses_select ON courses;
DROP POLICY courses_staff_review ON courses;
DROP POLICY courses_insert ON courses;
DROP POLICY courses_update ON courses;
DROP POLICY courses_delete ON courses;

CREATE POLICY courses_select_public ON courses FOR SELECT TO e9x_app
  USING (status = 'PUBLISHED' AND (owner_org_id IS NULL OR shared_status = 'APPROVED'));
CREATE POLICY courses_select_own_org ON courses FOR SELECT TO e9x_app
  USING (status = 'PUBLISHED' AND owner_org_id IS NOT NULL AND owner_org_id = e9x_current_org());
CREATE POLICY courses_select_staff ON courses FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']));
CREATE POLICY courses_select_shared_review ON courses FOR SELECT TO e9x_app
  USING (owner_org_id IS NOT NULL AND shared_status IN ('PENDING', 'APPROVED') AND e9x_workflow_in_context(NULL)
         AND e9x_workflow_has_role(NULL, exam_code, ARRAY['REVIEWER']));
CREATE POLICY courses_insert ON courses FOR INSERT TO e9x_app
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
              AND status = 'DRAFT' AND shared_status = 'NONE' AND created_by = e9x_current_user());
-- asking for the shared pool (and withdrawing the request): the owner's AUTHOR or ORG_ADMIN, in the owner's context
CREATE POLICY courses_share_request ON courses FOR UPDATE TO e9x_app
  USING (owner_org_id IS NOT NULL AND e9x_workflow_in_context(owner_org_id) AND shared_status IN ('NONE', 'PENDING')
         AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'ORG_ADMIN']))
  WITH CHECK (owner_org_id IS NOT NULL AND e9x_workflow_in_context(owner_org_id) AND shared_status IN ('NONE', 'PENDING')
              AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'ORG_ADMIN']));
-- deciding: an English9x REVIEWER of the exam (the trigger courses_guard_shared_approval also wants the English9x flag)
CREATE POLICY courses_share_decide ON courses FOR UPDATE TO e9x_app
  USING (owner_org_id IS NOT NULL AND shared_status IN ('PENDING', 'APPROVED') AND e9x_workflow_in_context(NULL)
         AND e9x_workflow_has_role(NULL, exam_code, ARRAY['REVIEWER']))
  WITH CHECK (owner_org_id IS NOT NULL AND shared_status IN ('NONE', 'PENDING', 'APPROVED') AND e9x_workflow_in_context(NULL)
              AND e9x_workflow_has_role(NULL, exam_code, ARRAY['REVIEWER']));

REVOKE ALL ON courses FROM e9x_app;
GRANT SELECT ON courses TO e9x_app;
GRANT INSERT (id, owner_org_id, exam_code, code, title, status, created_by) ON courses TO e9x_app;
GRANT UPDATE (shared_status) ON courses TO e9x_app;

-- ---- course_versions (the pattern of test_versions)
CREATE POLICY course_versions_select_staff ON course_versions FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']));
CREATE POLICY course_versions_select_public ON course_versions FOR SELECT TO e9x_app
  USING ((status IN ('PUBLISHED', 'ARCHIVED') AND (owner_org_id IS NULL OR owner_org_id = e9x_current_org()))
         OR (status = 'PUBLISHED' AND EXISTS (SELECT 1 FROM courses c WHERE c.id = course_id AND c.shared_status = 'APPROVED')));
CREATE POLICY course_versions_insert ON course_versions FOR INSERT TO e9x_app WITH CHECK (
  e9x_workflow_in_context(owner_org_id)
  AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
  AND status = 'DRAFT' AND created_by = e9x_current_user()
  AND EXISTS (SELECT 1 FROM courses c WHERE c.id = course_id AND c.exam_code = course_versions.exam_code
                 AND c.owner_org_id IS NOT DISTINCT FROM course_versions.owner_org_id));
CREATE POLICY course_versions_update_author ON course_versions FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
         AND created_by = e9x_current_user() AND status IN ('DRAFT', 'CHANGES_REQUESTED'))
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
              AND created_by = e9x_current_user() AND status IN ('DRAFT', 'CHANGES_REQUESTED', 'IN_REVIEW'));
CREATE POLICY course_versions_update_reviewer ON course_versions FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['REVIEWER']) AND status IN ('IN_REVIEW', 'PUBLISHED'))
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['REVIEWER'])
              AND status IN ('IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED', 'ARCHIVED'));
CREATE POLICY course_versions_lock_published ON course_versions FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']) AND status = 'PUBLISHED')
  WITH CHECK (false);

-- ---- chapters and lessons: read like their version; written (INSERT, DELETE) by the creator of the parent version while it is editable
CREATE POLICY course_chapters_select_staff ON course_chapters FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']));
CREATE POLICY course_chapters_select_public ON course_chapters FOR SELECT TO e9x_app
  USING (EXISTS (SELECT 1 FROM course_versions v WHERE v.id = course_version_id AND v.status IN ('PUBLISHED', 'ARCHIVED')));
CREATE POLICY course_chapters_insert ON course_chapters FOR INSERT TO e9x_app WITH CHECK (
  e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
  AND EXISTS (SELECT 1 FROM course_versions v WHERE v.id = course_version_id AND v.created_by = e9x_current_user()));
CREATE POLICY course_chapters_delete ON course_chapters FOR DELETE TO e9x_app USING (
  e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
  AND EXISTS (SELECT 1 FROM course_versions v WHERE v.id = course_version_id AND v.created_by = e9x_current_user()));

CREATE POLICY course_lessons_select_staff ON course_lessons FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']));
CREATE POLICY course_lessons_select_public ON course_lessons FOR SELECT TO e9x_app
  USING (EXISTS (SELECT 1 FROM course_versions v WHERE v.id = course_version_id AND v.status IN ('PUBLISHED', 'ARCHIVED')));
CREATE POLICY course_lessons_insert ON course_lessons FOR INSERT TO e9x_app WITH CHECK (
  e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
  AND EXISTS (SELECT 1 FROM course_versions v WHERE v.id = course_version_id AND v.created_by = e9x_current_user()));
CREATE POLICY course_lessons_delete ON course_lessons FOR DELETE TO e9x_app USING (
  e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
  AND EXISTS (SELECT 1 FROM course_versions v WHERE v.id = course_version_id AND v.created_by = e9x_current_user()));

GRANT SELECT ON course_versions, course_chapters, course_lessons TO e9x_app;
GRANT INSERT (id, course_id, owner_org_id, exam_code, version_no, title, description, author_display, unlock_rule, created_by) ON course_versions TO e9x_app;
GRANT UPDATE (status, title, description, author_display, unlock_rule, updated_at) ON course_versions TO e9x_app;
GRANT INSERT (id, course_version_id, position, title, pass_threshold, owner_org_id, exam_code) ON course_chapters TO e9x_app;
GRANT DELETE ON course_chapters TO e9x_app;
GRANT INSERT (id, course_version_id, chapter_position, position, kind, ref_id, title, max_attempts, owner_org_id, exam_code) ON course_lessons TO e9x_app;
GRANT DELETE ON course_lessons TO e9x_app;
