-- P1.4 identity: credentials on users, external identities, refresh / verification / reset tokens, and Row-Level
-- Security for users.
--
-- Ordering note: this file sorts before the already merged V202611070002__jobs.sql. That is fine because no database
-- has been deployed yet (a fresh database applies both in order; nothing here depends on or is depended on by jobs).
--
-- Model (the rulings of P1.4):
--  * The request role e9x_app has NO table privileges on the three token tables and cannot INSERT/DELETE users.
--    Everything that happens before there is an authenticated user (register, login lookup, verify, reset, refresh,
--    Google sign-in) goes through the narrow SECURITY DEFINER functions below: fixed search_path, exactly the columns
--    that flow needs, EXECUTE granted to e9x_app only (REVOKE FROM PUBLIC). The functions are owned by the migration
--    user (e9x_owner in the real stack), who owns the tables and is not subject to their RLS.
--  * Account creation is the function auth_create_user, not a GUC flag such as app.registration = 'on': a flag the
--    application role can set itself protects nothing against a compromised statement, a function it can only call
--    does. users has no INSERT policy, so a direct INSERT by e9x_app is refused.
--  * Tokens are stored as SHA-256 digests (bytea) of 256-bit random values; the raw token only exists in the mail /
--    the client. Time is passed in by the application (p_now) so tests with a controllable clock see the same time.
--
-- Concurrency: credentials and sessions serialise on the users row (READ COMMITTED; every function here is VOLATILE, so
-- each statement inside takes a fresh snapshot, and that is relied on below):
--  * Whatever MINTS a refresh token (auth_refresh_issue, auth_refresh_rotate) holds FOR SHARE on the user first and
--    keeps it to the end of its transaction.
--  * Whatever ENDS sessions (password reset, neutralisation or linking in auth_oauth_login, family revocation on reuse
--    or logout) takes FOR UPDATE on the user first and revokes in a LATER statement. The lock waits for the minters in
--    progress; the later statement's snapshot sees what they committed. A statement that both waited and revoked would
--    use its old snapshot and miss the row they inserted.
--  * users.credentials_version covers what no lock can: a login checks bcrypt OUTSIDE any transaction, so the version it
--    read goes with it into auth_refresh_issue (and through a one-time code), and a change in between is refused.
--  * Lock order, everywhere, so that no deadlock path exists: users, then password_resets, email_verifications,
--    refresh_tokens. Functions that must find the user from a token read it WITHOUT a lock first (user_id never changes).
--    A shared lock is never upgraded (two holders upgrading would deadlock): auth_refresh_rotate gives its locks back
--    (subtransaction rollback) and starts again with FOR UPDATE when it finds a replay.
--
-- What the request role can still do. These functions narrow what SQL can touch; they do not make a compromised
-- statement of e9x_app harmless. Through them, such a statement CAN: read any user's bcrypt hash by email
-- (auth_login_lookup, because Java performs bcrypt; verifying inside the database would take pgcrypto and passwords in SQL
-- text, so it is not done), issue and confirm a password reset for any address, mint refresh tokens for any user whose
-- credentials version it has read, record consent for any user, and pass any p_now. It can NOT: read token digests or raw tokens, read
-- users.password_hash through the table, create a verified account, create an account without consent or a password hash,
-- or delete live tokens. Protection against SQL injection is parameterised statements, not this list.

ALTER TABLE users
  ADD COLUMN password_hash text,                      -- bcrypt; NULL for accounts that only use Google
  ADD COLUMN email_verified_at timestamptz,
  ADD COLUMN consent_at timestamptz,                  -- Decree 13/2023: when the person agreed; NULL: no token is issued
  ADD COLUMN locale text NOT NULL DEFAULT 'vi' CHECK (locale IN ('vi','en')),
  ADD COLUMN credentials_version bigint NOT NULL DEFAULT 1; -- bumped whenever existing sign-ins must stop being honoured

