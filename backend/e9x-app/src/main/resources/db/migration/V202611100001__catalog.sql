-- P2.1 catalog (feature base of F09, F15, F16): exams and their sections, the 12 interaction kinds, the question types, score
-- tables, grading skills and grading policies.
--
-- Two kinds of table:
--   * REFERENCE data every tenant reads and nobody but a migration writes: exams (from the baseline), exam_sections,
--     interaction_kinds, question_types. No RLS (there is no tenant in them); e9x_app has SELECT and nothing else, so a
--     compromised statement of the application cannot change what an exam is. db-test 13 holds that allowlist to this rule.
--   * OWNER-SCOPED data (score_tables, grading_skills, grading_policies and the version tables of the first two): owner_org_id
--     (NULL = English9x), RLS by role, exam and context. There is no "any staff" clause anywhere: every write policy names the
--     role AND the exam of the row, in exactly the owner's context (e9x_workflow_has_role / e9x_workflow_in_context, P1.6).
--
-- Reading rules of the owner-scoped data
--   * A score table and a grading policy are not secret and every score and every grading decision needs them, so the header and
--     the PUBLISHED / ARCHIVED versions are readable in the context of the owner and, for English9x rows, in every context.
--     Drafts and versions under review are readable by the AUTHOR / REVIEWER / ORG_ADMIN / SYS_ADMIN of the exam in the owner's
--     context only.
--   * A grading skill is a prompt and a rubric with graded samples: not shown to learners. Readable by the exam's AUTHOR /
--     REVIEWER / ORG_ADMIN / SYS_ADMIN (all statuses) and TEACHER (PUBLISHED) in the owner's context, and by the grader login
--     (PUBLISHED, English9x or the bound organisation).
--   * e9x_grader (scoring, AI grading after a submission) reads score tables and policies the same way a learner does.
--
-- Versions follow the lifecycle of spec 7.1: e9x_guard_version_lifecycle is attached to each version table. The policies add who:
-- an AUTHOR edits DRAFT / CHANGES_REQUESTED rows and submits them; a REVIEWER moves IN_REVIEW rows to CHANGES_REQUESTED /
-- PUBLISHED and PUBLISHED rows to ARCHIVED. That a review approved a publication is the workflow service's rule (P1.6).

-- ---- exams (exists since the baseline): what is new is what the catalog knows about an exam --------------------------------------
ALTER TABLE exams
  -- the keys scores are kept and converted under; the reading of IELTS has one per variant (spec 3.3: two separate tables)
  ADD COLUMN score_skills text[] NOT NULL DEFAULT '{}',
  -- the variants a test of the exam can have (P2.6): IELTS has Academic and General Training
  ADD COLUMN variants text[] NOT NULL DEFAULT '{}';

-- Exams is the one table that had no RLS and was writable by the application: no longer.
REVOKE INSERT, UPDATE, DELETE ON exams FROM e9x_app;
-- P1 final review M4: DELETE grants that no policy uses. Nothing deletes these rows (revoking an entitlement ends it; an attempt is final).
REVOKE DELETE ON organizations, entitlements, exam_attempts FROM e9x_app;

-- ---- reference data ----------------------------------------------------------------------------------------------------------
CREATE TABLE exam_sections (
  exam_code text NOT NULL REFERENCES exams(code),
  code text NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
  skill text NOT NULL CHECK (skill IN ('LISTENING', 'READING', 'WRITING', 'SPEAKING')),
  position smallint NOT NULL CHECK (position > 0),
  -- questions of the section; for IELTS Writing the tasks (2) and for Speaking the parts (3)
  item_count smallint NOT NULL CHECK (item_count > 0),
  PRIMARY KEY (exam_code, code),
  UNIQUE (exam_code, position)
);

CREATE TABLE interaction_kinds (
  code text PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
  -- true = a deterministic scorer exists (ESSAY and SPEAKING_RESPONSE go to AI and/or teachers)
  auto_scored boolean NOT NULL
);

CREATE TABLE question_types (
  code text PRIMARY KEY CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
  exam_code text NOT NULL REFERENCES exams(code),
  section_code text NOT NULL,
  interaction_kind text NOT NULL REFERENCES interaction_kinds(code),
  name text NOT NULL CHECK (length(btrim(name)) > 0),
  -- the Excel template of the type (P2.7 generates it from the JSON Schema); NULL = the type cannot be imported from Excel
  excel_template_id text,
  FOREIGN KEY (exam_code, section_code) REFERENCES exam_sections(exam_code, code),
  UNIQUE (code, exam_code)
);
CREATE INDEX question_types_exam_idx ON question_types (exam_code, section_code);

