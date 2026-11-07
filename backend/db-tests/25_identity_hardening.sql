-- P1.4 fix round: what a compromised statement of the request role can and cannot do through the definer functions, and
-- how they are pinned. Collects every problem instead of stopping at the first.
SET ROLE e9x_app;
DO $$
DECLARE
  problems text[] := '{}';
  f record;
  n int;
  t0 timestamptz := now();
  u uuid;
  blank text;
BEGIN
  -- every definer in the schema (not only auth_*) has pg_temp pinned LAST, so a temp object can never shadow anything
  FOR f IN SELECT p.proname, p.proconfig FROM pg_proc p JOIN pg_namespace s ON s.oid = p.pronamespace
            WHERE s.nspname = 'public' AND p.prosecdef LOOP
    IF f.proconfig IS NULL OR NOT 'search_path=pg_catalog, public, pg_temp' = ANY (f.proconfig) THEN
      problems := array_append(problems, (format('%s: search_path is %s', f.proname, f.proconfig))::text);
    END IF;
  END LOOP;

  -- no test capability in production functions
  IF to_regprocedure('auth_create_user(text,text,text,text,timestamptz,boolean,timestamptz)') IS NOT NULL THEN
    problems := array_append(problems, ('auth_create_user still takes p_email_verified')::text);
  END IF;
  IF to_regprocedure('auth_purge_expired(timestamptz,interval)') IS NOT NULL THEN
    problems := array_append(problems, ('auth_purge_expired still takes the time and the age from its caller')::text);
  END IF;

  -- a NULL consent or a blank password hash is refused inside the function
  BEGIN
    PERFORM auth_create_user('forged-1@example.com', 'F', '$2a$10$hash', 'vi', NULL::timestamptz);
    problems := array_append(problems, ('auth_create_user accepted a NULL consent')::text);
  EXCEPTION WHEN check_violation THEN NULL;
            WHEN undefined_function THEN problems := array_append(problems, ('auth_create_user(5 args) does not exist')::text);
  END;
  FOREACH blank IN ARRAY ARRAY['', '   '] LOOP
    BEGIN
      PERFORM auth_create_user('forged-2@example.com', 'F', blank, 'vi', t0);
      problems := array_append(problems, (format('auth_create_user accepted the hash "%s"', blank))::text);
    EXCEPTION WHEN check_violation THEN NULL;
              WHEN undefined_function THEN NULL; -- reported above
    END;
  END LOOP;
  BEGIN
    PERFORM auth_create_user('forged-3@example.com', 'F', NULL, 'vi', t0);
    problems := array_append(problems, ('auth_create_user accepted a NULL hash')::text);
  EXCEPTION WHEN check_violation THEN NULL;
            WHEN undefined_function THEN NULL;
  END;

  -- purge decides by itself: a live token survives whatever the caller says
  BEGIN
    u := auth_create_user('purge@example.com', 'P', '$2a$10$hash', 'vi', t0);
    PERFORM auth_refresh_issue(u, gen_random_uuid(), '\xf001', t0 + interval '30 days', t0, 1);
    PERFORM auth_refresh_issue(u, gen_random_uuid(), '\xf002', t0 - interval '8 days', t0 - interval '38 days', 1);
    n := auth_purge_expired();
    IF n <> 1 THEN problems := array_append(problems, (format('purge removed %s tokens, expected the 1 stale one', n))::text); END IF;
    IF (SELECT r_outcome FROM auth_refresh_rotate('\xf001', '\xf003', t0 + interval '30 days', t0, interval '10 seconds')) <> 'ROTATED' THEN
      problems := array_append(problems, ('purge removed a live token')::text);
    END IF;
  EXCEPTION WHEN undefined_function THEN problems := array_append(problems, ('auth_purge_expired() / auth_refresh_issue(6 args) do not exist')::text);
  END;

  -- the version check of refresh issuing
  BEGIN
    u := auth_create_user('version@example.com', 'V', '$2a$10$hash', 'vi', t0);
    IF (SELECT r_credentials_version FROM auth_login_lookup('version@example.com')) IS DISTINCT FROM 1 THEN
      problems := array_append(problems, ('a new account is at credentials version 1')::text);
    END IF;
    IF auth_refresh_issue(u, gen_random_uuid(), '\xf101', t0 + interval '30 days', t0, 2) <> 'STALE' THEN
      problems := array_append(problems, ('a token was issued for another credentials version')::text);
    END IF;
    IF auth_refresh_issue(gen_random_uuid(), gen_random_uuid(), '\xf102', t0 + interval '30 days', t0, 1) <> 'STALE' THEN
      problems := array_append(problems, ('a token was issued for an unknown user')::text);
    END IF;
    IF auth_refresh_issue(u, gen_random_uuid(), '\xf103', t0 + interval '30 days', t0, 1) <> 'ISSUED' THEN
      problems := array_append(problems, ('the current version must work')::text);
    END IF;
    PERFORM auth_password_reset_issue('version@example.com', '\xf104', t0 + interval '1 hour', t0);
    PERFORM auth_password_reset_confirm('\xf104', 'new-hash', t0);
    IF (SELECT r_credentials_version FROM auth_login_lookup('version@example.com')) IS DISTINCT FROM 2 THEN
      problems := array_append(problems, ('a password change bumps the version')::text);
    END IF;
    IF auth_refresh_issue(u, gen_random_uuid(), '\xf105', t0 + interval '30 days', t0, 1) <> 'STALE' THEN
      problems := array_append(problems, ('a login that verified the old password was issued a token')::text);
    END IF;
  EXCEPTION WHEN undefined_function OR undefined_column THEN problems := array_append(problems, ('credentials version missing: ' || SQLERRM)::text);
  END;

  -- the version is not readable through the table
  IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'users'::regclass AND attname = 'credentials_version') THEN
    problems := array_append(problems, 'users.credentials_version does not exist'::text);
  ELSIF has_column_privilege('e9x_app', 'users', 'credentials_version', 'SELECT') THEN
    problems := array_append(problems, ('credentials_version is readable by e9x_app')::text);
  END IF;

  IF cardinality(problems) > 0 THEN
    RAISE EXCEPTION E'%', array_to_string(problems, E'\n');
  END IF;
