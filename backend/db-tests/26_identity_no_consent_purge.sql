-- P1.4 fix round 2: accounts nobody agreed to do not stay forever. As the table owner for the backdating, as e9x_app for the call.
INSERT INTO users (id, email, display_name, email_verified_at, created_at) VALUES
  ('a0000000-0000-0000-0000-000000000001', 'abandoned@example.com', 'Google Name', now(), now() - interval '8 days'),
  ('a0000000-0000-0000-0000-000000000002', 'fresh@example.com',     'Google Name', now(), now() - interval '6 days'),
  ('a0000000-0000-0000-0000-000000000003', 'agreed@example.com',    'Google Name', now(), now() - interval '90 days'),
  ('a0000000-0000-0000-0000-000000000004', 'active@example.com',    'Google Name', now(), now() - interval '90 days'),
  ('a0000000-0000-0000-0000-000000000005', 'not-google@example.com', NULL,         NULL,  now() - interval '90 days');
UPDATE users SET consent_at = now() - interval '89 days' WHERE email = 'agreed@example.com';
INSERT INTO auth_identities (user_id, provider, subject, email)
  SELECT id, 'google', 'sub-' || email, email FROM users WHERE email <> 'not-google@example.com';
-- user activity: anything in another table that refers to the user (here a membership)
INSERT INTO organizations (id, name, type) VALUES ('c0000000-0000-0000-0000-00000000000c', 'Org', 'CENTER');
INSERT INTO org_memberships (org_id, user_id) VALUES ('c0000000-0000-0000-0000-00000000000c', 'a0000000-0000-0000-0000-000000000004');

SET ROLE e9x_app;
DO $$
BEGIN
  -- the token purge never touches accounts, which is why this one is needed
  PERFORM auth_purge_expired();
  ASSERT (SELECT count(*) FROM auth_login_lookup('abandoned@example.com')) = 1, 'token housekeeping leaves accounts alone';

  ASSERT auth_purge_no_consent(interval '7 days') = 1, 'exactly the abandoned account';
  ASSERT (SELECT count(*) FROM auth_login_lookup('abandoned@example.com')) = 0, 'gone';
  ASSERT (SELECT count(*) FROM auth_login_lookup('fresh@example.com')) = 1, 'younger than the retention: kept';
  ASSERT (SELECT count(*) FROM auth_login_lookup('agreed@example.com')) = 1, 'consent on record: kept';
  ASSERT (SELECT count(*) FROM auth_login_lookup('active@example.com')) = 1, 'holds user activity: kept';
  ASSERT (SELECT count(*) FROM auth_login_lookup('not-google@example.com')) = 1, 'not the leftover of a Google sign-in: not ours to judge';
  -- a caller cannot shorten the wait to wipe people in the middle of the step: at least a day, whatever it passes
  ASSERT auth_purge_no_consent(interval '0') = 1, 'fresh is older than the floor';
  PERFORM auth_oauth_login('google', 'sub-now', 'just-now@example.com', true, 'N', 'vi', now());
  ASSERT auth_purge_no_consent(interval '0') = 0, 'someone who signed in just now is safe';
  ASSERT (SELECT count(*) FROM auth_login_lookup('just-now@example.com')) = 1;
END $$;

-- neutralised accounts: the clock starts at the neutralisation (the owner is on the consent step NOW, however old the
-- attacker's registration is); activity and later consent keep them; without those they go like any other
RESET ROLE;
INSERT INTO users (id, email, display_name, password_hash, consent_at, created_at) VALUES
  ('b0000000-0000-0000-0000-000000000001', 'n-recent@example.com',  'A', 'h', now(), now() - interval '90 days'),
  ('b0000000-0000-0000-0000-000000000002', 'n-old@example.com',     'A', 'h', now(), now() - interval '90 days'),
  ('b0000000-0000-0000-0000-000000000003', 'n-active@example.com',  'A', 'h', now(), now() - interval '90 days'),
  ('b0000000-0000-0000-0000-000000000004', 'n-agreed@example.com',  'A', 'h', now(), now() - interval '90 days');
INSERT INTO org_memberships (org_id, user_id) VALUES ('c0000000-0000-0000-0000-00000000000c', 'b0000000-0000-0000-0000-000000000003');
SET ROLE e9x_app;
DO $$
DECLARE r record;
BEGIN
  FOR r IN SELECT unnest(ARRAY['n-recent','n-old','n-active','n-agreed']) AS n LOOP
    ASSERT (SELECT r_outcome FROM auth_oauth_login('google', 'sub-' || r.n, r.n || '@example.com', true, 'Owner', 'vi', now())) = 'LINKED_NEUTRALISED';
  END LOOP;
  ASSERT (SELECT r_first_registration FROM auth_consent_record('b0000000-0000-0000-0000-000000000004', now())) = false,
         'a neutralised account was registered long ago';
END $$;
RESET ROLE;
UPDATE users SET neutralised_at = now() - interval '8 days'
 WHERE email IN ('n-old@example.com', 'n-active@example.com', 'n-agreed@example.com');
SET ROLE e9x_app;
DO $$
BEGIN
  ASSERT auth_purge_no_consent(interval '7 days') = 1, 'only the old one with nothing to its name';
  ASSERT (SELECT count(*) FROM auth_login_lookup('n-recent@example.com')) = 1, 'neutralised just now: kept, however old the row';
  ASSERT (SELECT count(*) FROM auth_login_lookup('n-old@example.com')) = 0, 'abandoned after neutralisation: gone';
  ASSERT (SELECT count(*) FROM auth_login_lookup('n-active@example.com')) = 1, 'holds activity: kept';
  ASSERT (SELECT count(*) FROM auth_login_lookup('n-agreed@example.com')) = 1, 'consent recorded later: kept';
END $$;
-- first consent of a Google account is its registration; asking again is not
DO $$
DECLARE u uuid;
BEGIN
  SELECT r_user_id INTO u FROM auth_oauth_login('google', 'sub-first', 'first@example.com', true, 'F', 'vi', now());
  ASSERT (SELECT r_first_registration FROM auth_consent_record(u, now())) = true;
  ASSERT (SELECT r_email FROM auth_consent_record(u, now())) = 'first@example.com';
  ASSERT (SELECT r_first_registration FROM auth_consent_record(u, now())) = false;
END $$;