GRANT SELECT ON exam_sections, interaction_kinds, question_types TO e9x_app, e9x_grader;
GRANT SELECT ON exams TO e9x_grader;

-- ---- common helpers ----------------------------------------------------------------------------------------------------------
-- The skill of a score table must be one the exam keeps scores for.
CREATE FUNCTION catalog_check_score_skill() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM exams e WHERE e.code = NEW.exam_code AND NEW.skill = ANY (e.score_skills)) THEN
    RAISE EXCEPTION 'exam % keeps no score under the skill %', NEW.exam_code, NEW.skill USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

-- ---- score tables --------------------------------------------------------------------------------------------------------------
-- A score table is identified by (exam, skill, scope). EXAM scope is the default of English9x; TEST scope is the override of one TEST
-- (spec 10.2 "bảng ghi đè theo đề"), keyed by test_id and NOT by test version: a new published version of the same test keeps its
-- override. ScoreTables#resolve takes the test version and looks up its test.
--
-- TEST-SCOPE ROWS ARE REFUSED UNTIL P2.6 (the CHECK score_tables_test_scope_refused_ck, 2026-11-10). `tests` does not exist yet, so
-- nothing can say whose test an id is: any author could otherwise squat any id (measured in the P2.1 review: a 23505 on a row the victim
-- cannot see, and a foreign TEST row winning in resolve). P2.6 lifts the CHECK together with:
--   * the foreign key test_id -> tests(id);
--   * an owner-match rule: the row's owner_org_id equals the test's owner (trigger or policy);
--   * writers restricted to the administrator of the test's owner (spec section 2, row 6, "Quản trị đặt": SYS_ADMIN for English9x
--     tests, ORG_ADMIN for an organisation's tests) instead of the exam's AUTHOR;
--   * the guard after publish where the spec requires it.
CREATE TABLE score_tables (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  skill text NOT NULL CHECK (skill ~ '^[A-Z][A-Z0-9_]*$'),
  scope text NOT NULL CHECK (scope IN ('EXAM', 'TEST')),
  test_id uuid,
  -- spec 10.2: TABLE (raw to scaled) or a function of the domain: IELTS_OVERALL, IELTS_WRITING_WEIGHTED, SUM
  function text NOT NULL DEFAULT 'TABLE' CHECK (function IN ('TABLE', 'IELTS_OVERALL', 'IELTS_WRITING_WEIGHTED', 'SUM')),
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT score_tables_scope_ck CHECK (
    (scope = 'EXAM' AND test_id IS NULL AND owner_org_id IS NULL)
    OR (scope = 'TEST' AND test_id IS NOT NULL)),
  -- P2.6 drops this constraint (see the note above): until then no TEST-scope row can be inserted by anyone, the owner included
  CONSTRAINT score_tables_test_scope_refused_ck CHECK (scope <> 'TEST'),
  UNIQUE (id, exam_code)
);
-- The owner is part of every key, so one owner's row never blocks another's (a 23505 on a row the caller cannot see).
CREATE UNIQUE INDEX score_tables_exam_default_uq ON score_tables (exam_code, skill, owner_org_id) NULLS NOT DISTINCT WHERE scope = 'EXAM';
CREATE UNIQUE INDEX score_tables_test_override_uq ON score_tables (owner_org_id, test_id, skill) NULLS NOT DISTINCT WHERE scope = 'TEST';
CREATE TRIGGER score_tables_skill_trg BEFORE INSERT ON score_tables
  FOR EACH ROW EXECUTE FUNCTION catalog_check_score_skill();
ALTER TABLE score_tables ENABLE ROW LEVEL SECURITY;

