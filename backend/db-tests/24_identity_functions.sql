-- P1.4: the narrow SECURITY DEFINER functions the pre-authentication flows go through.
-- Everything below runs as the request role e9x_app with NO user context.
CREATE ROLE outsider NOLOGIN;   -- a role that is not e9x_app
SET ROLE e9x_app;

-- hardening: fixed search_path, definer, not executable by PUBLIC or an outsider, executable by e9x_app
DO $$
DECLARE f record; n int := 0;
BEGIN
  FOR f IN SELECT p.oid, p.proname, p.prosecdef, p.proconfig FROM pg_proc p JOIN pg_namespace s ON s.oid = p.pronamespace
            WHERE s.nspname = 'public' AND p.proname LIKE 'auth\_%' LOOP
    n := n + 1;
    ASSERT f.prosecdef, f.proname || ' must be SECURITY DEFINER';
    ASSERT f.proconfig IS NOT NULL AND EXISTS (SELECT 1 FROM unnest(f.proconfig) c WHERE c LIKE 'search_path=pg_catalog%'),
           f.proname || ' must pin search_path';
    ASSERT has_function_privilege('e9x_app', f.oid, 'EXECUTE'), f.proname || ' executable by e9x_app';
    ASSERT NOT has_function_privilege('outsider', f.oid, 'EXECUTE'), f.proname || ' must not be executable by others';
  END LOOP;
  ASSERT n = 12, 'expected 12 auth functions, found ' || n;
END $$;

-- create_user: new, duplicate (case-insensitive), locale fallback
DO $$
DECLARE a uuid; b uuid; c uuid;
BEGIN
  a := auth_create_user('  Alice@Example.COM ', 'Alice', 'hash-a', 'en', now());
  ASSERT a IS NOT NULL, 'created';
  b := auth_create_user('alice@example.com', 'Impostor', 'hash-b', 'vi', now());
  ASSERT b IS NULL, 'duplicate email (case-insensitive) is refused with NULL, no error';
  c := auth_create_user('carol@example.com', 'Carol', 'hash-c', 'zz', now());
  ASSERT c IS NOT NULL;
  ASSERT (SELECT r_locale FROM auth_login_lookup('carol@example.com')) = 'vi', 'unknown locale falls back to vi';
  ASSERT NOT (SELECT r_email_verified FROM auth_login_lookup('carol@example.com')), 'always created unverified';
END $$;
-- (only the table owner can mark an account verified without a token: fixtures do it like the E2E seed API does)
RESET ROLE;
UPDATE users SET email_verified_at = now() WHERE email = 'carol@example.com';
SET ROLE e9x_app;

-- login lookup: exactly one row for exactly that email, none for others, never a wildcard
DO $$
BEGIN
  ASSERT (SELECT count(*) FROM auth_login_lookup('alice@example.com')) = 1;
  ASSERT (SELECT r_password_hash FROM auth_login_lookup('ALICE@example.com')) = 'hash-a';
  ASSERT (SELECT count(*) FROM auth_login_lookup('nobody@example.com')) = 0;
  ASSERT (SELECT count(*) FROM auth_login_lookup('%@example.com')) = 0, 'no pattern matching';
  ASSERT (SELECT count(*) FROM auth_login_lookup('')) = 0;
  ASSERT NOT (SELECT r_email_verified FROM auth_login_lookup('alice@example.com'));
END $$;

-- email verification: single use, expiring, unknown token
DO $$
DECLARE u uuid := (SELECT r_user_id FROM auth_login_lookup('alice@example.com'));
BEGIN
  PERFORM auth_email_verification_issue(u, '\xaa01', now() + interval '1 day', now());
  ASSERT (SELECT count(*) FROM auth_email_verification_consume('\xbb99', now())) = 0, 'unknown token';
  ASSERT (SELECT r_user_id FROM auth_email_verification_consume('\xaa01', now())) = u, 'valid token';
  ASSERT (SELECT r_email_verified FROM auth_login_lookup('alice@example.com')), 'user is verified now';
  ASSERT (SELECT count(*) FROM auth_email_verification_consume('\xaa01', now())) = 0, 'single use';
  -- expiry
  PERFORM auth_email_verification_issue(u, '\xaa02', now() + interval '1 hour', now());
  ASSERT (SELECT count(*) FROM auth_email_verification_consume('\xaa02', now() + interval '2 hours')) = 0, 'expired token';
  -- a newer token retires the older one
  PERFORM auth_email_verification_issue(u, '\xaa03', now() + interval '1 day', now());
  PERFORM auth_email_verification_issue(u, '\xaa04', now() + interval '1 day', now());
  ASSERT (SELECT count(*) FROM auth_email_verification_consume('\xaa03', now())) = 0, 'older token retired';
  ASSERT (SELECT count(*) FROM auth_email_verification_consume('\xaa04', now())) = 1, 'newest token works';
  -- resend: not for a verified account, not for an unknown address
  ASSERT (SELECT count(*) FROM auth_email_verification_issue_for_email('alice@example.com', '\xaa05', now() + interval '1 day', now())) = 0,
         'verified account: no resend';
  ASSERT (SELECT count(*) FROM auth_email_verification_issue_for_email('ghost@example.com', '\xaa06', now() + interval '1 day', now())) = 0,
         'unknown account: no resend row';
