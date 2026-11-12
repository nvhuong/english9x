-- P4.4 results and weakness (F38): item_type_stats, and the narrow answer "which method lessons are there for these question types".
--
-- WHY A TABLE. "How often does this person get a question type right, over all their attempts" needs, for every question they met, whether the answer was right.
-- That needs the answer key, and the key is the grader's side of the database (question_versions.answer_key is not readable by the request role: a learner, or an SQL
-- statement of a hijacked request, must never be able to read a key). So the automatic scoring, which already reads the key on the grader's connection, writes ONE row per
-- objective question of the attempt at the moment it scores it (the same rows, the same scorer: the statistic and the score of the attempt cannot disagree), and the request
-- role only READS its own rows, so the weakness page is a plain GROUP BY under Row-Level Security that never touches a key.
--
-- Rows are insert-only (like scores): a statistic is a fact about a finished, scored attempt. Writing and Speaking questions have no row (no objective right/wrong).
-- The unit of the aggregate is the question TYPE, the thing a method lesson is written for.
--
-- WHO. e9x_grader inserts, only for the person and organisation of the session and only for an attempt of that person that is SUBMITTED (the sub-select is the grader's own
-- view of exam_attempts, as for scores). e9x_app selects its own rows. Nobody updates or deletes.
CREATE TABLE item_type_stats (
  attempt_id uuid NOT NULL REFERENCES exam_attempts(id),
  question_version_id uuid NOT NULL,
  user_id uuid NOT NULL REFERENCES users(id),
  owner_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  type_code text NOT NULL,
  skill text NOT NULL CHECK (skill ~ '^[A-Z][A-Z0-9_]{0,31}$'),
  earned numeric(9, 2) NOT NULL CHECK (earned >= 0),
  max_points numeric(9, 2) NOT NULL CHECK (max_points > 0 AND earned <= max_points),
  correct boolean NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (attempt_id, question_version_id),
  FOREIGN KEY (type_code, exam_code) REFERENCES question_types(code, exam_code)
);
CREATE INDEX item_type_stats_user_type_idx ON item_type_stats (user_id, exam_code, type_code);
ALTER TABLE item_type_stats ENABLE ROW LEVEL SECURITY;

CREATE POLICY item_type_stats_select_own ON item_type_stats FOR SELECT TO e9x_app USING (user_id = e9x_current_user());
CREATE POLICY item_type_stats_select_grader ON item_type_stats FOR SELECT TO e9x_grader
  USING (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org());
CREATE POLICY item_type_stats_insert_grader ON item_type_stats FOR INSERT TO e9x_grader
  WITH CHECK (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org()
              AND EXISTS (SELECT 1 FROM exam_attempts a WHERE a.id = item_type_stats.attempt_id AND a.user_id = item_type_stats.user_id
                             AND a.owner_org_id IS NOT DISTINCT FROM item_type_stats.owner_org_id AND a.status = 'SUBMITTED'));

CREATE FUNCTION item_type_stats_immutable() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  RAISE EXCEPTION 'a question statistic is never changed or deleted' USING ERRCODE = '42501';
END $$;
CREATE TRIGGER item_type_stats_immutable BEFORE UPDATE OR DELETE ON item_type_stats FOR EACH ROW EXECUTE FUNCTION item_type_stats_immutable();

GRANT SELECT ON item_type_stats TO e9x_app, e9x_grader;
GRANT INSERT (attempt_id, question_version_id, user_id, owner_org_id, exam_code, type_code, skill, earned, max_points, correct) ON item_type_stats TO e9x_grader;

-- ---- the method lessons a person may be pointed to -------------------------------------------------------------------------------------------------------------------
-- strategy lessons are readable by their authors and reviewers only (strategy_lessons / strategy_lesson_types policies); a learner reads them through the course that holds
-- them. This is that path, narrowed to what a recommendation needs: the PUBLISHED strategy lessons (kind LESSON) of PUBLISHED course versions the bound person can see, that
-- are tagged with one of the question types, in a course the person holds an entitlement to at p_now (the same conditions as learning_lesson_open). Nothing of the lesson's
-- content, only where it is. With nobody bound the answer is empty.
CREATE FUNCTION results_method_lessons(p_exam text, p_types text[], p_now timestamptz)
  RETURNS TABLE (type_code text, course_id uuid, course_title text, course_lesson_id uuid, lesson_title text)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT t.type_code, v.course_id, c.title, l.id, l.title
    FROM course_lessons l
    JOIN course_versions v ON v.id = l.course_version_id
    JOIN courses c ON c.id = v.course_id
    JOIN strategy_lesson_versions s ON s.id = l.ref_id AND s.status = 'PUBLISHED'
    JOIN strategy_lesson_types t ON t.version_id = s.id
   WHERE e9x_current_user() IS NOT NULL
     AND l.kind = 'LESSON'
     AND (p_exam IS NULL OR v.exam_code = p_exam)
     AND t.type_code = ANY (p_types)
     AND v.status = 'PUBLISHED'
     AND (v.owner_org_id IS NULL OR v.owner_org_id = e9x_current_org() OR c.shared_status = 'APPROVED')
     AND EXISTS (SELECT 1 FROM entitlements e WHERE e.user_id = e9x_current_user() AND e.course_id = v.course_id
                    AND e.valid_from <= p_now AND p_now < e.valid_until)
   ORDER BY t.type_code, c.title, l.chapter_position, l.position $$;
REVOKE ALL ON FUNCTION results_method_lessons(text, text[], timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION results_method_lessons(text, text[], timestamptz) TO e9x_app;
