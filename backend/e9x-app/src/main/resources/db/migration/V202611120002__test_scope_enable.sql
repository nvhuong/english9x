-- P2.6 enables the TEST scope that P2.1 refused (V202611100001: score_tables_test_scope_refused_ck, grading_policies_test_scope_refused_ck), now that
-- `tests` exists (V202611120001). The note of 2026-11-10 lists what had to come together, and here it does:
--   (a) both CHECKs are dropped, at the same time as
--   (b) the foreign key test_id -> tests(id) on both tables,
--   (c) the owner-match rule: the owner (and exam) of a TEST row is the owner of its test (a trigger: a composite foreign key cannot say it, a NULL owner
--       means English9x and MATCH SIMPLE skips a NULL),
--   (d) the writers of a TEST row are the administrators of the test's owner only (spec 2, row 6, "Quản trị đặt"): the SYS_ADMIN for an English9x test,
--       the ORG_ADMIN of the organisation for its test (the placeholder that named the exam's AUTHOR is replaced),
--   (e) a TEST grading policy is frozen once its test has a PUBLISHED or ARCHIVED version (an attempt may be graded under it; spec 10.3 gives the policy
--       "đề > tổ chức > kỳ thi" and a result must not be graded by a rule that changed under it).
-- The conversion table of a test is versioned content (a new version replaces the old one in force), so no freeze is needed for score tables: the header is
-- insert-only, the rows go through the review, and a result records the version it used (P4.1).

-- ---- (a) (b)
ALTER TABLE score_tables DROP CONSTRAINT score_tables_test_scope_refused_ck;
ALTER TABLE grading_policies DROP CONSTRAINT grading_policies_test_scope_refused_ck;
ALTER TABLE score_tables ADD CONSTRAINT score_tables_test_fk FOREIGN KEY (test_id) REFERENCES tests(id);
ALTER TABLE grading_policies ADD CONSTRAINT grading_policies_test_fk FOREIGN KEY (test_id) REFERENCES tests(id);
CREATE INDEX score_tables_test_idx ON score_tables (test_id) WHERE test_id IS NOT NULL;
CREATE INDEX grading_policies_test_idx ON grading_policies (test_id) WHERE test_id IS NOT NULL;

-- ---- (c) SECURITY DEFINER: it must see the test whatever the caller's policies show (a test it cannot find is a refusal, never a pass)
CREATE FUNCTION catalog_check_test_owner() RETURNS trigger
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  IF NEW.scope = 'TEST' AND NOT EXISTS (SELECT 1 FROM tests t WHERE t.id = NEW.test_id
                                          AND t.owner_org_id IS NOT DISTINCT FROM NEW.owner_org_id AND t.exam_code = NEW.exam_code) THEN
    RAISE EXCEPTION 'a TEST row carries the owner and the exam of its test' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER score_tables_test_owner_trg BEFORE INSERT ON score_tables
  FOR EACH ROW EXECUTE FUNCTION catalog_check_test_owner();
CREATE TRIGGER grading_policies_test_owner_trg BEFORE INSERT OR UPDATE ON grading_policies
  FOR EACH ROW EXECUTE FUNCTION catalog_check_test_owner();

-- ---- (e)
-- Invoker rights on purpose: current_user is the caller (the owner and a superuser, i.e. migrations and fixtures, are exempt, like the decision guard), and the
-- caller sees the PUBLISHED / ARCHIVED versions of the test's owner under the policies of test_versions, which is all the rule needs.
CREATE FUNCTION catalog_grading_policy_test_frozen() RETURNS trigger
  LANGUAGE plpgsql SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_row grading_policies;
BEGIN
  v_row := CASE WHEN TG_OP = 'INSERT' THEN NEW ELSE OLD END;
  IF v_row.scope = 'TEST' AND current_user <> 'e9x_owner'
     AND NOT (SELECT r.rolsuper FROM pg_roles r WHERE r.rolname = current_user)
     AND EXISTS (SELECT 1 FROM test_versions v WHERE v.test_id = v_row.test_id AND v.status IN ('PUBLISHED', 'ARCHIVED')) THEN
    RAISE EXCEPTION 'the grading mode of a published test cannot change' USING ERRCODE = '23000';
  END IF;
  RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END $$;
CREATE TRIGGER grading_policies_test_frozen_trg BEFORE INSERT OR UPDATE OR DELETE ON grading_policies
  FOR EACH ROW EXECUTE FUNCTION catalog_grading_policy_test_frozen();

-- ---- (d) writers. EXAM scope keeps the AUTHOR of the exam as the writer of the header of a score table (it is the default table, English9x's); only the
-- TEST branch changes.
DROP POLICY score_tables_insert ON score_tables;
CREATE POLICY score_tables_insert ON score_tables FOR INSERT TO e9x_app WITH CHECK (
  e9x_workflow_in_context(owner_org_id)
  AND CASE scope
        WHEN 'EXAM' THEN e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
        ELSE e9x_workflow_has_role(owner_org_id, exam_code, ARRAY[CASE WHEN owner_org_id IS NULL THEN 'SYS_ADMIN' ELSE 'ORG_ADMIN' END])
      END);

-- grading policies: EXAM = SYS_ADMIN (English9x context), ORG = ORG_ADMIN of the organisation, TEST = the administrator of the test's owner. An ENTERPRISE
-- organisation has no teachers (spec 4): the rule "only AI" now covers its TEST policies as well as its ORG policy (an English9x row has no organisation).
DROP POLICY grading_policies_insert ON grading_policies;
CREATE POLICY grading_policies_insert ON grading_policies FOR INSERT TO e9x_app WITH CHECK (
  e9x_workflow_in_context(owner_org_id) AND updated_by = e9x_current_user()
  AND (owner_org_id IS NULL OR mode = 'AI' OR EXISTS (SELECT 1 FROM organizations o WHERE o.id = owner_org_id AND o.type = 'CENTER'))
  AND CASE scope
        WHEN 'EXAM' THEN e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['SYS_ADMIN'])
        WHEN 'ORG'  THEN e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['ORG_ADMIN'])
        ELSE e9x_workflow_has_role(owner_org_id, exam_code, ARRAY[CASE WHEN owner_org_id IS NULL THEN 'SYS_ADMIN' ELSE 'ORG_ADMIN' END])
      END);
DROP POLICY grading_policies_update ON grading_policies;
CREATE POLICY grading_policies_update ON grading_policies FOR UPDATE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id)
         AND CASE scope
               WHEN 'EXAM' THEN e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['SYS_ADMIN'])
               WHEN 'ORG'  THEN e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['ORG_ADMIN'])
               ELSE e9x_workflow_has_role(owner_org_id, exam_code, ARRAY[CASE WHEN owner_org_id IS NULL THEN 'SYS_ADMIN' ELSE 'ORG_ADMIN' END])
             END)
  WITH CHECK (updated_by = e9x_current_user()
              AND (owner_org_id IS NULL OR mode = 'AI' OR EXISTS (SELECT 1 FROM organizations o WHERE o.id = owner_org_id AND o.type = 'CENTER')));
DROP POLICY grading_policies_delete ON grading_policies;
CREATE POLICY grading_policies_delete ON grading_policies FOR DELETE TO e9x_app
  USING (e9x_workflow_in_context(owner_org_id)
         AND CASE scope
               WHEN 'ORG'  THEN e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['ORG_ADMIN'])
               WHEN 'TEST' THEN e9x_workflow_has_role(owner_org_id, exam_code, ARRAY[CASE WHEN owner_org_id IS NULL THEN 'SYS_ADMIN' ELSE 'ORG_ADMIN' END])
               ELSE false
             END);
