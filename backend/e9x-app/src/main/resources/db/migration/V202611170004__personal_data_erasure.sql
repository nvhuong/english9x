-- P6.5 branch D — personal-data erasure (spec §15, Nghị định 13/2023). The data subject's right to erasure, as ONE audited procedure.
--
-- Design (ghi chú 2026-11-08 P1.9): audit_log keeps IDS ONLY and is append-only (its triggers refuse UPDATE/DELETE/TRUNCATE even to the owner), so erasure never
-- touches it; a name or an address is read AT VIEW TIME from users / invitations (AuditQueryService), so scrubbing those tables erases it from the audit screen too
-- (the screen then shows a tombstone / null). The erasure is a SINGLE narrow SECURITY DEFINER owned by the migration role, not a new grant to e9x_app on any log table.
-- What it covers: the person's own account fields and sign-in identities; the invitations addressed to them (whatever their state); their notifications, preferences and
-- Telegram link; the finished background work (completed event publications, terminal jobs) whose payload may carry their data; their Speaking RECORDINGS' rows (voice is
-- biometric data — the objects themselves are deleted from the store by PersonalDataService through personal_data_recording_keys, before these rows go); the learner's
-- authored free text (responses.answer — Writing essays); the teacher's FEEDBACK on the person's attempts (teacher_reviews.comment); and the payer's transfer memo
-- (orders.bank_ref). Live/pending work is left: it is not ours to drop mid-flight, and it carries no more than ids; the orders rows stay for accounting, only the memo goes.

CREATE FUNCTION erase_personal_data(p_user uuid) RETURNS integer
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_email text;
  v_invitations integer := 0;
