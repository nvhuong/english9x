-- P6.2 classes / groups and assignments (F45, F46).
--
--  * classes (V202611060001) and class_members (V202611140003) ALREADY exist. classes gets teacher_id and kind; class_members only gets its
--    write policies (INSERT / DELETE) so that the ORG_ADMIN of the organisation and the TEACHER of that class manage the roster. Its select
--    policy stays; the person's own rows are still readable and nothing else widens. A LEARNER can write nothing here.
--  * The old classes_all policy (FOR ALL, any member of the current organisation) let a LEARNER create and edit classes (final P1 review, M2).
--    It is dropped. Now: SELECT for the organisation's context (the class list), INSERT / UPDATE / DELETE for its ORG_ADMIN only.
--  * assignments are written by the people who manage the class; learners of the class read them. assignment_submissions has NO write grant
--    at all for the request role: the only writer is classroom_record_submission(), a definer function that first reads the real attempt back.
--    MISSING is never stored: it is "no submission and the due date has passed", worked out when it is read.
--  * Definer functions pin search_path. The listeners run on pool threads with no user bound, so what they call is granted to e9x_app and checks
--    its own facts (the real attempt, the real membership); the recurring job's read is granted to e9x_worker only.

-- ---- classes: teacher and kind -----------------------------------------------------------------------------------------------
ALTER TABLE classes ADD COLUMN teacher_id uuid;
ALTER TABLE classes ADD COLUMN kind text NOT NULL DEFAULT 'CLASS' CHECK (kind IN ('CLASS', 'GROUP'));
-- the teacher is a member of the organisation; when the membership goes, the class is left without a teacher (the class stays)
ALTER TABLE classes ADD FOREIGN KEY (owner_org_id, teacher_id) REFERENCES org_memberships (org_id, user_id) ON DELETE SET NULL (teacher_id);
CREATE INDEX classes_teacher_idx ON classes (teacher_id) WHERE teacher_id IS NOT NULL;

-- An enterprise calls its classes "groups" and has no teachers (spec 4); a centre's classes are classes and a teacher must hold TEACHER there.
CREATE FUNCTION classes_guard() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_type text;
BEGIN
  SELECT o.type INTO v_type FROM organizations o WHERE o.id = NEW.owner_org_id;
  IF v_type = 'ENTERPRISE' THEN
    NEW.kind := 'GROUP';   -- whatever the insert said (the column default is CLASS): an enterprise has groups only
    IF NEW.teacher_id IS NOT NULL THEN
      RAISE EXCEPTION 'an enterprise has groups without a teacher' USING ERRCODE = 'check_violation';
    END IF;
  ELSE
    IF NEW.kind <> 'CLASS' THEN
      RAISE EXCEPTION 'a centre has classes, not groups' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.teacher_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM role_grants g
         WHERE g.user_id = NEW.teacher_id AND g.org_id = NEW.owner_org_id AND g.role = 'TEACHER') THEN
      RAISE EXCEPTION 'the teacher of a class must hold TEACHER in the organisation' USING ERRCODE = 'check_violation';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER classes_guard BEFORE INSERT OR UPDATE ON classes FOR EACH ROW EXECUTE FUNCTION classes_guard();

