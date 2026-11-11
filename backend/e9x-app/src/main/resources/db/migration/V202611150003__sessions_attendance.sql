-- P6.3 class sessions (schedule) and attendance (F47, F48).
--
--  * class_sessions: a meeting of a class, OFFLINE (a room) or ONLINE (an https link, any domain). ORG_ADMIN and the class's TEACHER write; the members of the
--    class read. A LEARNER writes nothing. attendance: one row per (session, member); written by the people who manage the class; a member reads
--    only their OWN row, the managers read them all. Nobody outside the organisation, nor a member of another class, sees any of it.
--  * Attendance is only ever recorded for a CURRENT member of the class of the session (guard trigger), and marked_by / marked_at are the caller's and the
--    clock's, never the request's.
--  * The attendance rate of a learner is worked out when read (classroom_attendance_report): (PRESENT + LATE) / (PRESENT + LATE + ABSENT); an EXCUSED
--    session leaves the learner's denominator (it is neither held against them nor counted for them); no marked session -> no rate (NULL).
--  * Definer functions pin search_path, REVOKE PUBLIC and are granted to e9x_app; the reminder job's read is granted to e9x_worker only.

CREATE TABLE class_sessions (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  class_id    uuid NOT NULL,
  org_id      uuid NOT NULL,
  mode        text NOT NULL CHECK (mode IN ('OFFLINE', 'ONLINE')),
  starts_at   timestamptz NOT NULL,
  ends_at     timestamptz NOT NULL,
  room        text CHECK (room IS NULL OR (length(room) BETWEEN 1 AND 100)),
  meeting_url text CHECK (meeting_url IS NULL OR length(meeting_url) <= 500),
  created_by  uuid NOT NULL REFERENCES users (id),
  created_at  timestamptz NOT NULL DEFAULT now(),
  CHECK (ends_at > starts_at),
  -- an online session NEEDS an https link; an offline one has none
  CHECK ((mode = 'ONLINE' AND COALESCE(meeting_url ~ '^https://[^[:space:]/]+[^[:space:]]*$', false)) OR (mode = 'OFFLINE' AND meeting_url IS NULL)),
  UNIQUE (id, org_id),
  FOREIGN KEY (class_id, org_id) REFERENCES classes (id, owner_org_id)
);
CREATE INDEX class_sessions_class_idx ON class_sessions (class_id, starts_at);
CREATE INDEX class_sessions_start_idx ON class_sessions (starts_at);