BEGIN
  SELECT lower(email) INTO v_email FROM users WHERE id = p_user;
  IF v_email IS NULL THEN
    RAISE EXCEPTION 'no such user %', p_user USING ERRCODE = '23503';
  END IF;

  -- 1. the person's own account fields (name, email) and their sign-in identities; the account id survives so their past activity keeps its referential integrity
  UPDATE users SET email = 'deleted-' || p_user || '@deleted.invalid', display_name = NULL, password_hash = NULL,
                   credentials_version = credentials_version + 1
   WHERE id = p_user;
  DELETE FROM auth_identities WHERE user_id = p_user;
  DELETE FROM refresh_tokens WHERE user_id = p_user;
  DELETE FROM password_resets WHERE user_id = p_user;
  DELETE FROM email_verifications WHERE user_id = p_user;

  -- 2. the invitations addressed to this person, whatever their state (pending, accepted, revoked, expired)
  UPDATE invitations SET email = 'deleted-' || id || '@deleted.invalid' WHERE lower(email) = v_email;
  GET DIAGNOSTICS v_invitations = ROW_COUNT;

  -- 3. the notifications (content) and their deliveries (ON DELETE CASCADE), the preferences and the Telegram link / tokens
  DELETE FROM notifications WHERE user_id = p_user;
  DELETE FROM notification_prefs WHERE user_id = p_user;
  DELETE FROM telegram_links WHERE user_id = p_user;
  DELETE FROM telegram_link_tokens WHERE user_id = p_user;

  -- 4. finished background work that may carry the person's data: the event module's COMPLETED publications and TERMINAL jobs (live work is left, it carries ids only)
  DELETE FROM event_publication WHERE completion_date IS NOT NULL AND position(p_user::text IN serialized_event) > 0;
  DELETE FROM jobs WHERE status IN ('SUCCEEDED', 'FAILED') AND position(p_user::text IN payload::text) > 0;

  -- 5. the person's attempt data that is personal or biometric. The definer is owned by e9x_owner, so the attempt-freeze / window guards (which exempt the owner) do
  --    not block these. The Speaking recordings' OBJECTS are deleted first, by PersonalDataService, through personal_data_recording_keys; here the rows go with the rest.
  DELETE FROM speaking_recordings WHERE user_id = p_user;
  UPDATE responses SET answer = 'null'::jsonb WHERE user_id = p_user;         -- the learner's authored free text (Writing essays); scores and item stats are already derived
  -- teacher_reviews is immutable by a trigger (a review is never changed): the owner lifts it just to scrub the free-text comment (the band and criteria, a grading
  -- record, stay). The ALTER takes ACCESS EXCLUSIVE, so no other session writes the table while it is off; it is on again before the function returns.
  ALTER TABLE teacher_reviews DISABLE TRIGGER teacher_reviews_immutable;
  UPDATE teacher_reviews SET comment = '' WHERE user_id = p_user;            -- the teacher's feedback tied to the person's attempts
  ALTER TABLE teacher_reviews ENABLE TRIGGER teacher_reviews_immutable;
  UPDATE orders SET bank_ref = NULL WHERE user_id = p_user;                   -- the transfer memo (may carry the payer's name); the order row stays for accounting

  RETURN v_invitations;
END $$;
REVOKE ALL ON FUNCTION erase_personal_data(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION erase_personal_data(uuid) TO e9x_app;

-- The Speaking recordings' object-storage keys of a person. PersonalDataService reads them (the recordings are the learner's own under RLS, so a narrow SECURITY DEFINER
-- is needed for a SYS_ADMIN erasing another person) and deletes the objects BEFORE erase_personal_data deletes the rows, so a voice blob (biometric data) is never
-- orphaned in the store. It reveals object keys of the subject only — nothing personal in the string, and only the keys of that one user.
CREATE FUNCTION personal_data_recording_keys(p_user uuid) RETURNS SETOF text
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT storage_path FROM speaking_recordings WHERE user_id = p_user
$$;
REVOKE ALL ON FUNCTION personal_data_recording_keys(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION personal_data_recording_keys(uuid) TO e9x_app;

-- Fix (review of P1, triage ledger 113): auth_purge_no_consent read conkey[1], which is only the first column of a foreign key, so a composite foreign key that
-- references users would have been read on the wrong column. Pair each conkey with its confkey and pick the referencing column that maps to users.id. Body unchanged
-- otherwise; db-test 36 (takeover cleanup) still proves it.
CREATE OR REPLACE FUNCTION auth_purge_no_consent(p_retention interval) RETURNS integer
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_cutoff timestamptz := now() - greatest(p_retention, interval '1 day');
  v_id uuid; v_active boolean; n integer := 0; ref record;
  v_users_id smallint := (SELECT attnum FROM pg_attribute WHERE attrelid = 'users'::regclass AND attname = 'id' AND NOT attisdropped);
BEGIN
  FOR v_id IN
    SELECT u.id FROM users u
     WHERE u.consent_at IS NULL AND coalesce(u.neutralised_at, u.created_at) < v_cutoff
       AND EXISTS (SELECT 1 FROM auth_identities i WHERE i.user_id = u.id)
     FOR UPDATE OF u SKIP LOCKED
  LOOP
    v_active := false;
    FOR ref IN
      SELECT c.conrelid::regclass AS tbl, a.attname AS col
        FROM pg_constraint c
        CROSS JOIN LATERAL unnest(c.conkey, c.confkey) AS k(conkey, confkey)
        JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.conkey
       WHERE c.contype = 'f' AND c.confrelid = 'users'::regclass AND k.confkey = v_users_id
         AND c.conrelid NOT IN ('auth_identities'::regclass, 'refresh_tokens'::regclass,
                                'email_verifications'::regclass, 'password_resets'::regclass)
    LOOP
      EXECUTE format('SELECT EXISTS (SELECT 1 FROM %s WHERE %I = $1)', ref.tbl, ref.col) INTO v_active USING v_id;
      EXIT WHEN v_active;
    END LOOP;
    IF NOT v_active THEN
      DELETE FROM users WHERE id = v_id;
      n := n + 1;
    END IF;
  END LOOP;
  RETURN n;
END $$;
