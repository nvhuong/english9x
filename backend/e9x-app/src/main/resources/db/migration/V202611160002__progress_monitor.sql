-- P4.5 progress by plan (F24) and AI monitoring (F56). No table: everything is computed from the rows of the modules (entitlements, lesson_progress, attempts, scores,
-- assignments, study time, ai_evaluations, teacher_reviews, jobs). What the request role cannot read by policy (another person's rows for an organisation's teacher,
-- the whole platform's cost and jobs for a system administrator) is reached ONLY through the SECURITY DEFINER functions below, each of which re-checks WHO asks in SQL
-- and answers nothing otherwise. EXECUTE: e9x_app only.

-- ---- who may read a person's progress ---------------------------------------------------------------------------------------------------------------------------
-- p_org NULL: the bound person reads their own. p_org given: the bound person is, in the organisation of the session context (= p_org), its ORG_ADMIN or one of its
-- TEACHERs, and p_user is a member of that organisation. Anything else is false.
CREATE FUNCTION progress_viewable(p_user uuid, p_org uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT e9x_current_user() IS NOT NULL AND p_user IS NOT NULL AND (
    (p_org IS NULL AND p_user = e9x_current_user())
    OR (p_org IS NOT NULL AND p_org = e9x_current_org() AND e9x_org_has_member(p_org, p_user)
        AND ((e9x_is_org_admin() AND e9x_has_role('ORG_ADMIN', p_org))
             OR EXISTS (SELECT 1 FROM role_grants g WHERE g.user_id = e9x_current_user() AND g.org_id = p_org AND g.role = 'TEACHER')))) $$;

-- ---- the plans: one row per entitlement, with the plan or licence it came from --------------------------------------------------------------------------------
-- For an organisation's reader only the SEAT entitlements issued by that organisation.
CREATE FUNCTION progress_plans(p_user uuid, p_org uuid)
  RETURNS TABLE (entitlement_id uuid, course_id uuid, course_code text, course_title text, valid_from timestamptz, valid_until timestamptz,
                 source text, group_id uuid, plan_name text, plan_max integer, org_name text)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT e.id, e.course_id, btrim(c.code), c.title, e.valid_from, e.valid_until,
         CASE WHEN e.source_order_id IS NOT NULL THEN 'ORDER' ELSE 'SEAT' END,
         coalesce(e.source_order_id, e.source_seat_id),
         pl.name,
         CASE WHEN e.source_order_id IS NOT NULL THEN pl.max_courses
              ELSE (SELECT count(*)::int FROM org_license_courses lc WHERE lc.license_id = sa.license_id) END,
         og.name
    FROM entitlements e
    JOIN courses c ON c.id = e.course_id
    LEFT JOIN orders od ON od.id = e.source_order_id
    LEFT JOIN plans pl ON pl.id = od.plan_id
    LEFT JOIN seat_assignments sa ON sa.id = e.source_seat_id
    LEFT JOIN organizations og ON og.id = e.source_org_id AND e.source_seat_id IS NOT NULL
   WHERE progress_viewable(p_user, p_org) AND e.user_id = p_user
     AND (p_org IS NULL OR (e.source_seat_id IS NOT NULL AND e.source_org_id = p_org))
   ORDER BY e.valid_until DESC, e.id $$;

-- ---- the course figures: against the latest PUBLISHED version of each course the person has (or had) an entitlement to ------------------------------------------
-- Progress is keyed by (person, course, ref_id), so a plan bought again finds the same figures: nothing here depends on the entitlement.
CREATE FUNCTION progress_courses(p_user uuid, p_org uuid)
  RETURNS TABLE (course_id uuid, lessons_total integer, lessons_done integer, tests_total integer, tests_done integer, chapters_total integer, chapters_done integer,
                 resume_lesson_id uuid, last_activity timestamptz)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  WITH cs AS (
    SELECT DISTINCT e.course_id FROM entitlements e
     WHERE progress_viewable(p_user, p_org) AND e.user_id = p_user
       AND (p_org IS NULL OR (e.source_seat_id IS NOT NULL AND e.source_org_id = p_org))),
  ver AS (
    SELECT DISTINCT ON (v.course_id) v.id, v.course_id FROM course_versions v JOIN cs ON cs.course_id = v.course_id
     WHERE v.status = 'PUBLISHED' ORDER BY v.course_id, v.version_no DESC),
  ls AS (
    SELECT ver.course_id, l.id, l.kind, l.chapter_position, l.position, coalesce(p.completed, false) AS done, p.completed_at
      FROM ver JOIN course_lessons l ON l.course_version_id = ver.id
      LEFT JOIN lesson_progress p ON p.user_id = p_user AND p.course_id = ver.course_id AND p.ref_id = l.ref_id),
  ch AS (SELECT course_id, chapter_position, bool_and(done) AS all_done FROM ls GROUP BY course_id, chapter_position)
  SELECT cs.course_id,
         count(ls.id) FILTER (WHERE ls.kind = 'LESSON')::int,
         count(ls.id) FILTER (WHERE ls.kind = 'LESSON' AND ls.done)::int,
         count(ls.id) FILTER (WHERE ls.kind <> 'LESSON')::int,
         count(ls.id) FILTER (WHERE ls.kind <> 'LESSON' AND ls.done)::int,
         (SELECT count(*)::int FROM ch WHERE ch.course_id = cs.course_id),
         (SELECT count(*)::int FROM ch WHERE ch.course_id = cs.course_id AND ch.all_done),
         (array_agg(ls.id ORDER BY ls.chapter_position, ls.position) FILTER (WHERE NOT ls.done))[1],
         max(ls.completed_at)
    FROM cs LEFT JOIN ls ON ls.course_id = cs.course_id
   GROUP BY cs.course_id $$;

-- ---- the exams sat: the submitted attempts, with every stored score row (the caller picks the displayed one with DisplayedScores) --------------------------------------
CREATE FUNCTION progress_attempts(p_user uuid, p_org uuid)
  RETURNS TABLE (attempt_id uuid, course_id uuid, exam_code text, submitted_at timestamptz, skill text, kind text, source text, scaled numeric)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT a.id, a.course_id, a.exam_code, a.submitted_at, s.skill, s.kind, s.source, s.scaled
    FROM exam_attempts a LEFT JOIN scores s ON s.attempt_id = a.id AND s.scaled IS NOT NULL
   WHERE progress_viewable(p_user, p_org) AND a.user_id = p_user AND a.status = 'SUBMITTED' AND a.submitted_at IS NOT NULL
     AND (p_org IS NULL OR a.owner_org_id = p_org)
   ORDER BY a.submitted_at, a.id $$;

-- ---- the assignments not handed in (before or after the due date) ----------------------------------------------------------------------------------------------------
CREATE FUNCTION progress_pending(p_user uuid, p_org uuid)
  RETURNS TABLE (assignment_id uuid, class_name text, due_at timestamptz, course_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT a.id, k.name, a.due_at,
         CASE WHEN a.lesson_id IS NOT NULL
              THEN (SELECT v.course_id FROM course_lessons l JOIN course_versions v ON v.id = l.course_version_id WHERE l.id = a.lesson_id)
              ELSE (SELECT v.course_id FROM course_lessons l JOIN course_versions v ON v.id = l.course_version_id
                     WHERE l.ref_id = a.test_version_id AND l.kind <> 'LESSON'
                       AND EXISTS (SELECT 1 FROM entitlements e WHERE e.user_id = p_user AND e.course_id = v.course_id)
                     ORDER BY v.version_no DESC LIMIT 1) END
    FROM assignments a
    JOIN class_members m ON m.class_id = a.class_id AND m.user_id = p_user
    JOIN classes k ON k.id = a.class_id
   WHERE progress_viewable(p_user, p_org) AND (p_org IS NULL OR a.org_id = p_org)
     AND classroom_submitted_at(a.id, p_user) IS NULL
   ORDER BY a.due_at, a.id $$;

-- ---- study time and streak --------------------------------------------------------------------------------------------------------------------------------------------
CREATE FUNCTION progress_study(p_user uuid, p_org uuid, p_from date)
  RETURNS TABLE (day date, seconds integer)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT d.day, d.seconds FROM study_time_daily d
   WHERE progress_viewable(p_user, p_org) AND d.user_id = p_user AND d.day >= p_from ORDER BY d.day $$;

CREATE FUNCTION progress_streak(p_user uuid, p_org uuid)
  RETURNS TABLE (current_days integer, longest_days integer, last_day date, time_zone text)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT s.current_days, s.longest_days, s.last_day, s.time_zone FROM study_streaks s
   WHERE progress_viewable(p_user, p_org) AND s.user_id = p_user $$;

-- ---- the AI monitor: SYS_ADMIN only (the English9x context and a SYS_ADMIN grant, two independent facts) ----------------------------------------------------------
-- A day is a day of Vietnam (the product's calendar).
CREATE FUNCTION monitor_ai_cost(p_from timestamptz, p_to timestamptz)
  RETURNS TABLE (day date, user_id uuid, email text, cost_vnd numeric, input_tokens bigint, output_tokens bigint, evaluations integer)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT (e.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS d, e.user_id, u.email, sum(e.cost_vnd), sum(e.input_tokens)::bigint, sum(e.output_tokens)::bigint, count(*)::int
    FROM ai_evaluations e JOIN users u ON u.id = e.user_id
   WHERE e9x_is_sys_admin() AND e.created_at >= p_from AND e.created_at < p_to
   GROUP BY d, e.user_id, u.email
   ORDER BY d, sum(e.cost_vnd) DESC, e.user_id $$;

CREATE FUNCTION monitor_failed_jobs(p_from timestamptz, p_to timestamptz, p_limit integer)
  RETURNS TABLE (id uuid, type text, attempts integer, last_error text, failed_at timestamptz)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT j.id, j.type, j.attempts, j.last_error, coalesce(j.finished_at, j.updated_at)
    FROM jobs j
   WHERE e9x_is_sys_admin() AND j.status = 'FAILED' AND coalesce(j.finished_at, j.updated_at) >= p_from AND coalesce(j.finished_at, j.updated_at) < p_to
   ORDER BY coalesce(j.finished_at, j.updated_at) DESC, j.id
   LIMIT least(greatest(p_limit, 1), 500) $$;

CREATE FUNCTION monitor_ai_failures(p_from timestamptz, p_to timestamptz) RETURNS integer
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT CASE WHEN e9x_is_sys_admin() THEN (SELECT count(*)::int FROM ai_gradings g WHERE g.state = 'AI_FAILED' AND g.created_at >= p_from AND g.created_at < p_to) ELSE 0 END $$;

-- The teacher's band minus the AI's, per question type: every teacher review of an attempt, against each AI evaluation of the same skill of that attempt.
CREATE FUNCTION monitor_ai_deviation(p_from timestamptz, p_to timestamptz)
  RETURNS TABLE (type_code text, type_name text, samples integer, mean_deviation numeric, mean_abs_deviation numeric)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT v.type_code, coalesce(t.name, v.type_code), count(*)::int, round(avg(r.deviation), 2), round(avg(abs(r.deviation)), 2)
    FROM teacher_reviews r
    JOIN ai_evaluations ev ON ev.attempt_id = r.attempt_id
    JOIN attempt_questions q ON q.attempt_id = ev.attempt_id AND q.question_id = ev.question_id
    JOIN attempt_sections s ON s.attempt_id = q.attempt_id AND s.section_code = q.section_code AND s.skill = r.skill
    JOIN question_versions v ON v.id = q.question_version_id
    LEFT JOIN question_types t ON t.code = v.type_code
   WHERE e9x_is_sys_admin() AND r.deviation IS NOT NULL AND ev.model <> 'none' AND r.created_at >= p_from AND r.created_at < p_to
   GROUP BY v.type_code, t.name
   ORDER BY avg(abs(r.deviation)) DESC, v.type_code $$;

REVOKE ALL ON FUNCTION progress_viewable(uuid, uuid), progress_plans(uuid, uuid), progress_courses(uuid, uuid), progress_attempts(uuid, uuid), progress_pending(uuid, uuid),
  progress_study(uuid, uuid, date), progress_streak(uuid, uuid), monitor_ai_cost(timestamptz, timestamptz), monitor_failed_jobs(timestamptz, timestamptz, integer),
  monitor_ai_failures(timestamptz, timestamptz), monitor_ai_deviation(timestamptz, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION progress_viewable(uuid, uuid), progress_plans(uuid, uuid), progress_courses(uuid, uuid), progress_attempts(uuid, uuid), progress_pending(uuid, uuid),
  progress_study(uuid, uuid, date), progress_streak(uuid, uuid), monitor_ai_cost(timestamptz, timestamptz), monitor_failed_jobs(timestamptz, timestamptz, integer),
  monitor_ai_failures(timestamptz, timestamptz), monitor_ai_deviation(timestamptz, timestamptz) TO e9x_app;
