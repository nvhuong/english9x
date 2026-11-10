-- P2.3 questions and question groups (features F09, F18). The plan text names V202611110001; that number and ...0002 are P5.1's.
--
-- Shape. A question is a HEADER (who owns it, which exam, which type: never changes) plus immutable VERSIONS. Everything an author edits, and
-- everything that is secret, lives on the version:
--   question_versions       content (what the learner sees) | answer_key and explanation (the solution side: the answer key, the transcript of a
--                           listening, a sample answer, notes) | points
--   question_group_versions content (title, instructions, passage, audio / image) | solution (transcript, notes), plus an ordered list of
--                           member question versions in the CHILD table question_group_items.
-- The baseline `questions` table (content, answer_key, status) had no reader or writer; its columns and its wide policies are replaced here,
-- before the first endpoint touches it (P1 final review, M1 and M2: a TOEIC TEACHER read and PUBLISHED a centre's draft, a LEARNER inserted a
-- PUBLISHED organisation question). Nothing in a deployed database can be in that table; the ALTER below would fail on a row, loudly.
--
-- Who sees what (an owner is an organisation, NULL = English9x; "in the context" = e9x_workflow_in_context):
--   * headers and versions: the AUTHOR or REVIEWER of the row's exam, in the row's own context, every status, answer key included.
--     Nobody else: a LEARNER, a TEACHER, the author of another exam, the same person in another organisation's context, an ORG_ADMIN or a
--     SYS_ADMIN who holds no AUTHOR / REVIEWER grant for that exam. A learner never selects a version row, so the answer key and the
--     explanation column are unreachable to them whatever the column grants of the login say. What a learner may see goes through
--     e9x_question_exam_view / e9x_question_group_exam_view below, which return the content side of a PUBLISHED (or later ARCHIVED) version only.
--   * the grader login reads PUBLISHED and ARCHIVED versions of English9x and of its current organisation (it scores an attempt against the
--     version the attempt used, even after a newer one replaced it).
--   * writes: an AUTHOR inserts a DRAFT version as themselves and edits ONLY a version they created (created_by = the caller). Spec 7.1 says an
--     author edits a draft; it does not say a colleague may take it over, and Reviewable.authorId() is one person: the creator is the author, the
--     only person who can edit, submit and (the review trigger) never decide. See the P2.3 report (ledger 141 default). A REVIEWER locks and moves
--     IN_REVIEW to PUBLISHED / CHANGES_REQUESTED and PUBLISHED to ARCHIVED; the lifecycle trigger refuses every other move and every edit of a
--     version that is not DRAFT / CHANGES_REQUESTED, for every writer.
--   * at most one open (DRAFT, CHANGES_REQUESTED, IN_REVIEW) version per question and per group: a second author cannot start a rival draft.
-- No stored generated column on a guarded table (e9x_guard_version_lifecycle compares OLD and NEW).

-- ---- questions: the header -----------------------------------------------------------------------------------------------------
DROP POLICY questions_select ON questions;
DROP POLICY questions_insert ON questions;
DROP POLICY questions_update ON questions;
DROP POLICY questions_delete ON questions;
DROP POLICY questions_grader_select ON questions;
REVOKE ALL ON questions FROM e9x_app, e9x_grader;
ALTER TABLE questions DROP COLUMN content, DROP COLUMN answer_key, DROP COLUMN status;

-- groups come first: a question may name the group it is written for
CREATE TABLE question_groups (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  created_by uuid NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, exam_code)
);
ALTER TABLE question_groups ENABLE ROW LEVEL SECURITY;

ALTER TABLE questions
  ADD COLUMN type_code text NOT NULL,
  ADD COLUMN group_id uuid,
  ADD COLUMN created_by uuid NOT NULL REFERENCES users(id),
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD CONSTRAINT questions_type_fk FOREIGN KEY (type_code, exam_code) REFERENCES question_types(code, exam_code),
  ADD CONSTRAINT questions_group_fk FOREIGN KEY (group_id, exam_code) REFERENCES question_groups(id, exam_code),
  ADD CONSTRAINT questions_id_exam_type_uq UNIQUE (id, exam_code, type_code);
CREATE INDEX questions_owner_exam_idx ON questions (owner_org_id, exam_code, type_code, created_at DESC);
CREATE INDEX questions_group_idx ON questions (group_id) WHERE group_id IS NOT NULL;

