-- P3.2 learning (features F22, F23, data for F24): what a learner has done in a course, the practice answers, the chapter-test attempts and the study time.
--
-- Everything here belongs to the PERSON: every policy is user_id = e9x_current_user() with no organisation clause (a learner who changes context keeps their
-- progress). Rows are only ever written through the learning module, which has asked the lesson gate (entitlement + unlock rule) first.
--
-- Progress is keyed by (user, course, ref_id): ref_id is the course_lessons.ref_id (the version of the strategy lesson or of the test the lesson points at),
-- NOT the course_lessons.id, which belongs to one course version. A new version of the course that keeps pointing at the same lesson version keeps the progress.
--
-- THE ONE PLACE A LEARNER MAY SEE AN ANSWER is practice (an embedded question of a lesson). learning_practice_key / learning_test_keys are the only way the
-- request role reaches an answer key: SECURITY DEFINER, pinned search_path, EXECUTE for e9x_app only, and each one re-checks in SQL that (a) a person is bound,
-- (b) the lesson belongs to a course version this person may read, (c) the question is one of that lesson (or of that chapter test), (d) the person holds an
-- entitlement for the course right now. They return the key to the learning module, which scores and answers {correct, earned, explanation, correctAnswer}.

CREATE TABLE lesson_progress (
  user_id uuid NOT NULL REFERENCES users(id),
  course_id uuid NOT NULL REFERENCES courses(id),
  ref_id uuid NOT NULL,
  kind text NOT NULL CHECK (kind IN ('LESSON', 'CHAPTER_TEST', 'MOCK_TEST')),
  completed boolean NOT NULL DEFAULT false,
  completed_at timestamptz,
  best_score numeric(5,4) CHECK (best_score IS NULL OR (best_score >= 0 AND best_score <= 1)),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, course_id, ref_id)
);

CREATE TABLE practice_attempts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id),
  course_id uuid NOT NULL REFERENCES courses(id),
  lesson_ref_id uuid NOT NULL,
  question_version_id uuid NOT NULL,
  type_code text NOT NULL,
  answer jsonb NOT NULL,
  correct boolean NOT NULL,
  earned numeric(8,2) NOT NULL CHECK (earned >= 0),
  points numeric(8,2) NOT NULL CHECK (points >= 0),
  answered_at timestamptz NOT NULL
);
CREATE INDEX practice_attempts_user_idx ON practice_attempts (user_id, answered_at DESC);
CREATE INDEX practice_attempts_type_idx ON practice_attempts (user_id, type_code);

CREATE TABLE chapter_test_attempts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id),
  course_id uuid NOT NULL REFERENCES courses(id),
  ref_id uuid NOT NULL,
  attempt_no integer NOT NULL CHECK (attempt_no > 0),
  raw numeric(8,2) NOT NULL CHECK (raw >= 0),
  raw_max numeric(8,2) NOT NULL CHECK (raw_max >= 0),
  score numeric(5,4) NOT NULL CHECK (score >= 0 AND score <= 1),
  passed boolean NOT NULL,
  answers jsonb NOT NULL,
  submitted_at timestamptz NOT NULL,
  UNIQUE (user_id, course_id, ref_id, attempt_no)
);

-- one row per heartbeat (the signal); study_time_daily is what the domain (StudyTime.credited) makes of the day's signals, in the learner's own time zone
CREATE TABLE study_heartbeats (
  user_id uuid NOT NULL REFERENCES users(id),
  at timestamptz NOT NULL,
  day date NOT NULL,
  course_id uuid NOT NULL,
  lesson_id uuid NOT NULL,
  PRIMARY KEY (user_id, at)
);
CREATE INDEX study_heartbeats_day_idx ON study_heartbeats (user_id, day);

CREATE TABLE study_time_daily (
  user_id uuid NOT NULL REFERENCES users(id),
  day date NOT NULL,
  seconds integer NOT NULL DEFAULT 0 CHECK (seconds >= 0),
  lessons_completed integer NOT NULL DEFAULT 0 CHECK (lessons_completed >= 0),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, day)
);

