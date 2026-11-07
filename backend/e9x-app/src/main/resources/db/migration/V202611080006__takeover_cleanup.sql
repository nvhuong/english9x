-- Final review of P1, findings I1 and M11, plan fix 11. What an unverified account chose must not outlive the arrival of the real
-- mailbox owner, and access tokens from before a credentials change must stop working (AccessTokens, CurrentContextFilter).
--
--  * An unverified account is nobody yet: anyone can register any address. So it gets no Telegram link token, writes no notification
--    preference and joins no organisation. It keeps read access to its own (default) preferences.
--  * LINKED_NEUTRALISED (the local account was unverified): chat, link tokens AND preferences go, so email is on by default again.
--    LINKED (verified account): chat and link tokens go, preferences stay; they were chosen by someone who proved the mailbox.
--  * Password reset: chat and link tokens go, preferences stay. The chat may have been linked by whoever held the old password;
--    the owner links again in one step.
--  * Lock order inside the two auth functions: telegram_link_tokens BEFORE telegram_links. telegram_link_consume holds its token row FOR UPDATE
--    while it writes the link, so deleting the tokens first waits for a consume in flight, and the following DELETE of the link, with a
--    fresh snapshot (READ COMMITTED, VOLATILE), sees what that consume wrote. A consume arriving later finds no token.
--  * What remains: a request that passed the token check an instant before a neutralisation committed can still issue a link token
--    for the now verified account. That token dies only by expiry (e9x.notification.telegram.link-ttl) then. Closing it means passing the version into
--    telegram_link_issue; a window of one request, needing a victim's sign-in at that very moment, is accepted (final-fix report).

-- Anything already written by an unverified account: same rule, on a database from before this file.
DELETE FROM telegram_link_tokens t USING users u WHERE u.id = t.user_id AND u.email_verified_at IS NULL;
DELETE FROM telegram_links l USING users u WHERE u.id = l.user_id AND u.email_verified_at IS NULL;
DELETE FROM notification_prefs p USING users u WHERE u.id = p.user_id AND u.email_verified_at IS NULL;

-- SQLSTATE E9X02: the caller's address is not verified. CREATE OR REPLACE keeps the grants of the original (e9x_app only).
CREATE OR REPLACE FUNCTION telegram_link_issue(p_token_hash bytea, p_now timestamptz, p_expires_at timestamptz)
  RETURNS void
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_user uuid := e9x_current_user();
  v_verified timestamptz;
BEGIN
  SELECT u.email_verified_at INTO v_verified FROM users u WHERE u.id = v_user;
  IF v_user IS NULL OR NOT FOUND THEN
    RAISE EXCEPTION 'no signed-in user' USING ERRCODE = '42501';
  END IF;
  IF v_verified IS NULL THEN
    RAISE EXCEPTION 'email not verified' USING ERRCODE = 'E9X02';
  END IF;
  IF p_expires_at <= p_now THEN
    RAISE EXCEPTION 'token already expired' USING ERRCODE = '22023';
  END IF;
  DELETE FROM telegram_link_tokens WHERE user_id = v_user AND (used_at IS NULL OR expires_at < p_now);
  INSERT INTO telegram_link_tokens (user_id, token_hash, created_at, expires_at) VALUES (v_user, p_token_hash, p_now, p_expires_at);
END $$;

DROP POLICY notification_prefs_insert ON notification_prefs;
CREATE POLICY notification_prefs_insert ON notification_prefs FOR INSERT WITH CHECK (
  user_id = e9x_current_user()
  AND EXISTS (SELECT 1 FROM users u WHERE u.id = user_id AND u.email_verified_at IS NOT NULL));
DROP POLICY notification_prefs_update ON notification_prefs;
CREATE POLICY notification_prefs_update ON notification_prefs FOR UPDATE
  USING (user_id = e9x_current_user())
  WITH CHECK (user_id = e9x_current_user()
              AND EXISTS (SELECT 1 FROM users u WHERE u.id = user_id AND u.email_verified_at IS NOT NULL));

-- Outcome EMAIL_NOT_VERIFIED added. A membership would survive neutralisation, so it is refused rather than cleaned up.
CREATE OR REPLACE FUNCTION org_join_code_redeem(p_code_hash bytea, p_now timestamptz)
  RETURNS TABLE (r_outcome text, r_org_id uuid)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_user uuid := e9x_current_user();
  jc join_codes%ROWTYPE;
BEGIN
  IF v_user IS NULL OR p_code_hash IS NULL OR NOT EXISTS (SELECT 1 FROM users u WHERE u.id = v_user) THEN
    RETURN QUERY SELECT 'INVALID'::text, NULL::uuid;
    RETURN;
  END IF;
  SELECT * INTO jc FROM join_codes c WHERE c.code_hash = p_code_hash FOR UPDATE;
  IF NOT FOUND OR jc.revoked_at IS NOT NULL OR jc.expires_at <= p_now
     OR (jc.max_uses IS NOT NULL AND jc.used_count >= jc.max_uses) THEN
    RETURN QUERY SELECT 'INVALID'::text, NULL::uuid;
    RETURN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM users u WHERE u.id = v_user AND u.email_verified_at IS NOT NULL) THEN
    RETURN QUERY SELECT 'EMAIL_NOT_VERIFIED'::text, NULL::uuid;
    RETURN;
  END IF;
  IF EXISTS (SELECT 1 FROM org_memberships m WHERE m.org_id = jc.org_id AND m.user_id = v_user) THEN
    RETURN QUERY SELECT 'ALREADY_MEMBER'::text, jc.org_id;
    RETURN;
  END IF;
  INSERT INTO org_memberships(org_id, user_id) VALUES (jc.org_id, v_user);
  INSERT INTO role_grants(user_id, org_id, exam_code, role) VALUES (v_user, jc.org_id, NULL, 'LEARNER')
    ON CONFLICT ON CONSTRAINT role_grants_scope_uq DO NOTHING;
  UPDATE join_codes SET used_count = used_count + 1 WHERE id = jc.id;
  RETURN QUERY SELECT 'JOINED'::text, jc.org_id;