END $$;

-- side branches of a replay inside the grace do not survive past it (time is controlled here through p_now)
DO $$
DECLARE u uuid; fam uuid := gen_random_uuid(); t0 timestamptz := now();
BEGIN
  u := auth_create_user('branch@example.com', 'B', '$2a$10$hash', 'vi', t0);
  PERFORM auth_refresh_issue(u, fam, '\xe001', t0 + interval '30 days', t0, 1);
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\xe001', '\xe002', t0 + interval '30 days', t0, interval '10 seconds')) = 'ROTATED';
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\xe001', '\xe003', t0 + interval '30 days', t0 + interval '5 seconds', interval '10 seconds')) = 'ROTATED_CONCURRENT';
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\xe002', '\xe004', t0 + interval '30 days', t0 + interval '6 seconds', interval '10 seconds')) = 'ROTATED';
  -- inside the grace of \xe002's rotation the side branch still counts as a harmless race ...
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\xe003', '\xe005', t0 + interval '30 days', t0 + interval '7 seconds', interval '10 seconds')) = 'ROTATED';
  -- ... and past it, any token at or behind what has moved on is a replay: the whole family goes
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\xe005', '\xe006', t0 + interval '30 days', t0 + interval '30 seconds', interval '10 seconds')) = 'ROTATED',
         'the newest generation is still the head';
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\xe004', '\xe007', t0 + interval '30 days', t0 + interval '60 seconds', interval '10 seconds')) = 'REUSED',
         'a token of the same generation as one rotated long ago';
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\xe006', '\xe008', t0 + interval '30 days', t0 + interval '61 seconds', interval '10 seconds')) = 'INVALID',
         'nothing of the family is left, not even the head';
END $$;