CREATE TABLE score_table_versions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  score_table_id uuid NOT NULL,
  -- copies of the parent's owner and exam, forced equal by the INSERT policy and immutable (no UPDATE privilege), so a policy never needs a join
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  version_no integer NOT NULL CHECK (version_no > 0),
  status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED', 'ARCHIVED')),
  -- [{"raw": 0, "scaled": 5.0}, ...] contiguous from raw 0 (checked by TableConverter.of in the service); [] for a function without table
  entries jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(entries) = 'array' AND jsonb_array_length(entries) <= 1000),
  created_by uuid REFERENCES users(id),     -- NULL = seeded by a migration
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (score_table_id, exam_code) REFERENCES score_tables(id, exam_code),
  UNIQUE (score_table_id, version_no)
);
CREATE INDEX score_table_versions_resolve_idx ON score_table_versions (score_table_id, status, version_no DESC);
CREATE TRIGGER score_table_versions_lifecycle BEFORE INSERT OR UPDATE OR DELETE ON score_table_versions
  FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_lifecycle('updated_at');
ALTER TABLE score_table_versions ENABLE ROW LEVEL SECURITY;

-- ---- grading skills ----------------------------------------------------------------------------------------------------------
CREATE TABLE grading_skills (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  question_type_code text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (question_type_code, exam_code) REFERENCES question_types(code, exam_code),
  UNIQUE (id, exam_code),
  UNIQUE NULLS NOT DISTINCT (owner_org_id, question_type_code)
);
ALTER TABLE grading_skills ENABLE ROW LEVEL SECURITY;

CREATE TABLE grading_skill_versions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  grading_skill_id uuid NOT NULL,
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  version_no integer NOT NULL CHECK (version_no > 0),
  status text NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED', 'ARCHIVED')),
  rubric text NOT NULL DEFAULT '' CHECK (length(rubric) <= 50000),
  criteria jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(criteria) = 'array'),
  prompt_template text NOT NULL DEFAULT '' CHECK (length(prompt_template) <= 50000),
  samples jsonb NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(samples) = 'array'),
  result_schema jsonb NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(result_schema) = 'object'),
  created_by uuid REFERENCES users(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  FOREIGN KEY (grading_skill_id, exam_code) REFERENCES grading_skills(id, exam_code),
  UNIQUE (grading_skill_id, version_no)
);
CREATE INDEX grading_skill_versions_resolve_idx ON grading_skill_versions (grading_skill_id, status, version_no DESC);
CREATE TRIGGER grading_skill_versions_lifecycle BEFORE INSERT OR UPDATE OR DELETE ON grading_skill_versions
  FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_lifecycle('updated_at');
ALTER TABLE grading_skill_versions ENABLE ROW LEVEL SECURITY;

-- ---- grading policies --------------------------------------------------------------------------------------------------------
-- Spec 10.3: test > organisation > exam. Not versioned (a setting, not content); the mode is AI, TEACHER or BOTH (GradingMode).
-- TEST scope is keyed by test_id (not by version) and is REFUSED UNTIL P2.6 for the same reasons and with the same lift list as
-- score_tables (grading_policies_test_scope_refused_ck): foreign key to tests, owner match, SYS_ADMIN / ORG_ADMIN of the test's owner
-- as the only writers (spec section 2 row 6; the policies below name AUTHOR for TEST scope only as a placeholder nobody can reach), and
-- no change after publish.
-- An ENTERPRISE organisation has no teachers (spec section 4): its ORG policy can only be AI (policy below; P6.5 relies on it).
CREATE TABLE grading_policies (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  scope text NOT NULL CHECK (scope IN ('EXAM', 'ORG', 'TEST')),
  -- the TEST the policy is for (not a test version); no foreign key until P2.6 creates tests
  test_id uuid,
  mode text NOT NULL CHECK (mode IN ('AI', 'TEACHER', 'BOTH')),
  updated_by uuid REFERENCES users(id),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT grading_policies_scope_ck CHECK (
    (scope = 'EXAM' AND owner_org_id IS NULL AND test_id IS NULL)
    OR (scope = 'ORG' AND owner_org_id IS NOT NULL AND test_id IS NULL)
    OR (scope = 'TEST' AND test_id IS NOT NULL)),
  -- P2.6 drops this constraint (see the note above)
  CONSTRAINT grading_policies_test_scope_refused_ck CHECK (scope <> 'TEST')
);
-- The owner is part of every key, so one owner's row never blocks another's.
CREATE UNIQUE INDEX grading_policies_exam_uq ON grading_policies (exam_code, owner_org_id) NULLS NOT DISTINCT WHERE scope = 'EXAM';
CREATE UNIQUE INDEX grading_policies_org_uq ON grading_policies (owner_org_id, exam_code) WHERE scope = 'ORG';
CREATE UNIQUE INDEX grading_policies_test_uq ON grading_policies (owner_org_id, test_id) NULLS NOT DISTINCT WHERE scope = 'TEST';
ALTER TABLE grading_policies ENABLE ROW LEVEL SECURITY;