CREATE FUNCTION classroom_manages_session(p_session uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT EXISTS (SELECT 1 FROM class_sessions s WHERE s.id = p_session AND classroom_manages(s.class_id)) $$;

ALTER TABLE class_sessions ENABLE ROW LEVEL SECURITY;
CREATE POLICY class_sessions_select ON class_sessions FOR SELECT USING (
  org_id = e9x_current_org() AND (classroom_manages(class_id) OR classroom_in_class(class_id)));
CREATE POLICY class_sessions_insert ON class_sessions FOR INSERT WITH CHECK (
  org_id = e9x_current_org() AND classroom_manages(class_id) AND created_by = e9x_current_user());
REVOKE ALL ON class_sessions FROM PUBLIC;
GRANT SELECT, INSERT ON class_sessions TO e9x_app;

CREATE TABLE attendance (
  session_id uuid NOT NULL,
  org_id     uuid NOT NULL,
  user_id    uuid NOT NULL REFERENCES users (id),
  status     text NOT NULL CHECK (status IN ('PRESENT', 'ABSENT', 'LATE', 'EXCUSED')),
  note       text CHECK (note IS NULL OR length(note) <= 500),
  marked_by  uuid NOT NULL REFERENCES users (id),
  marked_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (session_id, user_id),
  FOREIGN KEY (session_id, org_id) REFERENCES class_sessions (id, org_id)
);
CREATE INDEX attendance_user_idx ON attendance (user_id);

-- Only a current member of the class of the session can be marked; marked_at is the clock's.
CREATE FUNCTION attendance_guard() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  -- (this trigger fires before the row-level policy: say nothing about the session to a signed-in person who may not manage it; with nobody bound the
  -- policy refuses anyway, and a trusted writer such as the owner's seed or restore script is let through to the membership rule below)
  IF e9x_current_user() IS NOT NULL AND NOT classroom_manages_session(NEW.session_id) THEN
    RAISE EXCEPTION 'attendance is written by the people who manage the class' USING ERRCODE = 'insufficient_privilege';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM class_sessions s JOIN class_members m ON m.class_id = s.class_id
                  WHERE s.id = NEW.session_id AND m.user_id = NEW.user_id) THEN
    RAISE EXCEPTION 'attendance is recorded for members of the class only' USING ERRCODE = 'check_violation';
  END IF;
  NEW.marked_at := now();
  RETURN NEW;
END $$;
CREATE TRIGGER attendance_guard BEFORE INSERT OR UPDATE ON attendance FOR EACH ROW EXECUTE FUNCTION attendance_guard();

ALTER TABLE attendance ENABLE ROW LEVEL SECURITY;
CREATE POLICY attendance_select ON attendance FOR SELECT USING (
  org_id = e9x_current_org() AND (classroom_manages_session(session_id) OR user_id = e9x_current_user()));
CREATE POLICY attendance_insert ON attendance FOR INSERT WITH CHECK (
  org_id = e9x_current_org() AND classroom_manages_session(session_id) AND marked_by = e9x_current_user());
CREATE POLICY attendance_update ON attendance FOR UPDATE
  USING (org_id = e9x_current_org() AND classroom_manages_session(session_id))
  WITH CHECK (org_id = e9x_current_org() AND classroom_manages_session(session_id) AND marked_by = e9x_current_user());
REVOKE ALL ON attendance FROM PUBLIC;
GRANT SELECT, INSERT ON attendance TO e9x_app;
GRANT UPDATE (status, note, marked_by, marked_at) ON attendance TO e9x_app;

-- ---- reads ------------------------------------------------------------------------------------------------------------------------------------
-- The sessions of the current organisation the caller takes part in, as a learner of the class or as its teacher, starting in [p_from, p_to).
CREATE FUNCTION classroom_my_schedule(p_from timestamptz, p_to timestamptz)
  RETURNS TABLE (r_id uuid, r_class_id uuid, r_class_name text, r_mode text, r_starts_at timestamptz, r_ends_at timestamptz, r_room text,
                 r_meeting_url text, r_role text, r_attendance text)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT s.id, s.class_id, c.name, s.mode, s.starts_at, s.ends_at, s.room, s.meeting_url,
         CASE WHEN c.teacher_id = e9x_current_user() THEN 'TEACHER' ELSE 'LEARNER' END,
         (SELECT a.status FROM attendance a WHERE a.session_id = s.id AND a.user_id = e9x_current_user())
    FROM class_sessions s JOIN classes c ON c.id = s.class_id
   WHERE e9x_current_user() IS NOT NULL AND s.org_id = e9x_current_org()
     AND s.starts_at >= p_from AND s.starts_at < p_to
     AND (EXISTS (SELECT 1 FROM class_members m WHERE m.class_id = s.class_id AND m.user_id = e9x_current_user())
          OR (c.teacher_id = e9x_current_user()
              AND EXISTS (SELECT 1 FROM role_grants g WHERE g.user_id = c.teacher_id AND g.org_id = c.owner_org_id AND g.role = 'TEACHER')))
   ORDER BY s.starts_at, s.id $$;

-- The members of the class of a session with what was recorded for each (status NULL: not marked yet), for the people who manage the class.
CREATE FUNCTION classroom_session_attendance(p_session uuid)
  RETURNS TABLE (r_user_id uuid, r_display_name text, r_email text, r_status text, r_note text, r_marked_at timestamptz)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT m.user_id, u.display_name, CASE WHEN e9x_is_current_org_admin() THEN u.email END, a.status, a.note, a.marked_at
    FROM class_sessions s
    JOIN class_members m ON m.class_id = s.class_id
    JOIN users u ON u.id = m.user_id
    LEFT JOIN attendance a ON a.session_id = s.id AND a.user_id = m.user_id
   WHERE s.id = p_session AND s.org_id = e9x_current_org() AND classroom_manages(s.class_id)
   ORDER BY lower(coalesce(u.display_name, u.email)), m.user_id $$;

-- The attendance of every member of a class over all its marked sessions, for the people who manage the class.
CREATE FUNCTION classroom_attendance_report(p_class uuid)
  RETURNS TABLE (r_user_id uuid, r_display_name text, r_email text, r_present integer, r_late integer, r_absent integer, r_excused integer, r_rate double precision)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT x.user_id, x.display_name, x.email, x.present, x.late, x.absent, x.excused,
         CASE WHEN x.present + x.late + x.absent = 0 THEN NULL ELSE (x.present + x.late)::double precision / (x.present + x.late + x.absent) END
    FROM (
      SELECT m.user_id, u.display_name, CASE WHEN e9x_is_current_org_admin() THEN u.email END AS email,
             (count(*) FILTER (WHERE a.status = 'PRESENT'))::int AS present,
             (count(*) FILTER (WHERE a.status = 'LATE'))::int AS late,
             (count(*) FILTER (WHERE a.status = 'ABSENT'))::int AS absent,
             (count(*) FILTER (WHERE a.status = 'EXCUSED'))::int AS excused
        FROM class_members m
        JOIN users u ON u.id = m.user_id
        LEFT JOIN class_sessions s ON s.class_id = m.class_id
        LEFT JOIN attendance a ON a.session_id = s.id AND a.user_id = m.user_id
       WHERE m.class_id = p_class AND m.org_id = e9x_current_org() AND classroom_manages(p_class)
       GROUP BY m.user_id, u.display_name, u.email) x
   ORDER BY lower(coalesce(x.display_name, x.email)), x.user_id $$;

-- Who is told that a session starts soon: its learners and its teacher, for sessions starting in (p_after, p_until]. The job tells each once per session.
CREATE FUNCTION classroom_sessions_to_remind(p_after timestamptz, p_until timestamptz)
  RETURNS TABLE (r_session_id uuid, r_user_id uuid, r_starts_at timestamptz)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT s.id, p.user_id, s.starts_at
    FROM class_sessions s
    JOIN classes c ON c.id = s.class_id
    CROSS JOIN LATERAL (SELECT m.user_id FROM class_members m WHERE m.class_id = s.class_id
                        UNION
                        SELECT c.teacher_id WHERE c.teacher_id IS NOT NULL) p
   WHERE s.starts_at > p_after AND s.starts_at <= p_until
   ORDER BY s.starts_at, s.id, p.user_id $$;

REVOKE ALL ON FUNCTION attendance_guard() FROM PUBLIC;
REVOKE ALL ON FUNCTION classroom_manages_session(uuid), classroom_my_schedule(timestamptz, timestamptz), classroom_session_attendance(uuid),
  classroom_attendance_report(uuid), classroom_sessions_to_remind(timestamptz, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION classroom_manages_session(uuid), classroom_my_schedule(timestamptz, timestamptz), classroom_session_attendance(uuid),
  classroom_attendance_report(uuid) TO e9x_app;
GRANT EXECUTE ON FUNCTION classroom_sessions_to_remind(timestamptz, timestamptz) TO e9x_worker;
