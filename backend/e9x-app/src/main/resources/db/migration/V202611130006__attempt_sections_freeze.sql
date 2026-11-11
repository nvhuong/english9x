-- P4.1 fix 2: the section timers of an attempt are frozen with it.
--
-- responses_window_guard (V202611130005) judges an answer by the window of its section, read from attempt_sections. That table was still writable by the
-- learner after submitting (policy: user_id only), so stretching ends_at widened the window the guard checks. Same rule as attempt_questions: once the attempt
-- is not IN_PROGRESS nobody but the table owner (Flyway, the E2E seed) and superusers (test fixtures) inserts, changes or deletes a section row. The exam
-- service writes the sections right AFTER it marks the attempt submitted, in the same transaction (ExamRepository#save), so a row of an attempt that became
-- SUBMITTED in THIS transaction (its xmin is ours) is still written; any later transaction is refused.
CREATE FUNCTION attempt_sections_freeze() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_attempt uuid := CASE WHEN TG_OP = 'DELETE' THEN OLD.attempt_id ELSE NEW.attempt_id END;
BEGIN
  IF current_user = 'e9x_owner' OR (SELECT r.rolsuper FROM pg_roles r WHERE r.rolname = current_user) THEN
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM exam_attempts a WHERE a.id = v_attempt AND (a.status = 'IN_PROGRESS' OR a.xmin = pg_current_xact_id()::xid)) THEN
    RAISE EXCEPTION 'the sections of an attempt are fixed once it is no longer in progress' USING ERRCODE = '42501';
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER attempt_sections_freeze BEFORE INSERT OR UPDATE OR DELETE ON attempt_sections FOR EACH ROW EXECUTE FUNCTION attempt_sections_freeze();
