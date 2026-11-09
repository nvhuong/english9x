-- Every table in schema public must have RLS enabled, except the allowlist (P1.4 enabled RLS on users and removed it from the list).
-- The allowlist is REFERENCE data without a tenant (exams and what the catalog hangs on it, P2.1): allowed without RLS only because the
-- application cannot write it, which this test checks table by table.
-- flyway_schema_history exists only when Flyway ran (RlsIT); it is migration bookkeeping owned by e9x_owner.
SET ROLE e9x_app;
DO $$
DECLARE missing text;
BEGIN
  SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO missing
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = 'public' AND c.relkind IN ('r','p')
     AND NOT c.relrowsecurity
     AND c.relname NOT IN ('exams','exam_sections','interaction_kinds','question_types','flyway_schema_history');
  ASSERT missing IS NULL, 'tables without RLS: ' || coalesce(missing,'');
  ASSERT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
           WHERE n.nspname = 'public' AND c.relkind IN ('r','p') AND c.relrowsecurity) >= 8,
         'expected the RLS tables to be present';
  -- a table is on the allowlist only if e9x_app and e9x_grader can read it and neither can write it
  SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO missing
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = 'public' AND c.relkind IN ('r','p') AND NOT c.relrowsecurity AND c.relname <> 'flyway_schema_history'
     AND (has_table_privilege('e9x_app', c.oid, 'INSERT') OR has_table_privilege('e9x_app', c.oid, 'UPDATE')
          OR has_table_privilege('e9x_app', c.oid, 'DELETE') OR has_table_privilege('e9x_grader', c.oid, 'INSERT')
          OR has_table_privilege('e9x_grader', c.oid, 'UPDATE') OR has_table_privilege('e9x_grader', c.oid, 'DELETE'));
  ASSERT missing IS NULL, 'tables without RLS that the application can write: ' || coalesce(missing,'');
END $$;