-- ---- question_versions ---------------------------------------------------------------------------------------------------------
CREATE TABLE question_versions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  question_id uuid NOT NULL,
  -- copies of the header's owner, exam and type, forced equal by the INSERT policy and immutable (no UPDATE privilege): no policy needs a join
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  type_code text NOT NULL,
  version_no integer NOT NULL CHECK (version_no > 0),
  status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED', 'ARCHIVED')),
  -- validated against the schemas of the type by the service (catalog.api.SchemaValidator); the shape is the database's concern only as far as "an object"
  content jsonb NOT NULL CHECK (jsonb_typeof(content) = 'object'),
  -- SOLUTION SIDE: the answer key, and with it the transcript / sample answer / notes the schema moved there; readable by the author and reviewer of the exam
  answer_key jsonb NOT NULL CHECK (jsonb_typeof(answer_key) = 'object'),
  explanation text CHECK (explanation IS NULL OR length(explanation) <= 20000),
  points numeric(6, 2) NOT NULL DEFAULT 1 CHECK (points > 0 AND points <= 1000),
  created_by uuid NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (question_id, exam_code, type_code) REFERENCES questions(id, exam_code, type_code),
  UNIQUE (question_id, version_no),
  UNIQUE (id, exam_code)
);
CREATE UNIQUE INDEX question_versions_one_open_uq ON question_versions (question_id) WHERE status IN ('DRAFT', 'CHANGES_REQUESTED', 'IN_REVIEW');
CREATE INDEX question_versions_list_idx ON question_versions (owner_org_id, exam_code, type_code, status);
CREATE TRIGGER question_versions_lifecycle BEFORE INSERT OR UPDATE OR DELETE ON question_versions
  FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_lifecycle('updated_at');
ALTER TABLE question_versions ENABLE ROW LEVEL SECURITY;

-- ---- question_group_versions + the members --------------------------------------------------------------------------------------
CREATE TABLE question_group_versions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id uuid NOT NULL,
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  version_no integer NOT NULL CHECK (version_no > 0),
  status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED', 'ARCHIVED')),
  -- what the learner sees of the shared stimulus: title, instructions, passage, audioMediaId, imageMediaId (checked by the service)
  content jsonb NOT NULL CHECK (jsonb_typeof(content) = 'object'),
  -- SOLUTION SIDE: transcript, notes
  solution jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(solution) = 'object'),
  created_by uuid NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (group_id, exam_code) REFERENCES question_groups(id, exam_code),
  UNIQUE (group_id, version_no),
  UNIQUE (id, exam_code)
);
CREATE UNIQUE INDEX question_group_versions_one_open_uq ON question_group_versions (group_id) WHERE status IN ('DRAFT', 'CHANGES_REQUESTED', 'IN_REVIEW');
CREATE TRIGGER question_group_versions_lifecycle BEFORE INSERT OR UPDATE OR DELETE ON question_group_versions
  FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_lifecycle('updated_at');
ALTER TABLE question_group_versions ENABLE ROW LEVEL SECURITY;

-- The ordered member question versions of a group version. A CHILD of the version, so it has its own guard (below): no row is added, changed or
-- removed unless the parent is DRAFT / CHANGES_REQUESTED. owner_org_id and exam_code are copies, equal to the parent's and to the member's
-- (the guard checks both), so the table is tenant-linked like the rest.
CREATE TABLE question_group_items (
  group_version_id uuid NOT NULL,
  position smallint NOT NULL CHECK (position BETWEEN 1 AND 500),
  question_version_id uuid NOT NULL,
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  PRIMARY KEY (group_version_id, position),
  UNIQUE (group_version_id, question_version_id),
  FOREIGN KEY (group_version_id, exam_code) REFERENCES question_group_versions(id, exam_code),
  FOREIGN KEY (question_version_id, exam_code) REFERENCES question_versions(id, exam_code)
);
CREATE INDEX question_group_items_member_idx ON question_group_items (question_version_id);
ALTER TABLE question_group_items ENABLE ROW LEVEL SECURITY;

-- SECURITY DEFINER so the guard sees the parent whatever the caller's policies show: a row it cannot find is refused, never allowed.
CREATE FUNCTION question_group_items_guard() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_status text;
  v_owner uuid;
  v_exam text;
  v_member_owner uuid;
