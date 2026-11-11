-- P4.3 teacher grading queue (F35) and "Both" mode (F36).
--
--   * org_grading_sides: which side grades the Writing / Speaking of an organisation's learners (spec 10.3, B3): the centre's own teachers (CENTER) or
--     English9x (ENGLISH9X, the default: no row, an ENTERPRISE, a B2C learner). Read-only here; P6.5 (centre grading configuration) writes it.
--   * grading_queue_items: one row per attempt that waits for teachers, on one SIDE (grading_org_id NULL = English9x, else the centre) and one exam.
--     OPEN -> CLAIMED -> DONE (the states of domain.grading.GradingQueueItem). `version` is the optimistic lock: a claim is
--     UPDATE ... WHERE id = ? AND version = ? AND state = 'OPEN', so of two teachers who claim at once one gets 0 rows.
--       WHO SEES WHAT (e9x_app): a TEACHER of the exam on that side sees the OPEN items of THEIR side only, in the context of that side (English9x teachers in the
--       English9x context, a centre's teachers in the centre's context), and the CLAIMED / DONE items they hold themselves: an item somebody else holds is hidden.
--       The administrator of the side (SYS_ADMIN of English9x, ORG_ADMIN of the centre) sees all of the side and may hand an item to a teacher of the side.
--       The grader login (the learner's context, as in P4.1/P4.2) creates the item and marks it DONE; it never claims.
--   * teacher_reviews: the teacher's criteria scores, comment and band for one skill of one attempt; the AI band and the deviation (teacher - AI) are kept for
--     the administrators' monitoring (the learner role is not granted them). Insert-only.
--   * attempt_gradings.official_announced_at: "AttemptScored(official=true)" is announced once, separately from the preliminary announcement
--     (announced_at): in BOTH mode the learner is first told the AI score is preliminary, later that the teacher's is official.
--   * Narrow SECURITY DEFINER functions (search_path pinned, PUBLIC revoked): the grading side of an organisation (grader), the teachers to notify of an item
--     (the listener has no user context), "does the person hold an official score" (hint check of SCORE_OFFICIAL), and the worker's two doors
--     (release_expired_claim, claims due soon).

-- ---- the grading side of an organisation ----------------------------------------------------------------------------------------------
CREATE TABLE org_grading_sides (
  org_id uuid PRIMARY KEY REFERENCES organizations(id),
  side text NOT NULL CHECK (side IN ('CENTER', 'ENGLISH9X')),
  updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE org_grading_sides ENABLE ROW LEVEL SECURITY;
CREATE POLICY org_grading_sides_select ON org_grading_sides FOR SELECT TO e9x_app USING (org_id = e9x_current_org() OR e9x_is_sys_admin());
REVOKE ALL ON org_grading_sides FROM PUBLIC;
GRANT SELECT ON org_grading_sides TO e9x_app;

-- The side that grades the learners of an organisation: the centre itself when it chose its own teachers, otherwise English9x (NULL).
CREATE FUNCTION grading_queue_side(p_owner_org uuid) RETURNS uuid
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT o.id FROM organizations o JOIN org_grading_sides s ON s.org_id = o.id
   WHERE o.id = p_owner_org AND o.type = 'CENTER' AND s.side = 'CENTER'
$$;
REVOKE ALL ON FUNCTION grading_queue_side(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION grading_queue_side(uuid) TO e9x_grader;

-- ---- who is a teacher of a side / its administrator (invoker functions, used by the policies below) ---------------------------------
-- A teacher: holds TEACHER for that exam on that side, and the session's context IS that side.
CREATE FUNCTION grading_queue_my_side(p_exam text, p_grading_org uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT e9x_current_user() IS NOT NULL
     AND p_grading_org IS NOT DISTINCT FROM e9x_current_org()
     AND EXISTS (SELECT 1 FROM role_grants g
                  WHERE g.user_id = e9x_current_user() AND g.role = 'TEACHER' AND g.exam_code = p_exam AND g.org_id IS NOT DISTINCT FROM p_grading_org)
$$;
-- The administrator of the side, in the context of that side.
CREATE FUNCTION grading_queue_my_admin(p_grading_org uuid) RETURNS boolean
  LANGUAGE sql STABLE SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT (p_grading_org IS NULL AND e9x_current_org() IS NULL AND e9x_is_sys_admin())
      OR (p_grading_org IS NOT NULL AND p_grading_org = e9x_current_org() AND e9x_is_current_org_admin())
$$;
-- Is that person a teacher of the exam on the side? Only the administrator of the side may ask (reassignment).
CREATE FUNCTION grading_queue_is_teacher(p_exam text, p_grading_org uuid, p_user uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT grading_queue_my_admin(p_grading_org)
     AND EXISTS (SELECT 1 FROM role_grants g
                  WHERE g.user_id = p_user AND g.role = 'TEACHER' AND g.exam_code = p_exam AND g.org_id IS NOT DISTINCT FROM p_grading_org)
     AND (p_grading_org IS NULL OR EXISTS (SELECT 1 FROM org_memberships m WHERE m.org_id = p_grading_org AND m.user_id = p_user))
$$;
REVOKE ALL ON FUNCTION grading_queue_my_side(text, uuid), grading_queue_my_admin(uuid), grading_queue_is_teacher(text, uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION grading_queue_my_side(text, uuid), grading_queue_my_admin(uuid), grading_queue_is_teacher(text, uuid, uuid) TO e9x_app;

-- ---- the queue ------------------------------------------------------------------------------------------------------------------------
CREATE TABLE grading_queue_items (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  attempt_id uuid NOT NULL UNIQUE REFERENCES exam_attempts(id),
  user_id uuid NOT NULL REFERENCES users(id),
  owner_org_id uuid REFERENCES organizations(id),
  grading_org_id uuid REFERENCES organizations(id),
  exam_code text NOT NULL REFERENCES exams(code),
  reason text NOT NULL CHECK (reason IN ('POLICY', 'AI_FAILED', 'AI_LIMIT')),
  state text NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN', 'CLAIMED', 'DONE')),
  claimed_by uuid REFERENCES users(id),
  claimed_at timestamptz,
  claim_due_at timestamptz,
  graded_at timestamptz,
  version integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT grading_queue_items_state_ck CHECK (
       (state = 'OPEN'    AND claimed_by IS NULL AND claimed_at IS NULL AND claim_due_at IS NULL AND graded_at IS NULL)
    OR (state = 'CLAIMED' AND claimed_by IS NOT NULL AND claimed_at IS NOT NULL AND claim_due_at IS NOT NULL AND graded_at IS NULL)
    OR (state = 'DONE'    AND claimed_by IS NOT NULL AND graded_at IS NOT NULL))
);
CREATE INDEX grading_queue_items_side_idx ON grading_queue_items (grading_org_id, exam_code, state, created_at);
CREATE INDEX grading_queue_items_due_idx ON grading_queue_items (claim_due_at) WHERE state = 'CLAIMED';
ALTER TABLE grading_queue_items ENABLE ROW LEVEL SECURITY;

CREATE POLICY grading_queue_select_teacher ON grading_queue_items FOR SELECT TO e9x_app
  USING (grading_queue_my_side(exam_code, grading_org_id) AND (state = 'OPEN' OR claimed_by = e9x_current_user()));
CREATE POLICY grading_queue_select_admin ON grading_queue_items FOR SELECT TO e9x_app USING (grading_queue_my_admin(grading_org_id));
-- a claim: an OPEN item of my side becomes mine (the optimistic version is in the statement; the guard trigger makes it count)
CREATE POLICY grading_queue_claim ON grading_queue_items FOR UPDATE TO e9x_app
  USING (state = 'OPEN' AND grading_queue_my_side(exam_code, grading_org_id))
  WITH CHECK (state = 'CLAIMED' AND claimed_by = e9x_current_user() AND grading_queue_my_side(exam_code, grading_org_id));
-- the holder puts their item back in the queue
CREATE POLICY grading_queue_release ON grading_queue_items FOR UPDATE TO e9x_app
  USING (state = 'CLAIMED' AND claimed_by = e9x_current_user() AND grading_queue_my_side(exam_code, grading_org_id))
  WITH CHECK (state = 'OPEN' AND claimed_by IS NULL AND grading_queue_my_side(exam_code, grading_org_id));
-- the administrator of the side hands an item to a teacher of the side (or puts it back in the queue)
CREATE POLICY grading_queue_reassign ON grading_queue_items FOR UPDATE TO e9x_app
  USING (state IN ('OPEN', 'CLAIMED') AND grading_queue_my_admin(grading_org_id))
  WITH CHECK ((state = 'OPEN' AND claimed_by IS NULL AND grading_queue_my_admin(grading_org_id))
           OR (state = 'CLAIMED' AND grading_queue_is_teacher(exam_code, grading_org_id, claimed_by)));
-- the grader, in the learner's context
CREATE POLICY grading_queue_select_grader ON grading_queue_items FOR SELECT TO e9x_grader
  USING (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org());
CREATE POLICY grading_queue_insert_grader ON grading_queue_items FOR INSERT TO e9x_grader
  WITH CHECK (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org() AND state = 'OPEN'
              AND grading_org_id IS NOT DISTINCT FROM grading_queue_side(owner_org_id)
              AND EXISTS (SELECT 1 FROM exam_attempts a WHERE a.id = grading_queue_items.attempt_id AND a.user_id = grading_queue_items.user_id
                             AND a.owner_org_id IS NOT DISTINCT FROM grading_queue_items.owner_org_id AND a.exam_code = grading_queue_items.exam_code));
CREATE POLICY grading_queue_done_grader ON grading_queue_items FOR UPDATE TO e9x_grader
  USING (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org() AND state = 'CLAIMED')
  WITH CHECK (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org() AND state = 'DONE');

CREATE FUNCTION grading_queue_items_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'a grading queue item is never deleted' USING ERRCODE = '42501';
  END IF;
  IF NEW.id IS DISTINCT FROM OLD.id OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id OR NEW.user_id IS DISTINCT FROM OLD.user_id
     OR NEW.owner_org_id IS DISTINCT FROM OLD.owner_org_id OR NEW.grading_org_id IS DISTINCT FROM OLD.grading_org_id
     OR NEW.exam_code IS DISTINCT FROM OLD.exam_code OR NEW.reason IS DISTINCT FROM OLD.reason OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
    RAISE EXCEPTION 'only the state, the holder and the times of a queue item change' USING ERRCODE = '42501';
  END IF;
  IF NEW.version IS DISTINCT FROM OLD.version + 1 THEN
    RAISE EXCEPTION 'every change of a queue item raises its version by one' USING ERRCODE = '42501';
  END IF;
  IF (OLD.state = 'DONE') OR (OLD.state = 'OPEN' AND NEW.state <> 'CLAIMED') OR (OLD.state = 'CLAIMED' AND NEW.state = 'DONE' AND NEW.claimed_by IS DISTINCT FROM OLD.claimed_by) THEN
    RAISE EXCEPTION 'queue item cannot go from % to %', OLD.state, NEW.state USING ERRCODE = '42501';
  END IF;
  NEW.updated_at := now();
  RETURN NEW;
END $$;
CREATE TRIGGER grading_queue_items_guard BEFORE UPDATE OR DELETE ON grading_queue_items FOR EACH ROW EXECUTE FUNCTION grading_queue_items_guard();

GRANT SELECT ON grading_queue_items TO e9x_app, e9x_grader;
GRANT UPDATE (state, claimed_by, claimed_at, claim_due_at, version) ON grading_queue_items TO e9x_app;
GRANT INSERT (attempt_id, user_id, owner_org_id, grading_org_id, exam_code, reason) ON grading_queue_items TO e9x_grader;
GRANT UPDATE (state, graded_at, version) ON grading_queue_items TO e9x_grader;

-- ---- the teacher's review ---------------------------------------------------------------------------------------------------------------
CREATE TABLE teacher_reviews (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  queue_item_id uuid NOT NULL REFERENCES grading_queue_items(id),
  attempt_id uuid NOT NULL REFERENCES exam_attempts(id),
  user_id uuid NOT NULL REFERENCES users(id),
  owner_org_id uuid REFERENCES organizations(id),
  skill text NOT NULL CHECK (skill ~ '^[A-Z][A-Z0-9_]{0,31}$'),
  teacher_id uuid NOT NULL REFERENCES users(id),
  criteria jsonb NOT NULL CHECK (jsonb_typeof(criteria) = 'object'),
  comment text NOT NULL DEFAULT '' CHECK (char_length(comment) <= 4000),
  band numeric(6, 2) NOT NULL CHECK (band >= 0),
  ai_band numeric(6, 2) CHECK (ai_band >= 0),
  deviation numeric(6, 2),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (attempt_id, skill),
  CONSTRAINT teacher_reviews_deviation_ck CHECK ((ai_band IS NULL AND deviation IS NULL) OR (ai_band IS NOT NULL AND deviation = band - ai_band))
);
CREATE INDEX teacher_reviews_teacher_idx ON teacher_reviews (teacher_id, created_at DESC);
ALTER TABLE teacher_reviews ENABLE ROW LEVEL SECURITY;
CREATE POLICY teacher_reviews_select_learner ON teacher_reviews FOR SELECT TO e9x_app USING (user_id = e9x_current_user());
CREATE POLICY teacher_reviews_select_teacher ON teacher_reviews FOR SELECT TO e9x_app USING (teacher_id = e9x_current_user());
CREATE POLICY teacher_reviews_select_grader ON teacher_reviews FOR SELECT TO e9x_grader
  USING (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org());
-- written by the grader in the learner's context, only while the queue item is CLAIMED by that very teacher (the sub-select is the grader's own view)
CREATE POLICY teacher_reviews_insert_grader ON teacher_reviews FOR INSERT TO e9x_grader
  WITH CHECK (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org()
              AND EXISTS (SELECT 1 FROM grading_queue_items i WHERE i.id = teacher_reviews.queue_item_id AND i.attempt_id = teacher_reviews.attempt_id
                             AND i.state = 'CLAIMED' AND i.claimed_by = teacher_reviews.teacher_id));
CREATE FUNCTION teacher_reviews_immutable() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  RAISE EXCEPTION 'a teacher review is never changed or deleted' USING ERRCODE = '42501';
END $$;
CREATE TRIGGER teacher_reviews_immutable BEFORE UPDATE OR DELETE ON teacher_reviews FOR EACH ROW EXECUTE FUNCTION teacher_reviews_immutable();
-- the learner and the teacher read the review; the AI band and the deviation are for monitoring (a definer function of the monitoring task), not for this role
GRANT SELECT (id, queue_item_id, attempt_id, user_id, owner_org_id, skill, teacher_id, criteria, comment, band, created_at) ON teacher_reviews TO e9x_app;
GRANT SELECT, INSERT (queue_item_id, attempt_id, user_id, owner_org_id, skill, teacher_id, criteria, comment, band, ai_band, deviation) ON teacher_reviews TO e9x_grader;

-- ---- the announcement of the official score --------------------------------------------------------------------------------------------
ALTER TABLE attempt_gradings ADD COLUMN official_announced_at timestamptz;
ALTER TABLE attempt_gradings DISABLE TRIGGER attempt_gradings_guard;
-- what was announced before this migration without any preliminary score was the official announcement
UPDATE attempt_gradings g SET official_announced_at = g.announced_at
 WHERE g.announced_at IS NOT NULL AND NOT EXISTS (SELECT 1 FROM scores s WHERE s.attempt_id = g.attempt_id AND s.kind = 'PRELIMINARY');
ALTER TABLE attempt_gradings ENABLE TRIGGER attempt_gradings_guard;

CREATE OR REPLACE FUNCTION attempt_gradings_guard() RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'a grading record is never deleted' USING ERRCODE = '42501';
  END IF;
  IF NEW.attempt_id IS DISTINCT FROM OLD.attempt_id OR NEW.user_id IS DISTINCT FROM OLD.user_id OR NEW.owner_org_id IS DISTINCT FROM OLD.owner_org_id
     OR NEW.interrupted IS DISTINCT FROM OLD.interrupted OR NEW.graded_at IS DISTINCT FROM OLD.graded_at
     OR (OLD.announced_at IS NOT NULL AND NEW.announced_at IS DISTINCT FROM OLD.announced_at)
     OR (OLD.official_announced_at IS NOT NULL AND NEW.official_announced_at IS DISTINCT FROM OLD.official_announced_at)
     OR (NEW.announced_at IS NOT DISTINCT FROM OLD.announced_at AND NEW.official_announced_at IS NOT DISTINCT FROM OLD.official_announced_at) THEN
    RAISE EXCEPTION 'a grading record only gains its two announcement times, once each' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;
DROP POLICY attempt_gradings_update_grader ON attempt_gradings;
CREATE POLICY attempt_gradings_update_grader ON attempt_gradings FOR UPDATE TO e9x_grader
  USING (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org() AND (announced_at IS NULL OR official_announced_at IS NULL))
  WITH CHECK (user_id = e9x_current_user() AND owner_org_id IS NOT DISTINCT FROM e9x_current_org());
GRANT UPDATE (official_announced_at) ON attempt_gradings TO e9x_grader;

-- ---- narrow definer doors -----------------------------------------------------------------------------------------------------------------
-- The teachers to tell about an OPEN item: TEACHER of the item's exam on the item's side (members, for a centre). The argument is an item id (the listener
-- holds it from its own insert), not an exam or an organisation, so this cannot list the people of an arbitrary organisation. For a caller with no user context.
CREATE FUNCTION grading_queue_teachers(p_item uuid) RETURNS TABLE (r_user_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT DISTINCT g.user_id
    FROM grading_queue_items i
    JOIN role_grants g ON g.role = 'TEACHER' AND g.exam_code = i.exam_code AND g.org_id IS NOT DISTINCT FROM i.grading_org_id
   WHERE i.id = p_item AND i.state = 'OPEN' AND e9x_current_user() IS NULL
     AND (i.grading_org_id IS NULL OR EXISTS (SELECT 1 FROM org_memberships m WHERE m.org_id = i.grading_org_id AND m.user_id = g.user_id))
$$;
-- Does the attempt of that person hold an OFFICIAL score? Answers the SCORE_OFFICIAL listener that holds an AttemptScored hint and no user context.
CREATE FUNCTION scores_has_official(p_attempt uuid, p_user uuid) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT e9x_current_user() IS NULL
     AND EXISTS (SELECT 1 FROM scores s WHERE s.attempt_id = p_attempt AND s.user_id = p_user AND s.kind = 'OFFICIAL')
$$;
-- release_expired_claim: CLAIMED items whose deadline has passed go back to the queue (idempotent; looks at state, not at "since the last run").
CREATE FUNCTION grading_queue_release_expired(p_now timestamptz) RETURNS TABLE (r_item_id uuid)
  LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  UPDATE grading_queue_items SET state = 'OPEN', claimed_by = NULL, claimed_at = NULL, claim_due_at = NULL, version = version + 1
   WHERE state = 'CLAIMED' AND claim_due_at <= p_now
  RETURNING id
$$;
-- GRADING_CLAIM_DUE: claims whose deadline falls in (p_after, p_until]; the version is part of the notice's key, so a claim taken again is warned again.
CREATE FUNCTION grading_queue_claims_due(p_after timestamptz, p_until timestamptz) RETURNS TABLE (r_item_id uuid, r_teacher_id uuid, r_version integer)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT i.id, i.claimed_by, i.version FROM grading_queue_items i
   WHERE i.state = 'CLAIMED' AND i.claim_due_at > p_after AND i.claim_due_at <= p_until
   ORDER BY i.claim_due_at, i.id
$$;
REVOKE ALL ON FUNCTION grading_queue_teachers(uuid), scores_has_official(uuid, uuid), grading_queue_release_expired(timestamptz), grading_queue_claims_due(timestamptz, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION grading_queue_teachers(uuid), scores_has_official(uuid, uuid) TO e9x_app;
GRANT EXECUTE ON FUNCTION grading_queue_release_expired(timestamptz), grading_queue_claims_due(timestamptz, timestamptz) TO e9x_worker;