-- the streak as of the last signal, and the time zone the days are cut in (the browser's, sent with the heartbeat; Asia/Ho_Chi_Minh until it is known)
CREATE TABLE study_streaks (
  user_id uuid PRIMARY KEY REFERENCES users(id),
  current_days integer NOT NULL DEFAULT 0 CHECK (current_days >= 0),
  longest_days integer NOT NULL DEFAULT 0 CHECK (longest_days >= 0),
  last_day date,
  time_zone text NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
  updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE lesson_progress ENABLE ROW LEVEL SECURITY;
ALTER TABLE practice_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE chapter_test_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE study_heartbeats ENABLE ROW LEVEL SECURITY;
ALTER TABLE study_time_daily ENABLE ROW LEVEL SECURITY;
ALTER TABLE study_streaks ENABLE ROW LEVEL SECURITY;

CREATE POLICY lesson_progress_select ON lesson_progress FOR SELECT TO e9x_app USING (user_id = e9x_current_user());
CREATE POLICY lesson_progress_insert ON lesson_progress FOR INSERT TO e9x_app WITH CHECK (user_id = e9x_current_user());
CREATE POLICY lesson_progress_update ON lesson_progress FOR UPDATE TO e9x_app USING (user_id = e9x_current_user()) WITH CHECK (user_id = e9x_current_user());
CREATE POLICY practice_attempts_select ON practice_attempts FOR SELECT TO e9x_app USING (user_id = e9x_current_user());
CREATE POLICY practice_attempts_insert ON practice_attempts FOR INSERT TO e9x_app WITH CHECK (user_id = e9x_current_user());
CREATE POLICY chapter_test_attempts_select ON chapter_test_attempts FOR SELECT TO e9x_app USING (user_id = e9x_current_user());
CREATE POLICY chapter_test_attempts_insert ON chapter_test_attempts FOR INSERT TO e9x_app WITH CHECK (user_id = e9x_current_user());
CREATE POLICY study_heartbeats_select ON study_heartbeats FOR SELECT TO e9x_app USING (user_id = e9x_current_user());
CREATE POLICY study_heartbeats_insert ON study_heartbeats FOR INSERT TO e9x_app WITH CHECK (user_id = e9x_current_user());
CREATE POLICY study_time_daily_select ON study_time_daily FOR SELECT TO e9x_app USING (user_id = e9x_current_user());
CREATE POLICY study_time_daily_insert ON study_time_daily FOR INSERT TO e9x_app WITH CHECK (user_id = e9x_current_user());
CREATE POLICY study_time_daily_update ON study_time_daily FOR UPDATE TO e9x_app USING (user_id = e9x_current_user()) WITH CHECK (user_id = e9x_current_user());
CREATE POLICY study_streaks_select ON study_streaks FOR SELECT TO e9x_app USING (user_id = e9x_current_user());
CREATE POLICY study_streaks_insert ON study_streaks FOR INSERT TO e9x_app WITH CHECK (user_id = e9x_current_user());
CREATE POLICY study_streaks_update ON study_streaks FOR UPDATE TO e9x_app USING (user_id = e9x_current_user()) WITH CHECK (user_id = e9x_current_user());

GRANT SELECT, INSERT ON practice_attempts, chapter_test_attempts, study_heartbeats TO e9x_app;
GRANT SELECT, INSERT ON lesson_progress, study_time_daily, study_streaks TO e9x_app;
GRANT UPDATE (completed, completed_at, best_score, updated_at) ON lesson_progress TO e9x_app;
GRANT UPDATE (seconds, lessons_completed, updated_at) ON study_time_daily TO e9x_app;
GRANT UPDATE (current_days, longest_days, last_day, time_zone, updated_at) ON study_streaks TO e9x_app;

-- ==== the answer side, for practice and chapter tests only ==========================================================================
-- Is this lesson one the bound person may open right now, as far as the data goes (the unlock rule is the lesson gate's, in the application)?
CREATE FUNCTION learning_lesson_open(p_lesson uuid, p_now timestamptz) RETURNS TABLE (course_id uuid, ref_id uuid, owner_org_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT v.course_id, l.ref_id, l.owner_org_id
    FROM course_lessons l JOIN course_versions v ON v.id = l.course_version_id
   WHERE l.id = p_lesson AND e9x_current_user() IS NOT NULL
     AND ((v.status IN ('PUBLISHED', 'ARCHIVED') AND (v.owner_org_id IS NULL OR v.owner_org_id = e9x_current_org()))
          OR (v.status = 'PUBLISHED' AND EXISTS (SELECT 1 FROM courses c WHERE c.id = v.course_id AND c.shared_status = 'APPROVED')))
     AND EXISTS (SELECT 1 FROM entitlements e WHERE e.user_id = e9x_current_user() AND e.course_id = v.course_id
                    AND e.valid_from <= p_now AND p_now < e.valid_until) $$;

-- The key of ONE question embedded in ONE lesson (kind LESSON) the person is entitled to. Nothing for any other pair.
CREATE FUNCTION learning_practice_key(p_lesson uuid, p_question uuid, p_now timestamptz)
  RETURNS TABLE (course_id uuid, lesson_ref_id uuid, type_code text, kind text, answer_key jsonb, explanation text, points numeric)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT o.course_id, o.ref_id, q.type_code, qt.interaction_kind, q.answer_key, q.explanation, q.points
    FROM learning_lesson_open(p_lesson, p_now) o
    JOIN course_lessons l ON l.id = p_lesson AND l.kind = 'LESSON'
    JOIN strategy_lesson_versions s ON s.id = l.ref_id
    JOIN question_versions q ON q.id = p_question AND q.status IN ('PUBLISHED', 'ARCHIVED')
                            AND (q.owner_org_id IS NULL OR q.owner_org_id IS NOT DISTINCT FROM s.owner_org_id)
    JOIN question_types qt ON qt.code = q.type_code
   WHERE jsonb_typeof(s.blocks) = 'array'
     AND EXISTS (SELECT 1 FROM jsonb_array_elements(s.blocks) b
                  WHERE b ->> 'type' = 'embedded-question' AND b ->> 'questionVersionId' = p_question::text) $$;

-- The keys of the questions of the test a CHAPTER_TEST lesson points at, for the person's submission (scored, never shown).
CREATE FUNCTION learning_test_keys(p_lesson uuid, p_now timestamptz)
  RETURNS TABLE (course_id uuid, ref_id uuid, question_version_id uuid, type_code text, kind text, answer_key jsonb, points numeric)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT DISTINCT o.course_id, o.ref_id, i.question_version_id, q.type_code, qt.interaction_kind, q.answer_key, q.points
    FROM learning_lesson_open(p_lesson, p_now) o
    JOIN course_lessons l ON l.id = p_lesson AND l.kind = 'CHAPTER_TEST'
    JOIN test_part_groups g ON g.test_version_id = l.ref_id
    JOIN question_group_items i ON i.group_version_id = g.group_version_id
    JOIN question_versions q ON q.id = i.question_version_id
    JOIN question_types qt ON qt.code = q.type_code $$;

REVOKE ALL ON FUNCTION learning_lesson_open(uuid, timestamptz), learning_practice_key(uuid, uuid, timestamptz), learning_test_keys(uuid, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION learning_lesson_open(uuid, timestamptz), learning_practice_key(uuid, uuid, timestamptz), learning_test_keys(uuid, timestamptz) TO e9x_app;
