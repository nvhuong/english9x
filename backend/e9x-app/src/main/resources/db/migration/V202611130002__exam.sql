-- P3.3 exam backend (features F29, F30, F31 and the API of F25-F28, F32).
--
-- An attempt pins a test version. exam_attempts (L1d) grows the state of the domain aggregate (com.english9x.domain.exam.ExamAttempt): resume counters, the
-- section being sat, the submit reason, the overall deadline and an optimistic-lock `version`. Every state change loads the row, asks the domain (with the
-- application Clock), and UPDATEs ... WHERE version = <loaded>: of two concurrent submissions (the learner and the auto_submit_attempt job) exactly one
-- changes 1 row; the other reloads, sees SUBMITTED and does nothing.
--
-- Who may do what (all policies TO e9x_app): an attempt and everything hanging off it belongs to the person (user_id = e9x_current_user()); there is NO
-- organisation clause on the writes, so a member who left the organisation still has their attempt submitted (the auto-submit job falls back to the
-- personal context). owner_org_id, user_id, test_version_id, course_id and started_at can never change (trigger), and a SUBMITTED attempt can never change.
-- The solution side of a test is not reachable from here: no table below holds an answer key; `responses.answer` is what the LEARNER wrote.

ALTER TABLE exam_attempts
  ADD COLUMN exam_code text,
  ADD COLUMN course_id uuid REFERENCES courses(id),
  ADD COLUMN max_resumes smallint NOT NULL DEFAULT 2 CHECK (max_resumes BETWEEN 0 AND 20),
  ADD COLUMN resume_count smallint NOT NULL DEFAULT 0 CHECK (resume_count >= 0),
  ADD COLUMN interrupted boolean NOT NULL DEFAULT false,
  ADD COLUMN submit_reason text CHECK (submit_reason IS NULL OR submit_reason IN ('LEARNER','TIME_UP','RESUME_LIMIT')),
  ADD COLUMN current_section_index smallint NOT NULL DEFAULT 0 CHECK (current_section_index >= 0),
  ADD COLUMN current_section_started_at timestamptz,
  ADD COLUMN deadline_at timestamptz,
  ADD COLUMN version bigint NOT NULL DEFAULT 0;
CREATE INDEX exam_attempts_user_idx ON exam_attempts (user_id, started_at DESC);

CREATE TABLE attempt_sections (
  attempt_id uuid NOT NULL REFERENCES exam_attempts(id),
  position smallint NOT NULL CHECK (position >= 0),
  user_id uuid NOT NULL REFERENCES users(id),
  section_code text NOT NULL,
  skill text,
  duration_sec integer NOT NULL CHECK (duration_sec > 0),
  starts_at timestamptz,
  ends_at timestamptz,
  PRIMARY KEY (attempt_id, position),
  UNIQUE (attempt_id, section_code)
);

-- the questions of the pinned version, written once at the start: which section a question is sat in (the clock of its answer) and the exact question version
CREATE TABLE attempt_questions (
  attempt_id uuid NOT NULL REFERENCES exam_attempts(id),
  question_id uuid NOT NULL,
  user_id uuid NOT NULL REFERENCES users(id),
  question_version_id uuid NOT NULL,
  section_code text NOT NULL,
  PRIMARY KEY (attempt_id, question_id)
);

CREATE TABLE responses (
  attempt_id uuid NOT NULL REFERENCES exam_attempts(id),
  question_id uuid NOT NULL,
  user_id uuid NOT NULL REFERENCES users(id),
  answer jsonb NOT NULL,
  answered_at timestamptz NOT NULL,
  saved_at timestamptz NOT NULL,
  PRIMARY KEY (attempt_id, question_id),
  FOREIGN KEY (attempt_id, question_id) REFERENCES attempt_questions(attempt_id, question_id)
);

CREATE TABLE audio_plays (
  attempt_id uuid NOT NULL REFERENCES exam_attempts(id),
  media_id uuid NOT NULL,
  user_id uuid NOT NULL REFERENCES users(id),
  played_at timestamptz NOT NULL,
  PRIMARY KEY (attempt_id, media_id)
);

CREATE TABLE speaking_recordings (
  attempt_id uuid NOT NULL REFERENCES exam_attempts(id),
  question_id uuid NOT NULL,
  user_id uuid NOT NULL REFERENCES users(id),
  storage_path text NOT NULL,
  mime_type text NOT NULL,
  size_bytes bigint NOT NULL CHECK (size_bytes > 0),
  uploaded_at timestamptz NOT NULL,
  PRIMARY KEY (attempt_id, question_id),
  FOREIGN KEY (attempt_id, question_id) REFERENCES attempt_questions(attempt_id, question_id)
);

