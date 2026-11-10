-- P2.5 score tables and grading skills (F15, F16). The tables and their role-and-exam policies are P2.1's (V202611100001); this adds the guards
-- that the CMS path of P2.3 / P2.4 (questions, strategy lessons) has and the catalog versions did not:
--
--  * a version is decided only through its open review (e9x_guard_version_decision of V202611110003): a REVIEWER of the exam could otherwise
--    set IN_REVIEW to PUBLISHED with a plain UPDATE and no review behind it (ledger 142);
--  * at most one open version (DRAFT / CHANGES_REQUESTED / IN_REVIEW) per table and per skill, so "the draft" is unambiguous;
--  * an AUTHOR edits or deletes only a version they created (ledger 141 default), not a colleague's draft.
-- TEST scope stays refused (score_tables_test_scope_refused_ck): P2.6 lifts it. Nothing here touches that CHECK.

CREATE TRIGGER score_table_versions_decision BEFORE UPDATE ON score_table_versions
  FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_decision('score_table');
CREATE TRIGGER grading_skill_versions_decision BEFORE UPDATE ON grading_skill_versions
  FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_decision('grading_skill');

CREATE UNIQUE INDEX score_table_versions_one_open_uq ON score_table_versions (score_table_id)
  WHERE status IN ('DRAFT', 'CHANGES_REQUESTED', 'IN_REVIEW');
CREATE UNIQUE INDEX grading_skill_versions_one_open_uq ON grading_skill_versions (grading_skill_id)
  WHERE status IN ('DRAFT', 'CHANGES_REQUESTED', 'IN_REVIEW');

ALTER POLICY score_table_versions_update_author ON score_table_versions
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
         AND created_by = e9x_current_user() AND status IN ('DRAFT', 'CHANGES_REQUESTED'))
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
              AND created_by = e9x_current_user() AND status IN ('DRAFT', 'CHANGES_REQUESTED', 'IN_REVIEW'));
ALTER POLICY score_table_versions_delete ON score_table_versions
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
         AND created_by = e9x_current_user() AND status IN ('DRAFT', 'CHANGES_REQUESTED'));
ALTER POLICY grading_skill_versions_update_author ON grading_skill_versions
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
         AND created_by = e9x_current_user() AND status IN ('DRAFT', 'CHANGES_REQUESTED'))
  WITH CHECK (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
              AND created_by = e9x_current_user() AND status IN ('DRAFT', 'CHANGES_REQUESTED', 'IN_REVIEW'));
ALTER POLICY grading_skill_versions_delete ON grading_skill_versions
  USING (e9x_workflow_in_context(owner_org_id) AND e9x_workflow_has_role(owner_org_id, exam_code, ARRAY['AUTHOR'])
         AND created_by = e9x_current_user() AND status IN ('DRAFT', 'CHANGES_REQUESTED'));