BEGIN
  IF TG_OP IN ('UPDATE', 'DELETE') THEN
    SELECT g.status INTO v_status FROM question_group_versions g WHERE g.id = OLD.group_version_id;
    IF v_status IS NULL OR v_status NOT IN ('DRAFT', 'CHANGES_REQUESTED') THEN
      RAISE EXCEPTION 'the members of a % group version cannot change', coalesce(v_status, 'missing') USING ERRCODE = '23000';
    END IF;
  END IF;
  IF TG_OP IN ('INSERT', 'UPDATE') THEN
    SELECT g.status, g.owner_org_id, g.exam_code INTO v_status, v_owner, v_exam FROM question_group_versions g WHERE g.id = NEW.group_version_id;
    IF v_status IS NULL OR v_status NOT IN ('DRAFT', 'CHANGES_REQUESTED') THEN
      RAISE EXCEPTION 'the members of a % group version cannot change', coalesce(v_status, 'missing') USING ERRCODE = '23000';
    END IF;
    IF v_owner IS DISTINCT FROM NEW.owner_org_id OR v_exam <> NEW.exam_code THEN
      RAISE EXCEPTION 'a member row carries the owner and exam of its group version' USING ERRCODE = '23000';
    END IF;
    SELECT q.owner_org_id INTO v_member_owner FROM question_versions q WHERE q.id = NEW.question_version_id;
    IF NOT FOUND OR v_member_owner IS DISTINCT FROM v_owner THEN
      RAISE EXCEPTION 'a group holds questions of its own owner' USING ERRCODE = '23000';
    END IF;
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER question_group_items_guard_trg BEFORE INSERT OR UPDATE OR DELETE ON question_group_items
  FOR EACH ROW EXECUTE FUNCTION question_group_items_guard();

-- ---- a decision needs its review (P1 final review, triage of ledger 142) -------------------------------------------------------------
-- The lifecycle guard refuses shortcuts between states but cannot know WHY a version moved: a REVIEWER of the exam could set IN_REVIEW to PUBLISHED with a
-- plain UPDATE and no review behind it. For these two content types a version leaves IN_REVIEW (to PUBLISHED or CHANGES_REQUESTED) only while an open
-- review_requests row for it exists, which the workflow service always has locked at that moment. (The decision trigger of review_requests then keeps the
-- decider away from their own work.) Invoker rights: the caller must see the review under its own policies, as a REVIEWER of the exam does. The owner
-- (migrations, seed data) and a superuser are exempt, like the INSERT rule of the lifecycle guard.
CREATE FUNCTION e9x_guard_version_decision() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  IF OLD.status = 'IN_REVIEW' AND NEW.status IN ('PUBLISHED', 'CHANGES_REQUESTED')
     AND current_user <> 'e9x_owner'
     AND NOT (SELECT r.rolsuper FROM pg_roles r WHERE r.rolname = current_user)
     AND NOT EXISTS (SELECT 1 FROM review_requests rr WHERE rr.content_type = TG_ARGV[0] AND rr.version_id = NEW.id AND rr.status = 'IN_REVIEW') THEN
    RAISE EXCEPTION 'a version is decided only through its open review' USING ERRCODE = '23000';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER question_versions_decision BEFORE UPDATE ON question_versions
  FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_decision('question');
CREATE TRIGGER question_group_versions_decision BEFORE UPDATE ON question_group_versions
  FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_decision('question_group');

-- ==== policies ==================================================================================================================
-- ---- headers (insert-only for the application)
CREATE POLICY question_groups_select ON question_groups FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']));
CREATE POLICY question_groups_insert ON question_groups FOR INSERT TO e9x_app
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR']) AND created_by = e9x_current_user());

CREATE POLICY questions_select ON questions FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']));
CREATE POLICY questions_insert ON questions FOR INSERT TO e9x_app
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR']) AND created_by = e9x_current_user()
              AND (group_id IS NULL OR EXISTS (SELECT 1 FROM question_groups g WHERE g.id = group_id AND g.exam_code = questions.exam_code
                                                  AND g.owner_org_id IS NOT DISTINCT FROM questions.owner_org_id)));

