-- Every table in schema public must have RLS enabled, except the allowlist.
SET ROLE e9x_app;
DO $$
DECLARE missing text;
BEGIN
  SELECT string_agg(c.relname, ', ' ORDER BY c.relname) INTO missing
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
   WHERE n.nspname = 'public' AND c.relkind IN ('r','p')
     AND NOT c.relrowsecurity
     AND c.relname NOT IN ('users','exams');
  ASSERT missing IS NULL, 'tables without RLS: ' || coalesce(missing,'');
  ASSERT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
           WHERE n.nspname = 'public' AND c.relkind IN ('r','p') AND c.relrowsecurity) >= 8,
         'expected the RLS tables to be present';
END $$;