END $$;
DO $$
BEGIN
  PERFORM auth_create_user('dave@example.com', 'Dave', 'hash-d', 'vi', now());
  ASSERT (SELECT count(*) FROM auth_email_verification_issue_for_email('DAVE@example.com', '\xcc01', now() + interval '1 day', now())) = 1,
         'unverified account: resend issues a token';
  ASSERT (SELECT count(*) FROM auth_email_verification_consume('\xcc01', now())) = 1;
END $$;

-- refresh rotation: ROTATED, ROTATED_CONCURRENT inside the grace, REUSED outside it, family revoked
DO $$
DECLARE u uuid := (SELECT r_user_id FROM auth_login_lookup('alice@example.com')); fam uuid := gen_random_uuid(); t0 timestamptz := now();
BEGIN
  PERFORM auth_refresh_issue(u, fam, '\x1001', t0 + interval '30 days', t0, 1);
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\x9999', '\x1100', t0 + interval '30 days', t0, interval '10 seconds')) = 'INVALID', 'unknown token';
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\x1001', '\x1002', t0 + interval '30 days', t0, interval '10 seconds')) = 'ROTATED';
  -- two tabs: the old token arrives again 2s later
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\x1001', '\x1003', t0 + interval '30 days', t0 + interval '2 seconds', interval '10 seconds')) = 'ROTATED_CONCURRENT';
  -- both successors work
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\x1002', '\x1004', t0 + interval '30 days', t0 + interval '3 seconds', interval '10 seconds')) = 'ROTATED';
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\x1003', '\x1005', t0 + interval '30 days', t0 + interval '4 seconds', interval '10 seconds')) = 'ROTATED';
  -- replay of the first token long after the grace: the whole family dies
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\x1001', '\x1006', t0 + interval '30 days', t0 + interval '5 minutes', interval '10 seconds')) = 'REUSED';
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\x1004', '\x1007', t0 + interval '30 days', t0 + interval '5 minutes', interval '10 seconds')) = 'INVALID', 'family revoked';
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\x1005', '\x1008', t0 + interval '30 days', t0 + interval '5 minutes', interval '10 seconds')) = 'INVALID', 'family revoked';
  -- expiry
  PERFORM auth_refresh_issue(u, gen_random_uuid(), '\x2001', t0 + interval '1 day', t0, 1);
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\x2001', '\x2002', t0 + interval '30 days', t0 + interval '2 days', interval '10 seconds')) = 'INVALID', 'expired';
  -- logout revokes the family
  PERFORM auth_refresh_issue(u, gen_random_uuid(), '\x3001', t0 + interval '30 days', t0, 1);
  PERFORM auth_refresh_revoke_family('\x3001', t0);
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\x3001', '\x3002', t0 + interval '30 days', t0, interval '10 seconds')) = 'INVALID', 'logged out';
END $$;

-- password reset: single use, expiring, replaces the password, revokes every refresh token, retires other tokens
DO $$
DECLARE u uuid := (SELECT r_user_id FROM auth_login_lookup('alice@example.com')); fam uuid := gen_random_uuid(); t0 timestamptz := now();
BEGIN
  ASSERT (SELECT count(*) FROM auth_password_reset_issue('ghost@example.com', '\xdd01', t0 + interval '1 hour', t0)) = 0, 'unknown email: no row';
  PERFORM auth_refresh_issue(u, fam, '\x4001', t0 + interval '30 days', t0, 1);
  ASSERT (SELECT count(*) FROM auth_password_reset_issue('alice@example.com', '\xdd02', t0 + interval '1 hour', t0)) = 1;
  ASSERT (SELECT count(*) FROM auth_password_reset_confirm('\xdd99', 'new-hash', t0)) = 0, 'unknown token';
  ASSERT (SELECT count(*) FROM auth_password_reset_confirm('\xdd02', 'new-hash', t0 + interval '2 hours')) = 0, 'expired';
  PERFORM auth_password_reset_issue('alice@example.com', '\xdd03', t0 + interval '1 hour', t0);
  ASSERT (SELECT count(*) FROM auth_password_reset_confirm('\xdd02', 'new-hash', t0)) = 0, 'newer request retired the older token';
  ASSERT (SELECT r_user_id FROM auth_password_reset_confirm('\xdd03', 'new-hash', t0)) = u, 'valid';
  ASSERT (SELECT r_password_hash FROM auth_login_lookup('alice@example.com')) = 'new-hash';
  ASSERT (SELECT count(*) FROM auth_password_reset_confirm('\xdd03', 'other-hash', t0)) = 0, 'single use';
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\x4001', '\x4002', t0 + interval '30 days', t0, interval '10 seconds')) = 'INVALID', 'refresh tokens revoked';
END $$;