CREATE TABLE auth_identities (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider text NOT NULL CHECK (provider IN ('google')),
  subject text NOT NULL,
  email text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT auth_identities_subject_uq UNIQUE (provider, subject)
);
CREATE INDEX auth_identities_user_idx ON auth_identities (user_id);

CREATE TABLE refresh_tokens (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  family_id uuid NOT NULL,
  token_hash bytea NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  rotated_at timestamptz,
  revoked_at timestamptz,
  generation integer NOT NULL DEFAULT 0  -- rotations since the sign-in; see auth_refresh_rotate
);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);

CREATE TABLE email_verifications (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash bytea NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  used_at timestamptz
);
CREATE INDEX email_verifications_user_idx ON email_verifications (user_id);

CREATE TABLE password_resets (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash bytea NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  used_at timestamptz
);
CREATE INDEX password_resets_user_idx ON password_resets (user_id);

-- Row-Level Security --------------------------------------------------------------------------------------------
ALTER TABLE users              ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_identities    ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens     ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_verifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE password_resets    ENABLE ROW LEVEL SECURITY;

-- SELECT: yourself, or a member of the organisation of the current context, or English9x staff.
CREATE POLICY users_select ON users FOR SELECT USING (
  id = e9x_current_user()
  OR (e9x_current_org() IS NOT NULL
      AND EXISTS (SELECT 1 FROM org_memberships m
                   WHERE m.user_id = users.id AND m.org_id = e9x_current_org()))
  OR e9x_is_staff());
-- UPDATE: yourself only (and only the columns granted below).
CREATE POLICY users_update ON users FOR UPDATE
  USING (id = e9x_current_user()) WITH CHECK (id = e9x_current_user());
-- No INSERT / DELETE policy: accounts are created by auth_create_user only.

-- You may see which sign-in providers are linked to YOU. Nobody writes this table directly.
CREATE POLICY auth_identities_select ON auth_identities FOR SELECT USING (user_id = e9x_current_user());
-- refresh_tokens / email_verifications / password_resets: RLS on, no policy and no grant: only the functions below.

-- Privileges ----------------------------------------------------------------------------------------------------
REVOKE ALL ON users FROM e9x_app;
GRANT SELECT (id, email, display_name, created_at, email_verified_at, locale) ON users TO e9x_app;  -- never password_hash
GRANT UPDATE (display_name, locale) ON users TO e9x_app;
GRANT SELECT ON auth_identities TO e9x_app;

-- Functions -----------------------------------------------------------------------------------------------------
-- The merged org baseline has one definer of its own; same pin (pg_temp last), without rewriting a merged file.
ALTER FUNCTION role_grants_guard_enterprise() SET search_path = pg_catalog, public, pg_temp;

-- Account creation with email + password. Returns the new id, or NULL when the email (case-insensitive) is already taken.
-- Always unverified, always with a recorded consent and a password hash. (A Google account comes from auth_oauth_login.)
CREATE FUNCTION auth_create_user(
  p_email text, p_display_name text, p_password_hash text, p_locale text, p_consent_at timestamptz) RETURNS uuid
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE v_id uuid;
BEGIN
  IF p_consent_at IS NULL THEN
    RAISE EXCEPTION 'auth_create_user: consent is required' USING ERRCODE = 'check_violation';
  END IF;
  IF p_password_hash IS NULL OR btrim(p_password_hash) = '' THEN
    RAISE EXCEPTION 'auth_create_user: a password hash is required' USING ERRCODE = 'check_violation';
  END IF;
  INSERT INTO users (email, display_name, password_hash, locale, consent_at)
  VALUES (lower(btrim(p_email)), p_display_name, p_password_hash,
          CASE WHEN p_locale IN ('vi','en') THEN p_locale ELSE 'vi' END, p_consent_at)
  ON CONFLICT ((lower(email))) DO NOTHING
  RETURNING id INTO v_id;
  RETURN v_id;
END $$;

