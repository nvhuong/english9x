-- P6.4 per-class report (F49). No table. The report reads, per learner of a class, what the other modules store; the only thing the request role cannot read is the
-- question-type statistic of ANOTHER person (item_type_stats is own-row Row-Level Security). This narrow SECURITY DEFINER function gives the organisation's staff the same
-- aggregate P4.4 computes for the learner themselves, and answers nothing unless progress_viewable (P4.5: the context's ORG_ADMIN / TEACHER and a member of that
-- organisation) agrees. Only the rows made in that organisation count (owner_org_id = p_org): a person's personal-context attempts are not the organisation's to see.
-- EXECUTE: e9x_app only.
CREATE FUNCTION results_type_rates_of(p_user uuid, p_org uuid)
  RETURNS TABLE (type_code text, type_name text, right_n integer, total integer)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT s.type_code, coalesce(t.name, s.type_code), (count(*) FILTER (WHERE s.correct))::int, count(*)::int
    FROM item_type_stats s LEFT JOIN question_types t ON t.code = s.type_code
   WHERE p_org IS NOT NULL AND progress_viewable(p_user, p_org) AND s.user_id = p_user AND s.owner_org_id = p_org
   GROUP BY s.type_code, t.name
   ORDER BY (count(*) FILTER (WHERE s.correct))::float8 / count(*), count(*) DESC, s.type_code $$;

REVOKE ALL ON FUNCTION results_type_rates_of(uuid, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION results_type_rates_of(uuid, uuid) TO e9x_app;