ALTER TABLE attempt_sections ENABLE ROW LEVEL SECURITY;
ALTER TABLE attempt_questions ENABLE ROW LEVEL SECURITY;
ALTER TABLE responses ENABLE ROW LEVEL SECURITY;
ALTER TABLE audio_plays ENABLE ROW LEVEL SECURITY;
ALTER TABLE speaking_recordings ENABLE ROW LEVEL SECURITY;

-- exam_attempts: the owner updates only an attempt still IN_PROGRESS, in whatever context (a member who left the organisation must still be submitted)
DROP POLICY exam_attempts_update ON exam_attempts;
CREATE POLICY exam_attempts_update ON exam_attempts FOR UPDATE TO e9x_app
  USING (user_id = e9x_current_user() AND status = 'IN_PROGRESS')
  WITH CHECK (user_id = e9x_current_user());

CREATE POLICY attempt_sections_select ON attempt_sections FOR SELECT TO e9x_app USING (user_id = e9x_current_user());
CREATE POLICY attempt_sections_insert ON attempt_sections FOR INSERT TO e9x_app WITH CHECK (user_id = e9x_current_user());
CREATE POLICY attempt_sections_update ON attempt_sections FOR UPDATE TO e9x_app USING (user_id = e9x_current_user()) WITH CHECK (user_id = e9x_current_user());
CREATE POLICY attempt_questions_select ON attempt_questions FOR SELECT TO e9x_app USING (user_id = e9x_current_user());
CREATE POLICY attempt_questions_insert ON attempt_questions FOR INSERT TO e9x_app WITH CHECK (user_id = e9x_current_user());
CREATE POLICY responses_select ON responses FOR SELECT TO e9x_app USING (user_id = e9x_current_user());
CREATE POLICY responses_insert ON responses FOR INSERT TO e9x_app WITH CHECK (user_id = e9x_current_user());
CREATE POLICY responses_update ON responses FOR UPDATE TO e9x_app USING (user_id = e9x_current_user()) WITH CHECK (user_id = e9x_current_user());
CREATE POLICY audio_plays_select ON audio_plays FOR SELECT TO e9x_app USING (user_id = e9x_current_user());
CREATE POLICY audio_plays_insert ON audio_plays FOR INSERT TO e9x_app WITH CHECK (user_id = e9x_current_user());
CREATE POLICY speaking_recordings_select ON speaking_recordings FOR SELECT TO e9x_app USING (user_id = e9x_current_user());
CREATE POLICY speaking_recordings_insert ON speaking_recordings FOR INSERT TO e9x_app WITH CHECK (user_id = e9x_current_user());
CREATE POLICY speaking_recordings_update ON speaking_recordings FOR UPDATE TO e9x_app USING (user_id = e9x_current_user()) WITH CHECK (user_id = e9x_current_user());

GRANT SELECT, INSERT, UPDATE ON attempt_sections, responses, speaking_recordings TO e9x_app;
GRANT SELECT, INSERT ON attempt_questions, audio_plays TO e9x_app;

-- Immutability. The identity of an attempt never changes; a SUBMITTED attempt (and its sections, responses, recordings) never changes again.
CREATE FUNCTION exam_attempts_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status = 'SUBMITTED' THEN
    RAISE EXCEPTION 'a submitted attempt cannot change' USING ERRCODE = '42501';
  END IF;
  IF NEW.user_id IS DISTINCT FROM OLD.user_id OR NEW.owner_org_id IS DISTINCT FROM OLD.owner_org_id
     OR NEW.test_version_id IS DISTINCT FROM OLD.test_version_id OR NEW.started_at IS DISTINCT FROM OLD.started_at
     OR NEW.course_id IS DISTINCT FROM OLD.course_id OR NEW.exam_code IS DISTINCT FROM OLD.exam_code THEN
    RAISE EXCEPTION 'the identity of an attempt cannot change' USING ERRCODE = '42501'; -- insufficient_privilege, like the policy that guarded the owner before
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER exam_attempts_guard BEFORE UPDATE ON exam_attempts FOR EACH ROW EXECUTE FUNCTION exam_attempts_guard();

-- Responses and recordings are NOT frozen by status: the domain decides (ExamAttempt#acceptsAnswer: an answer written in the section's window is accepted
-- until `late-sync-grace` after the section ends, also after a TIME_UP or learner submission). Grading (P4.1) must therefore start after that grace.
