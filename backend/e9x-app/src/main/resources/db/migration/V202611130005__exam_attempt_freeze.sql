-- P4.1 fix 1: what a learner can write into an attempt after it was assembled and submitted.
--
-- attempt_questions (which questions the attempt holds) and responses (what the learner wrote) are the learner's own rows under RLS (user_id = the person). The
-- exam room only ever writes the first while the attempt is IN_PROGRESS (at assembly) and the second inside the window of the question's section plus the
-- late-sync grace; the database did not say so, so a learner with raw SQL could, after submitting, attach other PUBLISHED questions and write correct answers
-- for them, or back-date an answer. Scoring no longer reads membership from attempt_questions (it walks the immutable test version), and these triggers close
-- the writes themselves. They bind every role but the table owner (Flyway, the E2E seed) and superusers (test fixtures), who are not the request path.
--
-- The grace: the exam room checks the configured e9x.exam.late-sync-grace (default 2m, ExamProperties refuses more than 15m). The database cannot read a
-- configuration key, so it holds the hard ceiling: an answer is never accepted later than 15 minutes after its section ended. The configured grace
-- is enforced by the domain in front of it.

CREATE FUNCTION attempt_questions_freeze() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_attempt uuid := CASE WHEN TG_OP = 'DELETE' THEN OLD.attempt_id ELSE NEW.attempt_id END;
BEGIN
  IF current_user = 'e9x_owner' OR (SELECT r.rolsuper FROM pg_roles r WHERE r.rolname = current_user) THEN
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM exam_attempts a WHERE a.id = v_attempt AND a.status = 'IN_PROGRESS') THEN
    RAISE EXCEPTION 'the questions of an attempt are fixed once it is no longer in progress' USING ERRCODE = '42501';
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER attempt_questions_freeze BEFORE INSERT OR UPDATE OR DELETE ON attempt_questions FOR EACH ROW EXECUTE FUNCTION attempt_questions_freeze();

CREATE FUNCTION responses_window_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_start timestamptz;
  v_end timestamptz;
BEGIN
  IF current_user = 'e9x_owner' OR (SELECT r.rolsuper FROM pg_roles r WHERE r.rolname = current_user) THEN
    RETURN NEW;
  END IF;
  SELECT s.starts_at, coalesce(s.ends_at, s.starts_at + make_interval(secs => s.duration_sec)) INTO v_start, v_end
    FROM attempt_questions q JOIN attempt_sections s ON s.attempt_id = q.attempt_id AND s.section_code = q.section_code
   WHERE q.attempt_id = NEW.attempt_id AND q.question_id = NEW.question_id;
  IF v_start IS NULL OR NEW.answered_at < v_start OR NEW.answered_at >= v_end OR now() >= v_end + interval '15 minutes' THEN
    RAISE EXCEPTION 'an answer is accepted only if written inside the window of its section, and synced within the grace after it' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER responses_window_guard BEFORE INSERT OR UPDATE ON responses FOR EACH ROW EXECUTE FUNCTION responses_window_guard();

-- the grader reads the structure of the test an attempt sat: which questions it holds is the test's decision, not the learner's rows
CREATE POLICY question_group_items_select_grader ON question_group_items FOR SELECT TO e9x_grader
  USING ((owner_org_id IS NULL OR owner_org_id = e9x_current_org())
         AND EXISTS (SELECT 1 FROM question_group_versions g WHERE g.id = question_group_items.group_version_id AND g.status IN ('PUBLISHED', 'ARCHIVED')));
GRANT SELECT ON question_group_items TO e9x_grader;
GRANT SELECT (id, group_id, owner_org_id, exam_code, status) ON question_group_versions TO e9x_grader; -- not the content or the solution
CREATE POLICY question_group_versions_select_grader ON question_group_versions FOR SELECT TO e9x_grader
  USING (status IN ('PUBLISHED', 'ARCHIVED') AND (owner_org_id IS NULL OR owner_org_id = e9x_current_org()));