END $$;

-- The body of V202611070001 plus the two deletes, see the header. Everything else stands as documented there.
CREATE OR REPLACE FUNCTION auth_password_reset_confirm(p_token_hash bytea, p_new_password_hash text, p_now timestamptz)
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
  DELETE FROM telegram_link_tokens WHERE user_id = v_user;
  DELETE FROM telegram_links WHERE user_id = v_user;
  r_user_id := v_user; r_stored_hash := v_hash;
  RETURN NEXT;
END $$;

CREATE OR REPLACE FUNCTION auth_oauth_login(
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
      UPDATE users SET password_hash = NULL, email_verified_at = p_now, consent_at = NULL, neutralised_at = p_now,
                       display_name = nullif(btrim(p_display_name), ''), credentials_version = credentials_version + 1
       WHERE id = u.id RETURNING * INTO u;
      UPDATE password_resets SET used_at = p_now WHERE user_id = u.id AND used_at IS NULL;
      UPDATE email_verifications SET used_at = p_now WHERE user_id = u.id AND used_at IS NULL;
      UPDATE refresh_tokens SET revoked_at = p_now WHERE user_id = u.id AND revoked_at IS NULL;
      DELETE FROM notification_prefs WHERE user_id = u.id;
      r_outcome := 'LINKED_NEUTRALISED';
    ELSE
      UPDATE users SET password_hash = NULL, credentials_version = credentials_version + 1 WHERE id = u.id RETURNING * INTO u;
      UPDATE password_resets SET used_at = p_now WHERE user_id = u.id AND used_at IS NULL;
      UPDATE email_verifications SET used_at = p_now WHERE user_id = u.id AND used_at IS NULL;
      UPDATE refresh_tokens SET revoked_at = p_now WHERE user_id = u.id AND revoked_at IS NULL;
      r_outcome := 'LINKED';
    END IF;
    DELETE FROM telegram_link_tokens WHERE user_id = u.id;
    DELETE FROM telegram_links WHERE user_id = u.id;
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

-- M11. The access token now names the credentials version it was issued under. A rotation has to hand out the version read under the
-- users lock it already holds: reading it in a second statement would let a reset slip between the two, and a token minted from a
-- then-revoked refresh token would carry the new version. The return type changes, so DROP and CREATE, and the grant is repeated.
DROP FUNCTION auth_refresh_rotate(bytea, bytea, timestamptz, timestamptz, interval);
CREATE FUNCTION auth_refresh_rotate(p_old_hash bytea, p_new_hash bytea, p_new_expires timestamptz, p_now timestamptz, p_grace interval)
  RETURNS TABLE (r_outcome text, r_user_id uuid, r_family_id uuid, r_stored_hash bytea, r_credentials_version bigint)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE t refresh_tokens%ROWTYPE; v_user uuid; v_consent timestamptz; v_version bigint; v_exclusive boolean := false;
BEGIN
  SELECT user_id INTO v_user FROM refresh_tokens WHERE token_hash = p_old_hash;
  IF v_user IS NULL THEN
    r_outcome := 'INVALID'; RETURN NEXT; RETURN;
  END IF;
  LOOP
    BEGIN
      IF v_exclusive THEN
        SELECT consent_at, credentials_version INTO v_consent, v_version FROM users WHERE id = v_user FOR UPDATE;
      ELSE
        SELECT consent_at, credentials_version INTO v_consent, v_version FROM users WHERE id = v_user FOR SHARE;
      END IF;
      SELECT * INTO t FROM refresh_tokens WHERE token_hash = p_old_hash FOR UPDATE;
      IF NOT FOUND OR t.revoked_at IS NOT NULL OR t.expires_at <= p_now OR v_consent IS NULL THEN
        r_outcome := 'INVALID'; RETURN NEXT; RETURN;
      END IF;
      r_user_id := t.user_id; r_family_id := t.family_id; r_stored_hash := t.token_hash; r_credentials_version := v_version;
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
REVOKE ALL ON FUNCTION auth_refresh_rotate(bytea, bytea, timestamptz, timestamptz, interval) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth_refresh_rotate(bytea, bytea, timestamptz, timestamptz, interval) TO e9x_app;

-- The request filter's one read per authenticated request: a primary-key lookup, autocommit, no session context needed. The version is a
-- counter that says nothing about the person, and only holding a user's id lets anyone ask.
CREATE FUNCTION auth_credentials_version(p_user_id uuid) RETURNS bigint
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT credentials_version FROM users WHERE id = p_user_id $$;
REVOKE ALL ON FUNCTION auth_credentials_version(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth_credentials_version(uuid) TO e9x_app;
