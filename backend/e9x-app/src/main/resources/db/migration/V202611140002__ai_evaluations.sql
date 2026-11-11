-- P4.2 AI grading of Writing / Speaking (F34, F37).
--
--   * ai_gradings: one row per attempt the AI job has decided about. state DONE (every W/S response evaluated, scores written), AI_FAILED (the retries were
--     used up; the learner still sees "grading in progress", an administrator is told) or TEACHER_QUEUE (the emergency per-user limit routed the attempt
--     to the teachers instead of the AI: P4.3 takes these). DONE is final; AI_FAILED may still become DONE (a late run, an administrator's re-run).
--   * ai_evaluations: what the AI answered for one response (skill version, model, tokens, estimated cost, the validated JSON). Insert-only.
--   * The grader login (same pool as the automatic scoring) reads the PUBLISHED grading skill (policy of V202611100001: English9x + the bound
--     organisation), the question content, and the Speaking recordings of the attempt in the session; and writes the two tables above and AI scores.
--   * scores_has_preliminary: the narrow definer function the notification listener uses to check an AttemptScored hint.
CREATE TABLE ai_gradings (
  attempt_id uuid PRIMARY KEY REFERENCES exam_attempts(id),
  user_id uuid NOT NULL REFERENCES users(id),
  owner_org_id uuid REFERENCES organizations(id),
  state text NOT NULL CHECK (state IN ('DONE', 'AI_FAILED', 'TEACHER_QUEUE')),
  error text CHECK (error IS NULL OR length(error) <= 1000),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ai_gradings_user_idx ON ai_gradings (user_id, created_at DESC);
ALTER TABLE ai_gradings ENABLE ROW LEVEL SECURITY;
CREATE POLICY ai_gradings_select_own ON ai_gradings FOR SELECT TO e9x_app USING (user_id = e9x_current_user());
CREATE POLICY ai_gradings_select_grader ON ai_gradings FOR SELECT TO e9x_grader
  USING (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org());
CREATE POLICY ai_gradings_insert_grader ON ai_gradings FOR INSERT TO e9x_grader
  WITH CHECK (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org()
              AND EXISTS (SELECT 1 FROM exam_attempts a WHERE a.id = ai_gradings.attempt_id AND a.user_id = ai_gradings.user_id
                             AND a.owner_org_id IS NOT DISTINCT FROM ai_gradings.owner_org_id));
CREATE POLICY ai_gradings_update_grader ON ai_gradings FOR UPDATE TO e9x_grader
  USING (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org() AND state = 'AI_FAILED')
  WITH CHECK (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org());
CREATE FUNCTION ai_gradings_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'an AI grading record is never deleted' USING ERRCODE = '42501';
  END IF;
  IF NEW.attempt_id IS DISTINCT FROM OLD.attempt_id OR NEW.user_id IS DISTINCT FROM OLD.user_id OR NEW.owner_org_id IS DISTINCT FROM OLD.owner_org_id
     OR NEW.created_at IS DISTINCT FROM OLD.created_at OR OLD.state <> 'AI_FAILED' OR NEW.state <> 'DONE' THEN
    RAISE EXCEPTION 'an AI grading record only moves from AI_FAILED to DONE' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER ai_gradings_guard BEFORE UPDATE OR DELETE ON ai_gradings FOR EACH ROW EXECUTE FUNCTION ai_gradings_guard();
GRANT SELECT ON ai_gradings TO e9x_app, e9x_grader;
GRANT INSERT (attempt_id, user_id, owner_org_id, state, error) ON ai_gradings TO e9x_grader;
GRANT UPDATE (state, error, updated_at) ON ai_gradings TO e9x_grader;

CREATE TABLE ai_evaluations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  attempt_id uuid NOT NULL REFERENCES exam_attempts(id),
  question_id uuid NOT NULL,
  user_id uuid NOT NULL REFERENCES users(id),
  owner_org_id uuid REFERENCES organizations(id),
  skill_version_id uuid REFERENCES grading_skill_versions(id),
  model text NOT NULL,
  input_tokens integer NOT NULL CHECK (input_tokens >= 0),
  output_tokens integer NOT NULL CHECK (output_tokens >= 0),
  cost_vnd numeric(14, 4) NOT NULL CHECK (cost_vnd >= 0),
  band numeric(6, 2) NOT NULL CHECK (band >= 0),
  result jsonb NOT NULL CHECK (jsonb_typeof(result) = 'object'),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (attempt_id, question_id)
);
CREATE INDEX ai_evaluations_day_idx ON ai_evaluations (created_at);
ALTER TABLE ai_evaluations ENABLE ROW LEVEL SECURITY;
CREATE POLICY ai_evaluations_select_own ON ai_evaluations FOR SELECT TO e9x_app USING (user_id = e9x_current_user());
CREATE POLICY ai_evaluations_select_grader ON ai_evaluations FOR SELECT TO e9x_grader
  USING (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org());
CREATE POLICY ai_evaluations_insert_grader ON ai_evaluations FOR INSERT TO e9x_grader
  WITH CHECK (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org()
              AND EXISTS (SELECT 1 FROM exam_attempts a WHERE a.id = ai_evaluations.attempt_id AND a.user_id = ai_evaluations.user_id
                             AND a.owner_org_id IS NOT DISTINCT FROM ai_evaluations.owner_org_id));
CREATE FUNCTION ai_evaluations_immutable() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  RAISE EXCEPTION 'an AI evaluation is never changed or deleted' USING ERRCODE = '42501';
END $$;
CREATE TRIGGER ai_evaluations_immutable BEFORE UPDATE OR DELETE ON ai_evaluations FOR EACH ROW EXECUTE FUNCTION ai_evaluations_immutable();
GRANT SELECT ON ai_evaluations TO e9x_app, e9x_grader;
GRANT INSERT (attempt_id, question_id, user_id, owner_org_id, skill_version_id, model, input_tokens, output_tokens, cost_vnd, band, result) ON ai_evaluations TO e9x_grader;

-- the recording of a Speaking question, for the grader: only while the attempt (SUBMITTED, of the person and organisation in the session) is visible
CREATE POLICY speaking_recordings_select_grader ON speaking_recordings FOR SELECT TO e9x_grader
  USING (user_id = e9x_current_user() AND EXISTS (SELECT 1 FROM exam_attempts a WHERE a.id = speaking_recordings.attempt_id AND a.user_id = speaking_recordings.user_id));
GRANT SELECT ON speaking_recordings TO e9x_grader;

-- Does the attempt of that person hold a PRELIMINARY score? Answers a notification listener that holds an AttemptScored hint and no user context.
CREATE FUNCTION scores_has_preliminary(p_attempt uuid, p_user uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT e9x_current_user() IS NULL
     AND EXISTS (SELECT 1 FROM scores s WHERE s.attempt_id = p_attempt AND s.user_id = p_user AND s.kind = 'PRELIMINARY')
$$;
REVOKE ALL ON FUNCTION scores_has_preliminary(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION scores_has_preliminary(uuid, uuid) TO e9x_app;