-- ---- question_versions
CREATE POLICY question_versions_select_staff ON question_versions FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']));
CREATE POLICY question_versions_select_grader ON question_versions FOR SELECT TO e9x_grader
  USING (status IN ('PUBLISHED', 'ARCHIVED') AND (owner_org_id IS NULL OR owner_org_id = e9x_current_org()));
CREATE POLICY question_versions_insert ON question_versions FOR INSERT TO e9x_app WITH CHECK (
  e9x_workflow_in_context(owner_org_id)
  AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
  AND status = 'DRAFT' AND created_by = e9x_current_user()
  AND EXISTS (SELECT 1 FROM questions q WHERE q.id = question_id AND q.exam_code = question_versions.exam_code
                 AND q.type_code = question_versions.type_code AND q.owner_org_id IS NOT DISTINCT FROM question_versions.owner_org_id));
-- the author edits and submits only a version they created (see the head of this file)
CREATE POLICY question_versions_update_author ON question_versions FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
         AND created_by = e9x_current_user() AND status IN ('DRAFT', 'CHANGES_REQUESTED'))
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
              AND created_by = e9x_current_user() AND status IN ('DRAFT', 'CHANGES_REQUESTED', 'IN_REVIEW'));
CREATE POLICY question_versions_update_reviewer ON question_versions FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['REVIEWER'])
         AND status IN ('IN_REVIEW', 'PUBLISHED'))
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['REVIEWER'])
              AND status IN ('IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED', 'ARCHIVED'));
-- SELECT ... FOR SHARE (the workflow locking a dependency so it cannot be archived mid-approval) applies the UPDATE policy: an author or reviewer of
-- the exam may lock a PUBLISHED row. WITH CHECK (false): this policy lets nobody change anything.
CREATE POLICY question_versions_lock_published ON question_versions FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']) AND status = 'PUBLISHED')
  WITH CHECK (false);

-- ---- question_group_versions
CREATE POLICY question_group_versions_select_staff ON question_group_versions FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']));
CREATE POLICY question_group_versions_insert ON question_group_versions FOR INSERT TO e9x_app WITH CHECK (
  e9x_workflow_in_context(owner_org_id)
  AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
  AND status = 'DRAFT' AND created_by = e9x_current_user()
  AND EXISTS (SELECT 1 FROM question_groups g WHERE g.id = group_id AND g.exam_code = question_group_versions.exam_code
                 AND g.owner_org_id IS NOT DISTINCT FROM question_group_versions.owner_org_id));
CREATE POLICY question_group_versions_update_author ON question_group_versions FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
         AND created_by = e9x_current_user() AND status IN ('DRAFT', 'CHANGES_REQUESTED'))
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
              AND created_by = e9x_current_user() AND status IN ('DRAFT', 'CHANGES_REQUESTED', 'IN_REVIEW'));
CREATE POLICY question_group_versions_update_reviewer ON question_group_versions FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['REVIEWER'])
         AND status IN ('IN_REVIEW', 'PUBLISHED'))
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['REVIEWER'])
              AND status IN ('IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED', 'ARCHIVED'));
CREATE POLICY question_group_versions_lock_published ON question_group_versions FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']) AND status = 'PUBLISHED')
  WITH CHECK (false);

-- ---- question_group_items: read by the exam's author / reviewer; written (INSERT, DELETE) by the creator of the parent version
CREATE POLICY question_group_items_select ON question_group_items FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER']));
CREATE POLICY question_group_items_insert ON question_group_items FOR INSERT TO e9x_app WITH CHECK (
  e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
  AND EXISTS (SELECT 1 FROM question_group_versions g WHERE g.id = group_version_id AND g.created_by = e9x_current_user()));
CREATE POLICY question_group_items_delete ON question_group_items FOR DELETE TO e9x_app USING (
  e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
  AND EXISTS (SELECT 1 FROM question_group_versions g WHERE g.id = group_version_id AND g.created_by = e9x_current_user()));

