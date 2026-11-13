-- P6.5 branch A — centre grading configuration (F50, spec §10.3, line 271).
--
-- P4.3 created org_grading_sides read-only (the queue reads the side through grading_queue_side); P6.5 opens the WRITE door, narrowly:
--   * only the ORG_ADMIN of a CENTER, in that centre's own context, may set (or change) which side grades the centre's learners;
--   * an ENTERPRISE has no teachers (spec §4) so it never gets a CENTER row — the application refuses grader=CENTER / mode TEACHER|BOTH
--     with a 400 before the database would, and the WITH CHECK below is the matching defence in depth (type = 'CENTER').
-- The grading MODE (AI / TEACHER / BOTH) of the centre's learners is kept in grading_policies (scope ORG), already writable by P2.1 and already
-- guarded so an ENTERPRISE ORG row can only be AI (grading_policies_insert/update, db-test 37). No new grant on grading_policies here.

CREATE POLICY org_grading_sides_insert ON org_grading_sides FOR INSERT TO e9x_app WITH CHECK (
  org_id = e9x_current_org() AND e9x_is_current_org_admin()
  AND EXISTS (SELECT 1 FROM organizations o WHERE o.id = org_id AND o.type = 'CENTER'));
CREATE POLICY org_grading_sides_update ON org_grading_sides FOR UPDATE TO e9x_app
  USING (org_id = e9x_current_org() AND e9x_is_current_org_admin())
  WITH CHECK (org_id = e9x_current_org() AND e9x_is_current_org_admin()
             AND EXISTS (SELECT 1 FROM organizations o WHERE o.id = org_id AND o.type = 'CENTER'));

GRANT INSERT (org_id, side), UPDATE (side, updated_at) ON org_grading_sides TO e9x_app;
