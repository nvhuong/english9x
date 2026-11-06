-- English9x baseline: multi-tenant org schema with Row-Level Security.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'e9x_app') THEN
    CREATE ROLE e9x_app NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'e9x_grader') THEN
    CREATE ROLE e9x_grader NOLOGIN;
  END IF;
END $$;

CREATE FUNCTION e9x_current_org() RETURNS uuid
  LANGUAGE sql STABLE AS $$ SELECT nullif(current_setting('app.org_id', true), '')::uuid $$;
CREATE FUNCTION e9x_current_user() RETURNS uuid
  LANGUAGE sql STABLE AS $$ SELECT nullif(current_setting('app.user_id', true), '')::uuid $$;

CREATE TABLE users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email text NOT NULL,
  display_name text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email_lower_uq ON users (lower(email));

CREATE TABLE organizations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL,
  type text NOT NULL CHECK (type IN ('CENTER','ENTERPRISE')),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE org_memberships (
  org_id uuid NOT NULL REFERENCES organizations(id),
  user_id uuid NOT NULL REFERENCES users(id),
  PRIMARY KEY (org_id, user_id)
);

CREATE TABLE role_grants (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id),
  org_id uuid REFERENCES organizations(id),
  exam_code text,
  role text NOT NULL CHECK (role IN ('LEARNER','AUTHOR','REVIEWER','TEACHER','ORG_ADMIN','SYS_ADMIN')),
  CONSTRAINT role_grants_scope_uq UNIQUE NULLS NOT DISTINCT (user_id, org_id, exam_code, role)
);

CREATE TABLE exams (
  code text PRIMARY KEY,
  name text NOT NULL
);

CREATE TABLE courses (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  code char(6) NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9]{6}$'),
  title text NOT NULL,
  status text NOT NULL CHECK (status IN ('DRAFT','IN_REVIEW','CHANGES_REQUESTED','PUBLISHED','ARCHIVED')),
  shared_status text NOT NULL DEFAULT 'NONE' CHECK (shared_status IN ('NONE','PENDING','APPROVED'))
);

CREATE TABLE questions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  content jsonb NOT NULL,
  answer_key jsonb NOT NULL,
  status text NOT NULL
);

CREATE TABLE entitlements (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id),
  course_id uuid NOT NULL REFERENCES courses(id),
  valid_from timestamptz NOT NULL,
  valid_until timestamptz NOT NULL,
  source_order_id uuid,
  source_seat_id uuid,
  CONSTRAINT entitlements_period_ck CHECK (valid_until > valid_from),
  CONSTRAINT entitlements_source_ck CHECK (num_nonnulls(source_order_id, source_seat_id) = 1)
);

CREATE TABLE exam_attempts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id),
  owner_org_id uuid REFERENCES organizations(id),
  test_version_id uuid NOT NULL,
  status text NOT NULL CHECK (status IN ('IN_PROGRESS','SUBMITTED')),
  started_at timestamptz NOT NULL DEFAULT now(),
  submitted_at timestamptz
);
CREATE UNIQUE INDEX exam_attempts_one_in_progress_uq
  ON exam_attempts (user_id, test_version_id) WHERE status = 'IN_PROGRESS';

CREATE TABLE classes (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_org_id uuid NOT NULL REFERENCES organizations(id),
  name text NOT NULL
);

-- Row-Level Security
ALTER TABLE organizations  ENABLE ROW LEVEL SECURITY;
ALTER TABLE courses        ENABLE ROW LEVEL SECURITY;
ALTER TABLE questions      ENABLE ROW LEVEL SECURITY;
ALTER TABLE entitlements   ENABLE ROW LEVEL SECURITY;
ALTER TABLE exam_attempts  ENABLE ROW LEVEL SECURITY;
ALTER TABLE classes        ENABLE ROW LEVEL SECURITY;

CREATE POLICY organizations_select ON organizations FOR SELECT USING (
  id = e9x_current_org()
  OR EXISTS (SELECT 1 FROM org_memberships m
             WHERE m.org_id = organizations.id AND m.user_id = e9x_current_user()));

CREATE POLICY courses_select ON courses FOR SELECT USING (
  (owner_org_id IS NULL AND status = 'PUBLISHED')
  OR owner_org_id = e9x_current_org()
  OR (shared_status = 'APPROVED' AND status = 'PUBLISHED'));
CREATE POLICY courses_insert ON courses FOR INSERT
  WITH CHECK (owner_org_id IS NOT DISTINCT FROM e9x_current_org());
CREATE POLICY courses_update ON courses FOR UPDATE
  USING (owner_org_id IS NOT DISTINCT FROM e9x_current_org())
  WITH CHECK (owner_org_id IS NOT DISTINCT FROM e9x_current_org());
CREATE POLICY courses_delete ON courses FOR DELETE
  USING (owner_org_id IS NOT DISTINCT FROM e9x_current_org());

CREATE POLICY questions_all ON questions FOR ALL
  USING (owner_org_id IS NOT DISTINCT FROM e9x_current_org())
  WITH CHECK (owner_org_id IS NOT DISTINCT FROM e9x_current_org());

CREATE POLICY classes_all ON classes FOR ALL
  USING (owner_org_id = e9x_current_org())
  WITH CHECK (owner_org_id = e9x_current_org());

CREATE POLICY exam_attempts_select ON exam_attempts FOR SELECT USING (
  user_id = e9x_current_user()
  OR (owner_org_id IS NOT NULL AND owner_org_id = e9x_current_org()));
CREATE POLICY exam_attempts_insert ON exam_attempts FOR INSERT
  WITH CHECK (user_id = e9x_current_user());
CREATE POLICY exam_attempts_update ON exam_attempts FOR UPDATE
  USING (user_id = e9x_current_user())
  WITH CHECK (user_id = e9x_current_user());

CREATE POLICY entitlements_select ON entitlements FOR SELECT
  USING (user_id = e9x_current_user());

-- Privileges
GRANT SELECT, INSERT, UPDATE, DELETE ON
  users, organizations, org_memberships, role_grants, exams, courses,
  entitlements, exam_attempts, classes TO e9x_app;
-- questions: no table-level SELECT (would defeat the column restriction on answer_key)
GRANT SELECT (id, owner_org_id, exam_code, content, status) ON questions TO e9x_app;
GRANT INSERT, UPDATE, DELETE ON questions TO e9x_app;
GRANT SELECT ON questions TO e9x_grader;
GRANT EXECUTE ON FUNCTION e9x_current_org(), e9x_current_user() TO e9x_app, e9x_grader;