-- ==== policies ==================================================================================================================
-- "Readable in the owner's context": English9x rows in every context, an organisation's rows in that organisation's context.
-- (No role: the data is not secret and every score / grading decision needs it.)

-- ---- score_tables
CREATE POLICY score_tables_select ON score_tables FOR SELECT TO e9x_app, e9x_grader
  USING (owner_org_id IS NULL OR owner_org_id = e9x_current_org());
CREATE POLICY score_tables_insert ON score_tables FOR INSERT TO e9x_app WITH CHECK (
  e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR']));

-- ---- score_table_versions
CREATE POLICY score_table_versions_select_public ON score_table_versions FOR SELECT TO e9x_app, e9x_grader
  USING (status IN ('PUBLISHED', 'ARCHIVED') AND (owner_org_id IS NULL OR owner_org_id = e9x_current_org()));
CREATE POLICY score_table_versions_select_staff ON score_table_versions FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id)
         AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER', 'ORG_ADMIN', 'SYS_ADMIN']));
CREATE POLICY score_table_versions_insert ON score_table_versions FOR INSERT TO e9x_app WITH CHECK (
  e9x_workflow_in_context(owner_org_id)
  AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
  AND status = 'DRAFT' AND created_by = e9x_current_user()
  AND EXISTS (SELECT 1 FROM score_tables t WHERE t.id = score_table_id AND t.exam_code = score_table_versions.exam_code
                 AND t.owner_org_id IS NOT DISTINCT FROM score_table_versions.owner_org_id));
CREATE POLICY score_table_versions_update_author ON score_table_versions FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
         AND status IN ('DRAFT', 'CHANGES_REQUESTED'))
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
              AND status IN ('DRAFT', 'CHANGES_REQUESTED', 'IN_REVIEW'));
CREATE POLICY score_table_versions_update_reviewer ON score_table_versions FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['REVIEWER'])
         AND status IN ('IN_REVIEW', 'PUBLISHED'))
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['REVIEWER'])
              AND status IN ('IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED', 'ARCHIVED'));
CREATE POLICY score_table_versions_delete ON score_table_versions FOR DELETE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
         AND status IN ('DRAFT', 'CHANGES_REQUESTED'));

-- ---- grading_skills
CREATE POLICY grading_skills_select_staff ON grading_skills FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id)
         AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER', 'TEACHER', 'ORG_ADMIN', 'SYS_ADMIN']));
CREATE POLICY grading_skills_select_grader ON grading_skills FOR SELECT TO e9x_grader
  USING (owner_org_id IS NULL OR owner_org_id = e9x_current_org());
CREATE POLICY grading_skills_insert ON grading_skills FOR INSERT TO e9x_app WITH CHECK (
  e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR']));

-- ---- grading_skill_versions
CREATE POLICY grading_skill_versions_select_staff ON grading_skill_versions FOR SELECT TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id)
         AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'REVIEWER', 'ORG_ADMIN', 'SYS_ADMIN']));
CREATE POLICY grading_skill_versions_select_teacher ON grading_skill_versions FOR SELECT TO e9x_app
  USING (status = 'PUBLISHED' AND e9x_workflow_in_context(owner_org_id)
         AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['TEACHER']));
CREATE POLICY grading_skill_versions_select_grader ON grading_skill_versions FOR SELECT TO e9x_grader
  USING (status = 'PUBLISHED' AND (owner_org_id IS NULL OR owner_org_id = e9x_current_org()));
CREATE POLICY grading_skill_versions_insert ON grading_skill_versions FOR INSERT TO e9x_app WITH CHECK (
  e9x_workflow_in_context(owner_org_id)
  AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
  AND status = 'DRAFT' AND created_by = e9x_current_user()
  AND EXISTS (SELECT 1 FROM grading_skills s WHERE s.id = grading_skill_id AND s.exam_code = grading_skill_versions.exam_code
                 AND s.owner_org_id IS NOT DISTINCT FROM grading_skill_versions.owner_org_id));