-- Login (and duplicate-registration) lookup: exactly one email, exactly the columns the flow needs.
CREATE FUNCTION auth_login_lookup(p_email text)
  RETURNS TABLE (r_user_id uuid, r_password_hash text, r_email_verified boolean, r_display_name text, r_locale text, r_credentials_version bigint)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT u.id, u.password_hash, u.email_verified_at IS NOT NULL, u.display_name, u.locale, u.credentials_version
    FROM users u WHERE lower(u.email) = lower(btrim(p_email)) LIMIT 1
$$;

-- Email verification -------------------------------------------------------------------------------------------
CREATE FUNCTION auth_email_verification_issue(p_user_id uuid, p_token_hash bytea, p_expires timestamptz, p_now timestamptz)
  RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  PERFORM 1 FROM users WHERE id = p_user_id FOR UPDATE;
  UPDATE email_verifications SET used_at = p_now WHERE user_id = p_user_id AND used_at IS NULL;
  INSERT INTO email_verifications (user_id, token_hash, expires_at, created_at) VALUES (p_user_id, p_token_hash, p_expires, p_now);
END $$;

-- Resend: only for an existing, still unverified account. Returns no row otherwise (the caller answers the same).
CREATE FUNCTION auth_email_verification_issue_for_email(p_email text, p_token_hash bytea, p_expires timestamptz, p_now timestamptz)
  RETURNS TABLE (r_user_id uuid, r_display_name text, r_locale text)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE u users%ROWTYPE;
BEGIN
  SELECT * INTO u FROM users WHERE lower(email) = lower(btrim(p_email)) AND email_verified_at IS NULL FOR UPDATE;
  IF NOT FOUND THEN RETURN; END IF;
  UPDATE email_verifications SET used_at = p_now WHERE user_id = u.id AND used_at IS NULL;
  INSERT INTO email_verifications (user_id, token_hash, expires_at, created_at) VALUES (u.id, p_token_hash, p_expires, p_now);
  r_user_id := u.id; r_display_name := u.display_name; r_locale := u.locale;
  RETURN NEXT;
END $$;