-- ==== the learner's way in ======================================================================================================
-- The content side of a published version, nothing else: no answer_key, no explanation, no solution. SECURITY DEFINER because the learner has no
-- policy on the version tables. It answers a signed-in person for English9x content and for the content of the organisation of the current
-- context; WHETHER this person may sit this question (an attempt, an entitlement) is the caller's rule (the exam room, P3.3; a lesson, P3.2).
-- ARCHIVED stays visible: an attempt that began on a version keeps reading it after a newer one replaced it.
CREATE FUNCTION e9x_question_exam_view(p_version uuid)
  RETURNS TABLE (version_id uuid, question_id uuid, exam_code text, type_code text, content jsonb, points numeric)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT v.id, v.question_id, v.exam_code, v.type_code, v.content, v.points
    FROM question_versions v
   WHERE v.id = p_version AND e9x_current_user() IS NOT NULL AND v.status IN ('PUBLISHED', 'ARCHIVED')
     AND (v.owner_org_id IS NULL OR v.owner_org_id = e9x_current_org()) $$;

CREATE FUNCTION e9x_question_group_exam_view(p_version uuid)
  RETURNS TABLE (version_id uuid, group_id uuid, exam_code text, content jsonb, question_version_ids uuid[])
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT g.id, g.group_id, g.exam_code, g.content,
         coalesce((SELECT array_agg(i.question_version_id ORDER BY i.position) FROM question_group_items i WHERE i.group_version_id = g.id), '{}')
    FROM question_group_versions g
   WHERE g.id = p_version AND e9x_current_user() IS NOT NULL AND g.status IN ('PUBLISHED', 'ARCHIVED')
     AND (g.owner_org_id IS NULL OR g.owner_org_id = e9x_current_org()) $$;

-- The status and owner of a published or archived version of English9x or of the current organisation: what the workflow needs to accept a
-- dependency on content of another context (ReviewableType#readStatus), without opening the version row (and its answer key) to the reader.
CREATE FUNCTION e9x_question_version_status(p_version uuid) RETURNS TABLE (status text, owner_org_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT v.status, v.owner_org_id FROM question_versions v
   WHERE v.id = p_version AND e9x_current_user() IS NOT NULL AND v.status IN ('PUBLISHED', 'ARCHIVED')
     AND (v.owner_org_id IS NULL OR v.owner_org_id = e9x_current_org()) $$;
CREATE FUNCTION e9x_question_group_version_status(p_version uuid) RETURNS TABLE (status text, owner_org_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT v.status, v.owner_org_id FROM question_group_versions v
   WHERE v.id = p_version AND e9x_current_user() IS NOT NULL AND v.status IN ('PUBLISHED', 'ARCHIVED')
     AND (v.owner_org_id IS NULL OR v.owner_org_id = e9x_current_org()) $$;
REVOKE ALL ON FUNCTION e9x_question_exam_view(uuid), e9x_question_group_exam_view(uuid), e9x_question_version_status(uuid),
  e9x_question_group_version_status(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION e9x_question_exam_view(uuid), e9x_question_group_exam_view(uuid), e9x_question_version_status(uuid),
  e9x_question_group_version_status(uuid) TO e9x_app;

-- ==== privileges ================================================================================================================
-- Headers are insert-only. A version's identity (parent, owner, exam, type, number, author) never changes: only its content and status do.
-- The answer_key and explanation columns are in the SELECT grant on purpose (the author reads their own key): the POLICIES are what keep every
-- other role away from the row (db-test 05 and 40).
GRANT SELECT ON questions, question_groups, question_versions, question_group_versions, question_group_items TO e9x_app;
GRANT SELECT ON question_versions TO e9x_grader;
GRANT INSERT (id, owner_org_id, exam_code, type_code, group_id, created_by) ON questions TO e9x_app;
GRANT INSERT (id, owner_org_id, exam_code, created_by) ON question_groups TO e9x_app;
GRANT INSERT (id, question_id, owner_org_id, exam_code, type_code, version_no, content, answer_key, explanation, points, created_by) ON question_versions TO e9x_app;
GRANT UPDATE (status, content, answer_key, explanation, points, updated_at) ON question_versions TO e9x_app;
GRANT INSERT (id, group_id, owner_org_id, exam_code, version_no, content, solution, created_by) ON question_group_versions TO e9x_app;
GRANT UPDATE (status, content, solution, updated_at) ON question_group_versions TO e9x_app;
GRANT INSERT (group_version_id, position, question_version_id, owner_org_id, exam_code) ON question_group_items TO e9x_app;
GRANT DELETE ON question_group_items TO e9x_app;
