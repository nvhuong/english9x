-- Final re-review of P1, finding m1: telegram_link_consume takes its locks in the same order as the auth functions.
--
-- Correction of two statements in earlier migrations, which stay as merged:
--  * V202611080006 (header, "Lock order inside the two auth functions") and V202611070001 ("no deadlock path exists") are not true for
--    a first link. telegram_link_consume locked its token row FOR UPDATE and then waited on users (the foreign key of its link
--    insert), while auth_oauth_login and auth_password_reset_confirm hold users FOR UPDATE and then delete the tokens: a cycle.
--    Measured: "deadlock detected", one of the two aborted (a failed sign-in or reset, rolled back whole; a retry works).
--  * The order, now in every function that touches both: users, then telegram_link_tokens, then telegram_links.
--    telegram_link_consume reads the owner of the token WITHOUT a lock (user_id never changes), takes users FOR SHARE, then the token
--    row FOR UPDATE and re-checks it. Behind a sign-in that holds the user it waits, and finds the token gone: INVALID.
--
-- Every other branch is as in V202611080003. CREATE OR REPLACE keeps the grants (e9x_app only) and the search_path is restated.
CREATE OR REPLACE FUNCTION telegram_link_consume(p_token_hash bytea, p_chat_id bigint, p_now timestamptz)
  RETURNS TABLE (r_status text, r_locale text, r_old_chat bigint)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_token telegram_link_tokens%ROWTYPE;
  v_user uuid;
  v_owner uuid;
  v_locale text;
  v_old bigint;
BEGIN
  SELECT t.user_id INTO v_user FROM telegram_link_tokens t WHERE t.token_hash = p_token_hash;
  IF v_user IS NULL THEN
    r_status := 'INVALID'; r_locale := NULL; r_old_chat := NULL;
    RETURN NEXT;
    RETURN;
  END IF;
  PERFORM 1 FROM users u WHERE u.id = v_user FOR SHARE;
  SELECT * INTO v_token FROM telegram_link_tokens t WHERE t.token_hash = p_token_hash FOR UPDATE;
  IF NOT FOUND OR v_token.used_at IS NOT NULL OR v_token.expires_at <= p_now THEN
    r_status := 'INVALID'; r_locale := NULL; r_old_chat := NULL;
    RETURN NEXT;
    RETURN;
  END IF;
  SELECT l.user_id INTO v_owner FROM telegram_links l WHERE l.chat_id = p_chat_id;
  IF v_owner IS NOT NULL AND v_owner <> v_token.user_id THEN
    r_status := 'CHAT_TAKEN'; r_locale := NULL; r_old_chat := NULL;
    RETURN NEXT;
    RETURN;
  END IF;
  -- the chat this account had before, to be told that it no longer receives anything (NULL when none, or the same chat)
  SELECT l.chat_id INTO v_old FROM telegram_links l WHERE l.user_id = v_token.user_id AND l.chat_id <> p_chat_id;
  BEGIN
    INSERT INTO telegram_links (user_id, chat_id, linked_at) VALUES (v_token.user_id, p_chat_id, p_now)
    ON CONFLICT (user_id) DO UPDATE SET chat_id = EXCLUDED.chat_id, linked_at = EXCLUDED.linked_at;
  EXCEPTION WHEN unique_violation THEN
    -- another account took this chat between the check and the insert
    r_status := 'CHAT_TAKEN'; r_locale := NULL; r_old_chat := NULL;
    RETURN NEXT;
    RETURN;
  END;
  UPDATE telegram_link_tokens SET used_at = p_now WHERE id = v_token.id;
  SELECT u.locale INTO v_locale FROM users u WHERE u.id = v_token.user_id;
  r_status := 'LINKED'; r_locale := v_locale; r_old_chat := v_old;
  RETURN NEXT;
END $$;