-- ---- who manages / belongs to a class (definer: the policies below read tables that carry policies of their own) ----------------
CREATE FUNCTION classroom_manages(p_class uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT e9x_current_user() IS NOT NULL AND EXISTS (
    SELECT 1 FROM classes c
     WHERE c.id = p_class AND c.owner_org_id = e9x_current_org()
       AND (e9x_is_current_org_admin()
            OR (c.teacher_id = e9x_current_user()
                AND EXISTS (SELECT 1 FROM role_grants g WHERE g.user_id = c.teacher_id AND g.org_id = c.owner_org_id AND g.role = 'TEACHER')))) $$;

CREATE FUNCTION classroom_in_class(p_class uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT e9x_current_user() IS NOT NULL
     AND EXISTS (SELECT 1 FROM class_members m WHERE m.class_id = p_class AND m.user_id = e9x_current_user()) $$;

-- ---- classes policies ----------------------------------------------------------------------------------------------------------
DROP POLICY classes_all ON classes;
-- Reading: any member in the organisation's context sees the organisation's class list (names are not secret inside the organisation; this is what the
-- old policy allowed too). What is NOT open is the roster (class_members) and the assignments: those follow the class.
CREATE POLICY classes_select ON classes FOR SELECT USING (owner_org_id = e9x_current_org());
CREATE POLICY classes_insert ON classes FOR INSERT WITH CHECK (
  owner_org_id = e9x_current_org() AND e9x_is_current_org_admin());
CREATE POLICY classes_update ON classes FOR UPDATE
  USING (owner_org_id = e9x_current_org() AND e9x_is_current_org_admin())
  WITH CHECK (owner_org_id = e9x_current_org() AND e9x_is_current_org_admin());
CREATE POLICY classes_delete ON classes FOR DELETE USING (
  owner_org_id = e9x_current_org() AND e9x_is_current_org_admin());
-- only the name and the teacher change; the organisation and the kind are fixed at creation
REVOKE UPDATE ON classes FROM e9x_app;
GRANT UPDATE (name, teacher_id) ON classes TO e9x_app;

-- ---- class_members: the write side (P6.1 left it to this task) -------------------------------------------------------------------
CREATE POLICY class_members_select_manager ON class_members FOR SELECT USING (
  org_id = e9x_current_org() AND classroom_manages(class_id));
CREATE POLICY class_members_insert ON class_members FOR INSERT WITH CHECK (
  org_id = e9x_current_org() AND classroom_manages(class_id));
CREATE POLICY class_members_delete ON class_members FOR DELETE USING (
  org_id = e9x_current_org() AND classroom_manages(class_id));
GRANT INSERT, DELETE ON class_members TO e9x_app;

-- ---- assignments -------------------------------------------------------------------------------------------------------------------
CREATE TABLE assignments (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  class_id         uuid NOT NULL,
  org_id           uuid NOT NULL,
  lesson_id        uuid REFERENCES course_lessons (id),   -- a lesson, chapter test or mock test of a course version (done = lesson_progress)
  test_version_id  uuid REFERENCES test_versions (id),    -- a test taken as an exam attempt (done = a submitted attempt, AttemptSubmitted)
  due_at           timestamptz NOT NULL,
  created_by       uuid NOT NULL REFERENCES users (id),
  created_at       timestamptz NOT NULL DEFAULT now(),
  CHECK ((lesson_id IS NULL) <> (test_version_id IS NULL)),
  UNIQUE (id, org_id),
  FOREIGN KEY (class_id, org_id) REFERENCES classes (id, owner_org_id)
);
CREATE INDEX assignments_class_idx ON assignments (class_id, due_at);
CREATE INDEX assignments_test_idx ON assignments (test_version_id) WHERE test_version_id IS NOT NULL;
CREATE INDEX assignments_due_idx ON assignments (due_at);

-- Is the target something the organisation may hand out: a PUBLISHED lesson of a course visible to it / a PUBLISHED test of English9x or of its own.
CREATE FUNCTION classroom_target_ok(p_lesson uuid, p_test_version uuid, p_org uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT CASE
    WHEN p_lesson IS NOT NULL THEN EXISTS (
      SELECT 1 FROM course_lessons l JOIN course_versions v ON v.id = l.course_version_id
       WHERE l.id = p_lesson AND v.status = 'PUBLISHED'
         AND (v.owner_org_id IS NULL OR v.owner_org_id = p_org
              OR EXISTS (SELECT 1 FROM courses c WHERE c.id = v.course_id AND c.shared_status = 'APPROVED')))
    WHEN p_test_version IS NOT NULL THEN EXISTS (
      SELECT 1 FROM test_versions t WHERE t.id = p_test_version AND t.status = 'PUBLISHED' AND (t.owner_org_id IS NULL OR t.owner_org_id = p_org))
    ELSE false END $$;

CREATE FUNCTION assignments_guard() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  IF NOT classroom_target_ok(NEW.lesson_id, NEW.test_version_id, NEW.org_id) THEN
    RAISE EXCEPTION 'the assignment target is not published or not available to the organisation' USING ERRCODE = 'check_violation';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER assignments_guard BEFORE INSERT ON assignments FOR EACH ROW EXECUTE FUNCTION assignments_guard();

ALTER TABLE assignments ENABLE ROW LEVEL SECURITY;
CREATE POLICY assignments_select ON assignments FOR SELECT USING (
  org_id = e9x_current_org() AND (classroom_manages(class_id) OR classroom_in_class(class_id)));
CREATE POLICY assignments_insert ON assignments FOR INSERT WITH CHECK (
  org_id = e9x_current_org() AND classroom_manages(class_id) AND created_by = e9x_current_user());
CREATE POLICY assignments_update ON assignments FOR UPDATE
  USING (org_id = e9x_current_org() AND classroom_manages(class_id))
  WITH CHECK (org_id = e9x_current_org() AND classroom_manages(class_id));
REVOKE ALL ON assignments FROM PUBLIC;
GRANT SELECT, INSERT ON assignments TO e9x_app;
GRANT UPDATE (due_at) ON assignments TO e9x_app;

CREATE FUNCTION classroom_manages_assignment(p_assignment uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT EXISTS (SELECT 1 FROM assignments a WHERE a.id = p_assignment AND classroom_manages(a.class_id)) $$;

-- ---- submissions of test assignments -----------------------------------------------------------------------------------------------
CREATE TABLE assignment_submissions (
  assignment_id uuid NOT NULL,
  org_id        uuid NOT NULL,
  user_id       uuid NOT NULL REFERENCES users (id),
  status        text NOT NULL CHECK (status IN ('SUBMITTED', 'LATE')),
  submitted_at  timestamptz NOT NULL,
  attempt_id    uuid REFERENCES exam_attempts (id),
  PRIMARY KEY (assignment_id, user_id),
  FOREIGN KEY (assignment_id, org_id) REFERENCES assignments (id, org_id)
);
CREATE INDEX assignment_submissions_user_idx ON assignment_submissions (user_id);
ALTER TABLE assignment_submissions ENABLE ROW LEVEL SECURITY;
CREATE POLICY assignment_submissions_select ON assignment_submissions FOR SELECT USING (
  user_id = e9x_current_user()
  OR (org_id = e9x_current_org() AND classroom_manages_assignment(assignment_id)));
REVOKE ALL ON assignment_submissions FROM PUBLIC;
GRANT SELECT ON assignment_submissions TO e9x_app;   -- no write grant: classroom_record_submission is the only writer

-- ---- status ----------------------------------------------------------------------------------------------------------------------------
CREATE FUNCTION classroom_status(p_due timestamptz, p_submitted timestamptz, p_now timestamptz) RETURNS text
  LANGUAGE sql IMMUTABLE SET search_path = pg_catalog, pg_temp AS $$
  SELECT CASE WHEN p_submitted IS NOT NULL THEN CASE WHEN p_submitted > p_due THEN 'LATE' ELSE 'SUBMITTED' END
              WHEN p_now > p_due THEN 'MISSING' ELSE 'PENDING' END $$;

-- When did the person hand this assignment in (NULL: not yet)? A test: the stored submission; a lesson: its completion in lesson_progress.
CREATE FUNCTION classroom_submitted_at(p_assignment uuid, p_user uuid) RETURNS timestamptz
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT CASE WHEN a.test_version_id IS NOT NULL
    THEN (SELECT s.submitted_at FROM assignment_submissions s WHERE s.assignment_id = a.id AND s.user_id = p_user)
    ELSE (SELECT p.completed_at FROM course_lessons l JOIN course_versions v ON v.id = l.course_version_id
            JOIN lesson_progress p ON p.course_id = v.course_id AND p.ref_id = l.ref_id AND p.user_id = p_user AND p.completed
           WHERE l.id = a.lesson_id ORDER BY p.completed_at LIMIT 1) END
    FROM assignments a WHERE a.id = p_assignment $$;
REVOKE ALL ON FUNCTION classroom_submitted_at(uuid, uuid) FROM PUBLIC;

-- ---- reads for the people involved ----------------------------------------------------------------------------------------------------
-- The learner's own assignments in the current organisation, each with its status and what it points at.
CREATE FUNCTION classroom_my_assignments(p_now timestamptz)
  RETURNS TABLE (r_id uuid, r_class_id uuid, r_class_name text, r_lesson_id uuid, r_course_id uuid, r_test_version_id uuid,
                 r_title text, r_due_at timestamptz, r_status text, r_submitted_at timestamptz)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT a.id, a.class_id, c.name, a.lesson_id,
         (SELECT v.course_id FROM course_lessons l JOIN course_versions v ON v.id = l.course_version_id WHERE l.id = a.lesson_id),
         a.test_version_id,
         COALESCE((SELECT l.title FROM course_lessons l WHERE l.id = a.lesson_id), (SELECT t.title FROM test_versions t WHERE t.id = a.test_version_id)),
         a.due_at, classroom_status(a.due_at, s.at, p_now), s.at
    FROM assignments a
    JOIN classes c ON c.id = a.class_id
    JOIN class_members m ON m.class_id = a.class_id AND m.user_id = e9x_current_user()
    CROSS JOIN LATERAL (SELECT classroom_submitted_at(a.id, e9x_current_user()) AS at) s
   WHERE e9x_current_user() IS NOT NULL AND a.org_id = e9x_current_org()
   ORDER BY a.due_at, a.id $$;

-- The roster of a class with names (the e-mail only for the ORG_ADMIN: a teacher does not read other people's addresses).
CREATE FUNCTION classroom_class_members(p_class uuid)
  RETURNS TABLE (r_user_id uuid, r_display_name text, r_email text, r_added_at timestamptz)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT m.user_id, u.display_name, CASE WHEN e9x_is_current_org_admin() THEN u.email END, m.added_at
    FROM class_members m JOIN users u ON u.id = m.user_id
   WHERE m.class_id = p_class AND classroom_manages(p_class)
   ORDER BY lower(coalesce(u.display_name, u.email)), m.user_id $$;

-- The assignments of a class for the people who manage it: what they point at and how many members are in each state.
CREATE FUNCTION classroom_class_assignments(p_class uuid, p_now timestamptz)
  RETURNS TABLE (r_id uuid, r_lesson_id uuid, r_test_version_id uuid, r_title text, r_due_at timestamptz, r_created_at timestamptz,
                 r_total integer, r_submitted integer, r_late integer, r_missing integer)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT a.id, a.lesson_id, a.test_version_id,
         COALESCE((SELECT l.title FROM course_lessons l WHERE l.id = a.lesson_id), (SELECT t.title FROM test_versions t WHERE t.id = a.test_version_id)),
         a.due_at, a.created_at,
         count(m.user_id)::int,
         (count(*) FILTER (WHERE m.user_id IS NOT NULL AND x.st = 'SUBMITTED'))::int,
         (count(*) FILTER (WHERE m.user_id IS NOT NULL AND x.st = 'LATE'))::int,
         (count(*) FILTER (WHERE m.user_id IS NOT NULL AND x.st = 'MISSING'))::int
    FROM assignments a
    LEFT JOIN class_members m ON m.class_id = a.class_id
    CROSS JOIN LATERAL (SELECT classroom_status(a.due_at, classroom_submitted_at(a.id, m.user_id), p_now) AS st) x
   WHERE a.class_id = p_class AND a.org_id = e9x_current_org() AND classroom_manages(p_class)
   GROUP BY a.id
   ORDER BY a.due_at DESC, a.id $$;

-- Who did what for one assignment, for the people who manage the class.
CREATE FUNCTION classroom_assignment_report(p_assignment uuid, p_now timestamptz)
  RETURNS TABLE (r_user_id uuid, r_display_name text, r_email text, r_status text, r_submitted_at timestamptz)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT m.user_id, u.display_name, CASE WHEN e9x_is_current_org_admin() THEN u.email END,
         classroom_status(a.due_at, s.at, p_now), s.at
    FROM assignments a
    JOIN class_members m ON m.class_id = a.class_id
    JOIN users u ON u.id = m.user_id
    CROSS JOIN LATERAL (SELECT classroom_submitted_at(a.id, m.user_id) AS at) s
   WHERE a.id = p_assignment AND a.org_id = e9x_current_org() AND classroom_manages(a.class_id)
   ORDER BY lower(coalesce(u.display_name, u.email)), m.user_id $$;

-- ---- writers (called by the listeners, which run without a user) and the worker's read -------------------------------------------------------------------------------------------------------------
-- AttemptSubmitted is a hint (the request role can forge an event): the attempt is read back and must be a real submitted attempt of that person,
-- of that test version, made in the organisation's context; the submission time is the ROW's. Only the first submission counts. An attempt made
-- before the assignment existed counts for nothing. Returns how many assignments it marked.
CREATE FUNCTION classroom_record_submission(p_attempt uuid, p_user uuid, p_test_version uuid) RETURNS integer
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  att exam_attempts%ROWTYPE;
  n integer;
BEGIN
  SELECT * INTO att FROM exam_attempts x
   WHERE x.id = p_attempt AND x.user_id = p_user AND x.test_version_id = p_test_version
     AND x.status = 'SUBMITTED' AND x.submitted_at IS NOT NULL AND x.owner_org_id IS NOT NULL;
  IF NOT FOUND THEN
    RETURN 0;
  END IF;
  INSERT INTO assignment_submissions(assignment_id, org_id, user_id, status, submitted_at, attempt_id)
  SELECT a.id, a.org_id, att.user_id, CASE WHEN att.submitted_at > a.due_at THEN 'LATE' ELSE 'SUBMITTED' END, att.submitted_at, att.id
    FROM assignments a JOIN class_members m ON m.class_id = a.class_id AND m.user_id = att.user_id
   WHERE a.test_version_id = att.test_version_id AND a.org_id = att.owner_org_id AND att.submitted_at >= a.created_at
  ON CONFLICT (assignment_id, user_id) DO NOTHING;
  GET DIAGNOSTICS n = ROW_COUNT;
  RETURN n;
END $$;

-- ASSIGNMENT_NEW goes to the learners the service named in the event; the listener asks, one person at a time, whether that person really is in the
-- class of that assignment (an event can be forged by the request role). A yes/no for a pair: nobody can list a class through it.
CREATE FUNCTION classroom_is_recipient(p_assignment uuid, p_user uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT EXISTS (SELECT 1 FROM assignments a JOIN class_members m ON m.class_id = a.class_id WHERE a.id = p_assignment AND m.user_id = p_user) $$;

-- Learners whose assignment falls due inside (p_after, p_until] and who have not handed it in. The job tells each once (dedupe key per assignment).
CREATE FUNCTION classroom_due_soon(p_after timestamptz, p_until timestamptz)
  RETURNS TABLE (r_assignment_id uuid, r_user_id uuid, r_due_at timestamptz)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT a.id, m.user_id, a.due_at
    FROM assignments a JOIN class_members m ON m.class_id = a.class_id
   WHERE a.due_at > p_after AND a.due_at <= p_until AND classroom_submitted_at(a.id, m.user_id) IS NULL
   ORDER BY a.due_at, a.id, m.user_id $$;

REVOKE ALL ON FUNCTION classes_guard(), assignments_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION classroom_manages(uuid), classroom_in_class(uuid), classroom_manages_assignment(uuid), classroom_target_ok(uuid, uuid, uuid),
  classroom_status(timestamptz, timestamptz, timestamptz), classroom_my_assignments(timestamptz), classroom_class_members(uuid),
  classroom_assignment_report(uuid, timestamptz), classroom_class_assignments(uuid, timestamptz), classroom_record_submission(uuid, uuid, uuid), classroom_is_recipient(uuid, uuid),
  classroom_due_soon(timestamptz, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION classroom_manages(uuid), classroom_in_class(uuid), classroom_manages_assignment(uuid), classroom_target_ok(uuid, uuid, uuid),
  classroom_status(timestamptz, timestamptz, timestamptz), classroom_my_assignments(timestamptz), classroom_class_members(uuid),
  classroom_assignment_report(uuid, timestamptz), classroom_class_assignments(uuid, timestamptz), classroom_record_submission(uuid, uuid, uuid),
  classroom_is_recipient(uuid, uuid) TO e9x_app;
GRANT EXECUTE ON FUNCTION classroom_due_soon(timestamptz, timestamptz) TO e9x_worker;