-- google: unverified refuses; creates; reuses the identity; links; neutralises an unverified local account
DO $$
DECLARE r record; u uuid; fam uuid := gen_random_uuid(); t0 timestamptz := now();
BEGIN
  SELECT * INTO r FROM auth_oauth_login('google','sub-x','newbie@example.com', false, 'N', 'vi', t0);
  ASSERT r.r_outcome = 'EMAIL_NOT_VERIFIED' AND r.r_user_id IS NULL, 'unverified IdP email: refused';
  ASSERT (SELECT count(*) FROM auth_login_lookup('newbie@example.com')) = 0, 'and no account was created';

  SELECT * INTO r FROM auth_oauth_login('google','sub-x','newbie@example.com', true, 'Newbie', 'en', t0);
  ASSERT r.r_outcome = 'CREATED' AND r.r_user_id IS NOT NULL;
  ASSERT (SELECT r_email_verified FROM auth_login_lookup('newbie@example.com')) AND (SELECT r_password_hash FROM auth_login_lookup('newbie@example.com')) IS NULL;
  u := r.r_user_id;
  SELECT * INTO r FROM auth_oauth_login('google','sub-x','changed@example.com', true, 'Newbie', 'en', t0);
  ASSERT r.r_outcome = 'EXISTING' AND r.r_user_id = u, 'second login reuses the account (even if the email changed)';

  -- verified local account with the same address: linked, password kept
  SELECT * INTO r FROM auth_oauth_login('google','sub-c','carol@example.com', true, 'Carol', 'vi', t0);
  ASSERT r.r_outcome = 'LINKED';
  -- IdP does not assert the email: an existing account is NOT linked (no takeover)
  SELECT * INTO r FROM auth_oauth_login('google','sub-evil','carol@example.com', false, 'Mallory', 'vi', t0);
  ASSERT r.r_outcome = 'EMAIL_NOT_VERIFIED';
  ASSERT NOT EXISTS (SELECT 1 FROM auth_identities WHERE subject = 'sub-evil'), 'nothing linked';

  -- pre-hijack: an UNVERIFIED local account (attacker password + live session) is neutralised on link
  PERFORM auth_create_user('victim@example.com', 'Attacker', 'attacker-hash', 'vi', t0);
  u := (SELECT r_user_id FROM auth_login_lookup('victim@example.com'));
  PERFORM auth_refresh_issue(u, fam, '\x5001', t0 + interval '30 days', t0, 1);
  PERFORM auth_email_verification_issue(u, '\x5002', t0 + interval '1 day', t0);
  SELECT * INTO r FROM auth_oauth_login('google','sub-victim','victim@example.com', true, 'Victim', 'vi', t0);
  ASSERT r.r_outcome = 'LINKED_NEUTRALISED' AND r.r_user_id = u;
  ASSERT (SELECT r_password_hash FROM auth_login_lookup('victim@example.com')) IS NULL, 'attacker password removed';
  ASSERT (SELECT r_email_verified FROM auth_login_lookup('victim@example.com'));
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\x5001', '\x5003', t0 + interval '30 days', t0, interval '10 seconds')) = 'INVALID', 'attacker session revoked';
  ASSERT (SELECT count(*) FROM auth_email_verification_consume('\x5002', t0)) = 0, 'pending verification retired';
END $$;

-- purge
DO $$
DECLARE u uuid := (SELECT r_user_id FROM auth_login_lookup('alice@example.com')); t0 timestamptz := now();
BEGIN
  PERFORM auth_refresh_issue(u, gen_random_uuid(), '\x6001', t0 - interval '40 days', t0 - interval '70 days',
                             (SELECT r_credentials_version FROM auth_login_lookup('alice@example.com')));
  ASSERT auth_purge_expired() >= 1, 'old expired token removed';
  ASSERT (SELECT r_outcome FROM auth_refresh_rotate('\x6001', '\x6002', t0 + interval '30 days', t0, interval '10 seconds')) = 'INVALID';
END $$;

-- an outsider role cannot call them
RESET ROLE;
SET ROLE outsider;
DO $$ BEGIN
  BEGIN
    PERFORM auth_login_lookup('alice@example.com');
    RAISE EXCEPTION 'outsider must not execute auth_login_lookup';
  EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $$;