-- Single use, expiring. Returns the user (and the stored digest for the caller's constant-time re-check), or no row.
-- Sessions and password are left alone: in the normal case the person who clicks is the person who registered. A click
-- cannot prove that (see LINKED in auth_oauth_login for what happens to a registrant who was not the mailbox owner).
CREATE FUNCTION auth_email_verification_consume(p_token_hash bytea, p_now timestamptz)
  RETURNS TABLE (r_user_id uuid, r_stored_hash bytea)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE v_user uuid; v_hash bytea;
BEGIN
  SELECT v.user_id INTO v_user FROM email_verifications v WHERE v.token_hash = p_token_hash;
  IF v_user IS NULL THEN RETURN; END IF;
  PERFORM 1 FROM users WHERE id = v_user FOR UPDATE;
  UPDATE email_verifications v SET used_at = p_now
   WHERE v.token_hash = p_token_hash AND v.used_at IS NULL AND v.expires_at > p_now
  RETURNING v.token_hash INTO v_hash;
  IF v_hash IS NULL THEN RETURN; END IF;
  UPDATE users SET email_verified_at = coalesce(email_verified_at, p_now) WHERE id = v_user;
  r_user_id := v_user; r_stored_hash := v_hash;
  RETURN NEXT;
END $$;

-- Password reset -----------------------------------------------------------------------------------------------
CREATE FUNCTION auth_password_reset_issue(p_email text, p_token_hash bytea, p_expires timestamptz, p_now timestamptz)
  RETURNS TABLE (r_user_id uuid, r_display_name text, r_locale text)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE u users%ROWTYPE;
BEGIN
  SELECT * INTO u FROM users WHERE lower(email) = lower(btrim(p_email)) FOR UPDATE;
  IF NOT FOUND THEN RETURN; END IF;
  UPDATE password_resets SET used_at = p_now WHERE user_id = u.id AND used_at IS NULL;
  INSERT INTO password_resets (user_id, token_hash, expires_at, created_at) VALUES (u.id, p_token_hash, p_expires, p_now);
  r_user_id := u.id; r_display_name := u.display_name; r_locale := u.locale;
  RETURN NEXT;
END $$;

-- Sets the new password, retires the token and every other reset token, and revokes EVERY refresh token of the user,
-- including those being minted right now (see Concurrency above); a login still holding the old version is refused.
-- Following the emailed link also proves the mailbox, so the address counts as verified.
CREATE FUNCTION auth_password_reset_confirm(p_token_hash bytea, p_new_password_hash text, p_now timestamptz)
  RETURNS TABLE (r_user_id uuid, r_stored_hash bytea)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE v_user uuid; v_hash bytea;
BEGIN
  SELECT r.user_id INTO v_user FROM password_resets r WHERE r.token_hash = p_token_hash;
  IF v_user IS NULL THEN RETURN; END IF;
  PERFORM 1 FROM users WHERE id = v_user FOR UPDATE;
  UPDATE password_resets r SET used_at = p_now
   WHERE r.token_hash = p_token_hash AND r.used_at IS NULL AND r.expires_at > p_now
  RETURNING r.token_hash INTO v_hash;
  IF v_hash IS NULL THEN RETURN; END IF;
  UPDATE users SET password_hash = p_new_password_hash, email_verified_at = coalesce(email_verified_at, p_now),
                   credentials_version = credentials_version + 1 WHERE id = v_user;
  UPDATE password_resets SET used_at = p_now WHERE user_id = v_user AND used_at IS NULL;
  UPDATE email_verifications SET used_at = p_now WHERE user_id = v_user AND used_at IS NULL;
  UPDATE refresh_tokens SET revoked_at = p_now WHERE user_id = v_user AND revoked_at IS NULL;
  r_user_id := v_user; r_stored_hash := v_hash;
  RETURN NEXT;
END $$;

-- Refresh tokens -----------------------------------------------------------------------------------------------
-- A new sign-in. p_credentials_version is what the caller verified against: when the credentials changed since (or
-- the user is gone) nothing is issued and the answer is STALE. A user with no consent on record gets CONSENT_REQUIRED and
-- nothing is issued either. Otherwise ISSUED.
CREATE FUNCTION auth_refresh_issue(p_user_id uuid, p_family_id uuid, p_token_hash bytea, p_expires timestamptz, p_now timestamptz,
                                   p_credentials_version bigint)
  RETURNS text LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE v_version bigint; v_consent timestamptz;
BEGIN
  SELECT credentials_version, consent_at INTO v_version, v_consent FROM users WHERE id = p_user_id FOR SHARE;
  IF NOT FOUND OR v_version IS DISTINCT FROM p_credentials_version THEN
    RETURN 'STALE';
  END IF;
  IF v_consent IS NULL THEN
    RETURN 'CONSENT_REQUIRED';
  END IF;
  INSERT INTO refresh_tokens (user_id, family_id, token_hash, expires_at, created_at)
  VALUES (p_user_id, p_family_id, p_token_hash, p_expires, p_now);
  RETURN 'ISSUED';
END $$;

-- Rotation. Outcomes:
--   INVALID            unknown, expired or revoked token, or a user with no consent on record: nothing changes
--   ROTATED            first use: the token is marked rotated and a successor is issued in the same family
--   ROTATED_CONCURRENT the token was already rotated, but less than p_grace ago: two tabs refreshed together. A second
--                      successor is issued in the same family (both stay valid) and nothing is revoked
--   REUSED             a replay of a stolen / stale token, see below. The WHOLE family is revoked
-- What counts as a replay. Tolerating two tabs inside the grace means one token can have two successors, and a thief who
-- replays inside the grace holds one of them. So a branch may not outlive the grace undetected: each token knows its
-- generation, and using a token is a replay when it was itself rotated longer than p_grace ago, OR when ANOTHER token of
-- the family at the same or a later generation was. The real client keeps one token (the last stored), so past the
-- grace it never presents a token behind one already moved on. Whichever branch comes second, the family dies. What
-- remains: inside the grace the replay works, and the access token it got lives until it expires.
-- The first pass holds the user FOR SHARE. A replay needs FOR UPDATE to revoke (see Concurrency at the top) and a shared
-- lock must not be upgraded, so it raises to give back what the subtransaction locked and starts over exclusively.
-- Revocation is committed because this function returns (rather than raises) for REUSED.
CREATE FUNCTION auth_refresh_rotate(p_old_hash bytea, p_new_hash bytea, p_new_expires timestamptz, p_now timestamptz, p_grace interval)
  RETURNS TABLE (r_outcome text, r_user_id uuid, r_family_id uuid, r_stored_hash bytea)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE t refresh_tokens%ROWTYPE; v_user uuid; v_consent timestamptz; v_exclusive boolean := false;
BEGIN
  SELECT user_id INTO v_user FROM refresh_tokens WHERE token_hash = p_old_hash;
  IF v_user IS NULL THEN
    r_outcome := 'INVALID'; RETURN NEXT; RETURN;
  END IF;
  LOOP
    BEGIN
      IF v_exclusive THEN
        SELECT consent_at INTO v_consent FROM users WHERE id = v_user FOR UPDATE;
      ELSE
        SELECT consent_at INTO v_consent FROM users WHERE id = v_user FOR SHARE;
      END IF;
      SELECT * INTO t FROM refresh_tokens WHERE token_hash = p_old_hash FOR UPDATE;
      IF NOT FOUND OR t.revoked_at IS NOT NULL OR t.expires_at <= p_now OR v_consent IS NULL THEN
        r_outcome := 'INVALID'; RETURN NEXT; RETURN;
      END IF;
      r_user_id := t.user_id; r_family_id := t.family_id; r_stored_hash := t.token_hash;
      IF (t.rotated_at IS NOT NULL AND p_now - t.rotated_at > p_grace)
         OR EXISTS (SELECT 1 FROM refresh_tokens o
                     WHERE o.family_id = t.family_id AND o.id <> t.id AND o.generation >= t.generation
                       AND o.rotated_at IS NOT NULL AND p_now - o.rotated_at > p_grace) THEN
        IF NOT v_exclusive THEN
          RAISE EXCEPTION 'replay: start over exclusively' USING ERRCODE = 'E9X01';
        END IF;
        UPDATE refresh_tokens SET revoked_at = p_now WHERE family_id = t.family_id AND revoked_at IS NULL;
        r_outcome := 'REUSED'; RETURN NEXT; RETURN;
      END IF;
      IF t.rotated_at IS NULL THEN
        UPDATE refresh_tokens SET rotated_at = p_now WHERE id = t.id;
        r_outcome := 'ROTATED';
      ELSE
        r_outcome := 'ROTATED_CONCURRENT';
      END IF;
      INSERT INTO refresh_tokens (user_id, family_id, token_hash, expires_at, created_at, generation)
      VALUES (t.user_id, t.family_id, p_new_hash, p_new_expires, p_now, t.generation + 1);
      RETURN NEXT; RETURN;
    EXCEPTION WHEN SQLSTATE 'E9X01' THEN
      v_exclusive := true;
    END;
  END LOOP;
END $$;

-- Logout: revokes the whole family of the presented token, including a successor being minted right now (no row, no
-- error, for an unknown token).
CREATE FUNCTION auth_refresh_revoke_family(p_token_hash bytea, p_now timestamptz)
  RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE v_user uuid; v_family uuid;
BEGIN
  SELECT user_id, family_id INTO v_user, v_family FROM refresh_tokens WHERE token_hash = p_token_hash;
  IF v_user IS NULL THEN RETURN; END IF;
  PERFORM 1 FROM users WHERE id = v_user FOR UPDATE;
  UPDATE refresh_tokens SET revoked_at = p_now WHERE family_id = v_family AND revoked_at IS NULL;
END $$;

-- Google ---------------------------------------------------------------------------------------------------------
-- The answer to the consent step of a Google sign-in. Keeps an earlier consent.
CREATE FUNCTION auth_consent_record(p_user_id uuid, p_now timestamptz)
  RETURNS void LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
BEGIN
  PERFORM 1 FROM users WHERE id = p_user_id FOR UPDATE;
  UPDATE users SET consent_at = coalesce(consent_at, p_now) WHERE id = p_user_id;
END $$;

-- One atomic step for a sign-in with an external identity. Nobody has agreed to anything here: consent reaches the server
-- only through auth_consent_record, and no token is issued before it. Outcomes:
--   EXISTING             the (provider, subject) is already linked: that account
--   EMAIL_NOT_VERIFIED   the provider does not assert email_verified: no link, no account, nothing changes
--   LINKED               a verified-email account with the same address gets the identity attached. Its sessions end: the
--                        address may have been verified by a mailbox owner clicking a link in a mail they did not ask for,
--                        for an account an attacker registered, and that attacker may be signed in. The password stays,
--                        because nothing here tells that case from a person adding Google to their own account; the
--                        caller mails the owner, who can replace it. Consent on record stays.
--   LINKED_NEUTRALISED   same, but the local account's address was NOT verified. Whoever created that account may
--                        be someone other than the mailbox owner (pre-hijack: an attacker registers the victim's
--                        address with a password of their choosing, then waits for the victim to sign in with Google).
--                        So what they chose goes: the password, the display name (replaced by the provider's) and their
--                        consent (the owner gives their own); every refresh token revoked and pending tokens retired;
--                        the real owner keeps Google and can set a password through the reset mail.
--   CREATED              no account with that address: created verified, without a password, without consent
CREATE FUNCTION auth_oauth_login(
  p_provider text, p_subject text, p_email text, p_email_verified boolean, p_display_name text, p_locale text, p_now timestamptz)
  RETURNS TABLE (r_outcome text, r_user_id uuid, r_display_name text, r_locale text, r_credentials_version bigint)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE v_user uuid; u users%ROWTYPE; v_email text := lower(btrim(p_email));
BEGIN
  SELECT i.user_id INTO v_user FROM auth_identities i WHERE i.provider = p_provider AND i.subject = p_subject;
  IF v_user IS NOT NULL THEN
    SELECT * INTO u FROM users WHERE id = v_user;
    r_outcome := 'EXISTING'; r_user_id := u.id; r_display_name := u.display_name; r_locale := u.locale;
    r_credentials_version := u.credentials_version;
    RETURN NEXT; RETURN;
  END IF;
  IF NOT coalesce(p_email_verified, false) OR v_email IS NULL OR v_email = '' THEN
    r_outcome := 'EMAIL_NOT_VERIFIED'; RETURN NEXT; RETURN;
  END IF;
  SELECT * INTO u FROM users WHERE lower(email) = v_email FOR UPDATE;
  IF FOUND THEN
    IF u.email_verified_at IS NULL THEN
      UPDATE users SET password_hash = NULL, email_verified_at = p_now, consent_at = NULL,
                       display_name = nullif(btrim(p_display_name), ''), credentials_version = credentials_version + 1
       WHERE id = u.id RETURNING * INTO u;
      UPDATE password_resets SET used_at = p_now WHERE user_id = u.id AND used_at IS NULL;
      UPDATE email_verifications SET used_at = p_now WHERE user_id = u.id AND used_at IS NULL;
      UPDATE refresh_tokens SET revoked_at = p_now WHERE user_id = u.id AND revoked_at IS NULL;
      r_outcome := 'LINKED_NEUTRALISED';
    ELSE
      UPDATE users SET credentials_version = credentials_version + 1 WHERE id = u.id RETURNING * INTO u;
      UPDATE refresh_tokens SET revoked_at = p_now WHERE user_id = u.id AND revoked_at IS NULL;
      r_outcome := 'LINKED';
    END IF;
    INSERT INTO auth_identities (user_id, provider, subject, email, created_at) VALUES (u.id, p_provider, p_subject, v_email, p_now);
    r_user_id := u.id; r_display_name := u.display_name; r_locale := u.locale; r_credentials_version := u.credentials_version;
    RETURN NEXT; RETURN;
  END IF;
  INSERT INTO users (email, display_name, locale, email_verified_at)
  VALUES (v_email, nullif(btrim(p_display_name), ''), CASE WHEN p_locale IN ('vi','en') THEN p_locale ELSE 'vi' END, p_now)
  RETURNING * INTO u;
  INSERT INTO auth_identities (user_id, provider, subject, email, created_at) VALUES (u.id, p_provider, p_subject, v_email, p_now);
  r_outcome := 'CREATED'; r_user_id := u.id; r_display_name := u.display_name; r_locale := u.locale;
  r_credentials_version := u.credentials_version;
  RETURN NEXT;
END $$;

-- Housekeeping: drops tokens that expired (or were used / revoked) more than 7 days ago. The clock and the age are its
-- own: no caller can make it delete something live. Tables in the order of the Concurrency note.
CREATE FUNCTION auth_purge_expired() RETURNS integer
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE n integer := 0; c integer; v_cutoff timestamptz := now() - interval '7 days';
BEGIN
  DELETE FROM password_resets WHERE expires_at < v_cutoff OR used_at < v_cutoff;
  GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
  DELETE FROM email_verifications WHERE expires_at < v_cutoff OR used_at < v_cutoff;
  GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
  DELETE FROM refresh_tokens WHERE expires_at < v_cutoff OR revoked_at < v_cutoff;
  GET DIAGNOSTICS c = ROW_COUNT; n := n + c;
  RETURN n;
END $$;

REVOKE ALL ON FUNCTION
  auth_create_user(text, text, text, text, timestamptz),
  auth_login_lookup(text),
  auth_email_verification_issue(uuid, bytea, timestamptz, timestamptz),
  auth_email_verification_issue_for_email(text, bytea, timestamptz, timestamptz),
  auth_email_verification_consume(bytea, timestamptz),
  auth_password_reset_issue(text, bytea, timestamptz, timestamptz),
  auth_password_reset_confirm(bytea, text, timestamptz),
  auth_refresh_issue(uuid, uuid, bytea, timestamptz, timestamptz, bigint),
  auth_refresh_rotate(bytea, bytea, timestamptz, timestamptz, interval),
  auth_refresh_revoke_family(bytea, timestamptz),
  auth_consent_record(uuid, timestamptz),
  auth_oauth_login(text, text, text, boolean, text, text, timestamptz),
  auth_purge_expired()
  FROM PUBLIC;
GRANT EXECUTE ON FUNCTION
  auth_create_user(text, text, text, text, timestamptz),
  auth_login_lookup(text),
  auth_email_verification_issue(uuid, bytea, timestamptz, timestamptz),
  auth_email_verification_issue_for_email(text, bytea, timestamptz, timestamptz),
  auth_email_verification_consume(bytea, timestamptz),
  auth_password_reset_issue(text, bytea, timestamptz, timestamptz),
  auth_password_reset_confirm(bytea, text, timestamptz),
  auth_refresh_issue(uuid, uuid, bytea, timestamptz, timestamptz, bigint),
  auth_refresh_rotate(bytea, bytea, timestamptz, timestamptz, interval),
  auth_refresh_revoke_family(bytea, timestamptz),
  auth_consent_record(uuid, timestamptz),
  auth_oauth_login(text, text, text, boolean, text, text, timestamptz),
  auth_purge_expired()
  TO e9x_app;
