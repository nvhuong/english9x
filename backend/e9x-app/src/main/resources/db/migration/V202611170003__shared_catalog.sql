-- P6.5 branch C — the shared catalogue (F21, spec §14). P1.6/P3.1 already carry the spine: courses.shared_status (NONE/PENDING/APPROVED), the owner's narrow
-- request path (courses_share_request: the owner's AUTHOR or ORG_ADMIN, NONE <-> PENDING), the English9x decision path (courses_share_decide: an English9x
-- REVIEWER of the exam, in the English9x context, with the staff flag the approval trigger also wants), the public view of an APPROVED shared course
-- (courses_select_public) and the reviewers' view of the queue (courses_select_shared_review). review_requests has NO SHARED_CATALOG type and is NOT widened:
-- the queue is the PENDING rows of courses, which only an English9x REVIEWER of the exam may read.
--
-- P6.5 adds, by the two rulings in the task:
--   (i)  SYS_ADMIN does NOT approve by being SYS_ADMIN — approval stays with the English9x REVIEWER role of the exam (spec §4, P1.6: roles do not imply each
--        other). This is already what courses_share_decide requires; no change is needed for it, and db-test 59 proves a bare SYS_ADMIN cannot approve.
--   (ii) REJECTED is a real status (clearer than a bare return to NONE, ledger line 59): an English9x REVIEWER declines a PENDING request or removes an APPROVED
--        course from the pool to REJECTED; the owner may re-request (REJECTED -> PENDING) or withdraw a PENDING request (PENDING -> NONE). REJECTED is not public.

ALTER TABLE courses DROP CONSTRAINT courses_shared_status_check;
ALTER TABLE courses ADD CONSTRAINT courses_shared_status_check CHECK (shared_status IN ('NONE', 'PENDING', 'APPROVED', 'REJECTED'));

-- the owner's request path, now also REJECTED -> PENDING (re-request) and still PENDING -> NONE (withdraw); never reaches APPROVED or REJECTED itself
DROP POLICY courses_share_request ON courses;
CREATE POLICY courses_share_request ON courses FOR UPDATE TO e9x_app
  USING (owner_org_id IS NOT NULL AND e9x_workflow_in_context(owner_org_id) AND shared_status IN ('NONE', 'PENDING', 'REJECTED')
         AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'ORG_ADMIN']))
  WITH CHECK (owner_org_id IS NOT NULL AND e9x_workflow_in_context(owner_org_id) AND shared_status IN ('NONE', 'PENDING')
              AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR', 'ORG_ADMIN']));

-- the English9x REVIEWER's decision path: PENDING -> APPROVED | REJECTED, and APPROVED -> NONE | REJECTED (withdraw a shared course from the pool, ledger 59)
DROP POLICY courses_share_decide ON courses;
CREATE POLICY courses_share_decide ON courses FOR UPDATE TO e9x_app
  USING (owner_org_id IS NOT NULL AND shared_status IN ('PENDING', 'APPROVED') AND e9x_workflow_in_context(NULL)
         AND e9x_workflow_has_role(NULL, exam_code, ARRAY['REVIEWER']))
  WITH CHECK (owner_org_id IS NOT NULL AND shared_status IN ('NONE', 'PENDING', 'APPROVED', 'REJECTED') AND e9x_workflow_in_context(NULL)
              AND e9x_workflow_has_role(NULL, exam_code, ARRAY['REVIEWER']));

-- the queue the English9x reviewers read: the PENDING rows (and the APPROVED / REJECTED history) of their exam
DROP POLICY courses_select_shared_review ON courses;
CREATE POLICY courses_select_shared_review ON courses FOR SELECT TO e9x_app
  USING (owner_org_id IS NOT NULL AND shared_status IN ('PENDING', 'APPROVED', 'REJECTED') AND e9x_workflow_in_context(NULL)
         AND e9x_workflow_has_role(NULL, exam_code, ARRAY['REVIEWER']));

-- The English9x reviewer reads the PUBLISHED version of a shared course (its title and author line for the queue), parallel to courses_select_shared_review.
-- Without this the reviewer sees the course header but not its version, so the queue could not name the version (ghi chú 2026-11-08 P1.6: "nêu phiên bản").
CREATE POLICY course_versions_select_shared_review ON course_versions FOR SELECT TO e9x_app
  USING (status = 'PUBLISHED' AND owner_org_id IS NOT NULL AND e9x_workflow_in_context(NULL)
         AND e9x_workflow_has_role(NULL, exam_code, ARRAY['REVIEWER'])
         AND EXISTS (SELECT 1 FROM courses c WHERE c.id = course_id AND c.shared_status IN ('PENDING', 'APPROVED', 'REJECTED')));

-- The owner CENTRE's name for the queue: an English9x reviewer is not a member of the centre, so organizations is not in their RLS view. This narrow definer
-- reveals the name ONLY for an organisation that owns a course in the shared pool (PENDING/APPROVED/REJECTED) — it cannot enumerate arbitrary organisations,
-- and the reviewer already sees that course row (courses_select_shared_review). Nothing personal: an organisation's name only.
CREATE FUNCTION shared_catalog_owner_name(p_org uuid) RETURNS text
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT o.name FROM organizations o
   WHERE o.id = p_org AND EXISTS (SELECT 1 FROM courses c WHERE c.owner_org_id = p_org AND c.shared_status IN ('PENDING', 'APPROVED', 'REJECTED'))
$$;
REVOKE ALL ON FUNCTION shared_catalog_owner_name(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION shared_catalog_owner_name(uuid) TO e9x_app;