CREATE POLICY grading_skill_versions_update_author ON grading_skill_versions FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
         AND status IN ('DRAFT', 'CHANGES_REQUESTED'))
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
              AND status IN ('DRAFT', 'CHANGES_REQUESTED', 'IN_REVIEW'));
CREATE POLICY grading_skill_versions_update_reviewer ON grading_skill_versions FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['REVIEWER'])
         AND status IN ('IN_REVIEW', 'PUBLISHED'))
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['REVIEWER'])
              AND status IN ('IN_REVIEW', 'CHANGES_REQUESTED', 'PUBLISHED', 'ARCHIVED'));
CREATE POLICY grading_skill_versions_delete ON grading_skill_versions FOR DELETE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
         AND status IN ('DRAFT', 'CHANGES_REQUESTED'));

-- ---- grading_policies
-- Readable like a score table. Written by: SYS_ADMIN for the exam default (English9x context); the ORG_ADMIN of the organisation
-- for its own policy; an AUTHOR of the exam for a test's policy (TEST scope is refused until P2.6, which makes it the administrator of the test's owner).
CREATE POLICY grading_policies_select ON grading_policies FOR SELECT TO e9x_app, e9x_grader
  USING (owner_org_id IS NULL OR owner_org_id = e9x_current_org());
CREATE POLICY grading_policies_insert ON grading_policies FOR INSERT TO e9x_app WITH CHECK (
  e9x_workflow_in_context(owner_org_id) AND updated_by = e9x_current_user()
  AND (scope <> 'ORG' OR mode = 'AI' OR EXISTS (SELECT 1 FROM organizations o WHERE o.id = owner_org_id AND o.type = 'CENTER'))
  AND CASE scope
        WHEN 'EXAM' THEN e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['SYS_ADMIN'])
        WHEN 'ORG'  THEN e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['ORG_ADMIN'])
        ELSE e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
      END);
CREATE POLICY grading_policies_update ON grading_policies FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id)
         AND CASE scope
               WHEN 'EXAM' THEN e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['SYS_ADMIN'])
               WHEN 'ORG'  THEN e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['ORG_ADMIN'])
               ELSE e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
             END)
  WITH CHECK (updated_by = e9x_current_user()
              AND (scope <> 'ORG' OR mode = 'AI' OR EXISTS (SELECT 1 FROM organizations o WHERE o.id = owner_org_id AND o.type = 'CENTER')));
CREATE POLICY grading_policies_delete ON grading_policies FOR DELETE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id)
         AND CASE scope
               WHEN 'ORG'  THEN e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['ORG_ADMIN'])
               WHEN 'TEST' THEN e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
               ELSE false
             END);

-- ==== privileges =================================================================================================================
-- Headers are insert-only. A version's identity (parent, owner, exam, number, author) never changes; only its content and status do.
GRANT SELECT ON score_tables, score_table_versions, grading_policies TO e9x_app, e9x_grader;
GRANT SELECT ON grading_skills, grading_skill_versions TO e9x_app, e9x_grader;
GRANT INSERT (id, owner_org_id, exam_code, skill, scope, test_id, function) ON score_tables TO e9x_app;
GRANT INSERT (id, score_table_id, owner_org_id, exam_code, version_no, entries, created_by) ON score_table_versions TO e9x_app;
GRANT UPDATE (status, entries, updated_at) ON score_table_versions TO e9x_app;
GRANT DELETE ON score_table_versions TO e9x_app;
GRANT INSERT (id, owner_org_id, exam_code, question_type_code) ON grading_skills TO e9x_app;
GRANT INSERT (id, grading_skill_id, owner_org_id, exam_code, version_no, rubric, criteria, prompt_template, samples, result_schema, created_by)
  ON grading_skill_versions TO e9x_app;
GRANT UPDATE (status, rubric, criteria, prompt_template, samples, result_schema, updated_at) ON grading_skill_versions TO e9x_app;
GRANT DELETE ON grading_skill_versions TO e9x_app;
GRANT INSERT (id, owner_org_id, exam_code, scope, test_id, mode, updated_by) ON grading_policies TO e9x_app;
GRANT UPDATE (mode, updated_by, updated_at) ON grading_policies TO e9x_app;
GRANT DELETE ON grading_policies TO e9x_app;
