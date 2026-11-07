-- P1.8 notification (F52-F55): in-app notifications, per-event/per-channel preferences, Telegram links, and the delivery
-- bookkeeping of the background job `send_notification`.
--
-- Model (the rulings of P1.8):
--  * A notification belongs to a PERSON, not to a context: it is readable and markable by that person only, in every
--    context (the policies below do not look at app.org_id, and "English9x staff" see nobody's notifications).
--    The row never carries a content title: only ids, the exam code and the content type (data jsonb), so a person who left an
--    organisation keeps nothing of its content in their inbox.
--  * Nobody inserts a notification directly. e9x_app has no INSERT grant: notification_create() (SECURITY DEFINER, fixed
--    search_path, EXECUTE for e9x_app only) is the one way in. It writes the row and the delivery bookkeeping in one statement
--    block and returns the channels to enqueue a job for. Its dedupe key makes it idempotent (events are delivered at least once).
--  * The worker (e9x_worker_login) reaches NO notification table. The job payload is only {notificationId, channel}: no address, no
--    name, no text, so the jobs table holds no personal data. What the sender needs (address or chat id, locale, the event data)
--    comes from notification_delivery_load(), EXECUTE for e9x_worker only, which answers only for a delivery still PENDING and
--    only with what that channel needs; notification_delivery_finish() records the outcome. A random notification id is the
--    key to both, and the worker pool is not reachable from HTTP.
--  * The Telegram link token is 256-bit random, stored as a SHA-256 digest, single-use, with an expiry. It is issued by the person
--    (telegram_link_issue, for the caller of the current context) and consumed by the unauthenticated webhook
--    (telegram_link_consume). Rule for a chat that is already linked: one chat belongs to at most one account at a time. Linking
--    a chat that belongs to ANOTHER account is refused (CHAT_TAKEN, the token stays unused); its owner must unlink in the app first.
--    Linking another chat for the same account replaces the old one (an account has at most one chat).
--  * Reviewer lookup for ReviewRequested: notification_reviewers_of_review(review_id) returns the user ids of the REVIEWERs of that
--    open review's exam in that review's own context. The argument is a review id (a capability the caller must already hold), not an
--    organisation or an exam, so the function cannot be used to list the people of an arbitrary organisation.
--
-- What the request role can still do through these functions (they narrow SQL, they do not make a compromised statement harmless):
--  * create a notification (in-app row, and the delivery of it) for any user id it knows, with an event type of the allowed shape and
--    data of the allowed size: unwanted notifications / mail / Telegram messages to a known person, never reading anyone's;
--  * learn the reviewer ids of an open review whose id it holds; link a Telegram chat to an account only with a token it holds.
-- It can NOT: read another person's notifications, preferences, link or tokens; read addresses or chat ids; list people by organisation.

-- ---- tables ---------------------------------------------------------------------------------------------------------------------
CREATE TABLE notifications (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  -- the Java enum EventType is the list; the database only keeps the shape (a new type needs no migration)
  event_type  text        NOT NULL CHECK (event_type ~ '^[A-Z][A-Z0-9_]{0,63}$'),
  data        jsonb       NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(data) = 'object' AND pg_column_size(data) <= 4096),
  -- one notification per (person, source event): redelivery of the event creates nothing new
  dedupe_key  text        NOT NULL CHECK (char_length(dedupe_key) BETWEEN 1 AND 200),
  created_at  timestamptz NOT NULL DEFAULT now(),
  read_at     timestamptz,
  CONSTRAINT notifications_dedupe_uq UNIQUE (user_id, dedupe_key)
);
CREATE INDEX notifications_inbox_idx ON notifications (user_id, created_at DESC, id DESC);
CREATE INDEX notifications_unread_idx ON notifications (user_id) WHERE read_at IS NULL;

-- Preferences by (person, event, channel). IN_APP is always on and has no row. No row = enabled.
CREATE TABLE notification_prefs (
  user_id     uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  event_type  text        NOT NULL CHECK (event_type ~ '^[A-Z][A-Z0-9_]{0,63}$'),
  channel     text        NOT NULL CHECK (channel IN ('EMAIL', 'TELEGRAM')),
  enabled     boolean     NOT NULL,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, event_type, channel)
);

CREATE TABLE telegram_links (
  user_id     uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  chat_id     bigint      NOT NULL,
  linked_at   timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT telegram_links_chat_uq UNIQUE (chat_id)
);

CREATE TABLE telegram_link_tokens (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash  bytea       NOT NULL UNIQUE,
  created_at  timestamptz NOT NULL DEFAULT now(),
  expires_at  timestamptz NOT NULL,
  used_at     timestamptz
);
CREATE INDEX telegram_link_tokens_user_idx ON telegram_link_tokens (user_id);

-- One row per (notification, channel that is not IN_APP) that is to be sent. Written by notification_create, finished by the worker.
CREATE TABLE notification_deliveries (
  notification_id uuid        NOT NULL REFERENCES notifications(id) ON DELETE CASCADE,
  channel         text        NOT NULL CHECK (channel IN ('EMAIL', 'TELEGRAM')),
  status          text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'SKIPPED')),
  last_error      text        CHECK (char_length(last_error) <= 200),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (notification_id, channel)
);

-- ---- Row-Level Security ---------------------------------------------------------------------------------------------------------
ALTER TABLE notifications           ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_prefs      ENABLE ROW LEVEL SECURITY;
ALTER TABLE telegram_links          ENABLE ROW LEVEL SECURITY;
ALTER TABLE telegram_link_tokens    ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_deliveries ENABLE ROW LEVEL SECURITY;

-- Yours only, in every context. No INSERT / DELETE policy on notifications: they come from notification_create and are never deleted by a person.
CREATE POLICY notifications_select ON notifications FOR SELECT USING (user_id = e9x_current_user());
CREATE POLICY notifications_mark_read ON notifications FOR UPDATE
  USING (user_id = e9x_current_user()) WITH CHECK (user_id = e9x_current_user());

CREATE POLICY notification_prefs_select ON notification_prefs FOR SELECT USING (user_id = e9x_current_user());
CREATE POLICY notification_prefs_insert ON notification_prefs FOR INSERT WITH CHECK (user_id = e9x_current_user());
CREATE POLICY notification_prefs_update ON notification_prefs FOR UPDATE
  USING (user_id = e9x_current_user()) WITH CHECK (user_id = e9x_current_user());

-- You may see and remove YOUR link; it is created only by telegram_link_consume.
CREATE POLICY telegram_links_select ON telegram_links FOR SELECT USING (user_id = e9x_current_user());
CREATE POLICY telegram_links_delete ON telegram_links FOR DELETE USING (user_id = e9x_current_user());
-- telegram_link_tokens, notification_deliveries: RLS on, no policy and no grant: only the functions below.

-- ---- privileges -----------------------------------------------------------------------------------------------------------------
GRANT SELECT ON notifications TO e9x_app;
GRANT UPDATE (read_at) ON notifications TO e9x_app;
GRANT SELECT ON notification_prefs TO e9x_app;
GRANT INSERT (user_id, event_type, channel, enabled, updated_at) ON notification_prefs TO e9x_app;
GRANT UPDATE (enabled, updated_at) ON notification_prefs TO e9x_app;
GRANT SELECT, DELETE ON telegram_links TO e9x_app;

-- ---- functions ------------------------------------------------------------------------------------------------------------------
-- Creates the in-app row of a notification and the delivery rows of the optional channels the person has enabled (EMAIL; TELEGRAM only
-- when a chat is linked), and returns (notification_id, channel) for each delivery so that the caller enqueues the jobs in the same
-- transaction. Returns nothing when the user does not exist or this (user, dedupe key) was already notified.
CREATE FUNCTION notification_create(p_user uuid, p_type text, p_data jsonb, p_dedupe text, p_now timestamptz)
  RETURNS TABLE (r_notification_id uuid, r_channel text)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_id uuid;
BEGIN
  IF p_type IS NULL OR p_type !~ '^[A-Z][A-Z0-9_]{0,63}$' THEN
    RAISE EXCEPTION 'invalid event type' USING ERRCODE = '22023';
  END IF;
  IF p_dedupe IS NULL OR char_length(p_dedupe) NOT BETWEEN 1 AND 200 THEN
    RAISE EXCEPTION 'invalid dedupe key' USING ERRCODE = '22023';
  END IF;
  IF p_data IS NULL OR jsonb_typeof(p_data) <> 'object' OR pg_column_size(p_data) > 4096 THEN
    RAISE EXCEPTION 'invalid notification data' USING ERRCODE = '22023';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM users u WHERE u.id = p_user) THEN
    RETURN;
  END IF;
  INSERT INTO notifications (user_id, event_type, data, dedupe_key, created_at)
  VALUES (p_user, p_type, p_data, p_dedupe, p_now)
  ON CONFLICT (user_id, dedupe_key) DO NOTHING
  RETURNING id INTO v_id;
  IF v_id IS NULL THEN
    RETURN;
  END IF;
  -- EMAIL: on unless the person switched it off for this event
  IF COALESCE((SELECT p.enabled FROM notification_prefs p
                WHERE p.user_id = p_user AND p.event_type = p_type AND p.channel = 'EMAIL'), true) THEN
    INSERT INTO notification_deliveries (notification_id, channel, updated_at) VALUES (v_id, 'EMAIL', p_now);
    r_notification_id := v_id; r_channel := 'EMAIL';
    RETURN NEXT;
  END IF;
  -- TELEGRAM: needs a linked chat, and on unless switched off
  IF EXISTS (SELECT 1 FROM telegram_links l WHERE l.user_id = p_user)
     AND COALESCE((SELECT p.enabled FROM notification_prefs p
                    WHERE p.user_id = p_user AND p.event_type = p_type AND p.channel = 'TELEGRAM'), true) THEN
    INSERT INTO notification_deliveries (notification_id, channel, updated_at) VALUES (v_id, 'TELEGRAM', p_now);
    r_notification_id := v_id; r_channel := 'TELEGRAM';
    RETURN NEXT;
  END IF;
  RETURN;
END $$;

-- What the sender of one delivery needs, only while the delivery is PENDING. The preference and the link are read again here, so a
-- person who switched the channel off (or unlinked) after the event but before the send receives nothing: the delivery becomes SKIPPED.
CREATE FUNCTION notification_delivery_load(p_notification uuid, p_channel text)
  RETURNS TABLE (r_event_type text, r_data jsonb, r_created_at timestamptz, r_locale text, r_display_name text, r_email text, r_chat_id bigint)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_user uuid;
  v_type text;
  v_chat bigint;
BEGIN
  SELECT n.user_id, n.event_type INTO v_user, v_type
    FROM notifications n
    JOIN notification_deliveries d ON d.notification_id = n.id AND d.channel = p_channel AND d.status = 'PENDING'
   WHERE n.id = p_notification;
  IF NOT FOUND THEN
    RETURN;
  END IF;
  IF NOT COALESCE((SELECT p.enabled FROM notification_prefs p
                    WHERE p.user_id = v_user AND p.event_type = v_type AND p.channel = p_channel), true) THEN
    UPDATE notification_deliveries SET status = 'SKIPPED', last_error = 'switched-off', updated_at = now()
     WHERE notification_id = p_notification AND channel = p_channel;
    RETURN;
  END IF;
  IF p_channel = 'TELEGRAM' THEN
    SELECT l.chat_id INTO v_chat FROM telegram_links l WHERE l.user_id = v_user;
    IF v_chat IS NULL THEN
      UPDATE notification_deliveries SET status = 'SKIPPED', last_error = 'not-linked', updated_at = now()
       WHERE notification_id = p_notification AND channel = p_channel;
      RETURN;
    END IF;
  END IF;
  RETURN QUERY
    SELECT n.event_type, n.data, n.created_at, u.locale, u.display_name,
           CASE WHEN p_channel = 'EMAIL' THEN u.email END,
           v_chat
      FROM notifications n JOIN users u ON u.id = n.user_id
     WHERE n.id = p_notification;
END $$;

-- Records the outcome of a delivery; only a PENDING delivery moves. Returns whether it moved.
CREATE FUNCTION notification_delivery_finish(p_notification uuid, p_channel text, p_status text, p_error text)
  RETURNS boolean
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_rows integer;
BEGIN
  IF p_status NOT IN ('SENT', 'FAILED', 'SKIPPED') THEN
    RAISE EXCEPTION 'invalid delivery status' USING ERRCODE = '22023';
  END IF;
  UPDATE notification_deliveries
     SET status = p_status, last_error = left(p_error, 200), updated_at = now()
   WHERE notification_id = p_notification AND channel = p_channel AND status = 'PENDING';
  GET DIAGNOSTICS v_rows = ROW_COUNT;
  RETURN v_rows = 1;
END $$;

-- The REVIEWERs who must hear about an open review: holders of REVIEWER for the review's exam in the review's own context
-- (the organisation's, or English9x's for owner NULL; organisation holders must still be members), minus the people who may not
-- decide it (its author and its submitter). Nothing for a review that is not open.
CREATE FUNCTION notification_reviewers_of_review(p_review uuid)
  RETURNS TABLE (r_user_id uuid)
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
  SELECT DISTINCT g.user_id
    FROM review_requests r
    JOIN role_grants g ON g.role = 'REVIEWER' AND g.exam_code = r.exam_code AND g.org_id IS NOT DISTINCT FROM r.owner_org_id
   WHERE r.id = p_review AND r.status = 'IN_REVIEW'
     AND g.user_id NOT IN (r.author_id, r.submitted_by)
     AND (r.owner_org_id IS NULL
          OR EXISTS (SELECT 1 FROM org_memberships m WHERE m.org_id = r.owner_org_id AND m.user_id = g.user_id)) $$;

-- Telegram link tokens. Issue: for the caller (the current user of the session); older unused tokens of that person are retired.
CREATE FUNCTION telegram_link_issue(p_token_hash bytea, p_now timestamptz, p_expires_at timestamptz)
  RETURNS void
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_user uuid := e9x_current_user();
BEGIN
  IF v_user IS NULL OR NOT EXISTS (SELECT 1 FROM users u WHERE u.id = v_user) THEN
    RAISE EXCEPTION 'no signed-in user' USING ERRCODE = '42501';
  END IF;
  IF p_expires_at <= p_now THEN
    RAISE EXCEPTION 'token already expired' USING ERRCODE = '22023';
  END IF;
  DELETE FROM telegram_link_tokens WHERE user_id = v_user AND (used_at IS NULL OR expires_at < p_now);
  INSERT INTO telegram_link_tokens (user_id, token_hash, created_at, expires_at) VALUES (v_user, p_token_hash, p_now, p_expires_at);
END $$;

-- Consume (the unauthenticated webhook): LINKED | CHAT_TAKEN | INVALID. INVALID covers unknown, used and expired tokens alike, so the
-- answer never tells which tokens exist. The locale is the person's, for the confirmation text.
CREATE FUNCTION telegram_link_consume(p_token_hash bytea, p_chat_id bigint, p_now timestamptz)
  RETURNS TABLE (r_status text, r_locale text)
  LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
  v_token telegram_link_tokens%ROWTYPE;
  v_owner uuid;
  v_locale text;
BEGIN
  SELECT * INTO v_token FROM telegram_link_tokens t WHERE t.token_hash = p_token_hash FOR UPDATE;
  IF NOT FOUND OR v_token.used_at IS NOT NULL OR v_token.expires_at <= p_now THEN
    r_status := 'INVALID'; r_locale := NULL;
    RETURN NEXT;
    RETURN;
  END IF;
  SELECT l.user_id INTO v_owner FROM telegram_links l WHERE l.chat_id = p_chat_id;
  IF v_owner IS NOT NULL AND v_owner <> v_token.user_id THEN
    r_status := 'CHAT_TAKEN'; r_locale := NULL;
    RETURN NEXT;
    RETURN;
  END IF;
  BEGIN
    INSERT INTO telegram_links (user_id, chat_id, linked_at) VALUES (v_token.user_id, p_chat_id, p_now)
    ON CONFLICT (user_id) DO UPDATE SET chat_id = EXCLUDED.chat_id, linked_at = EXCLUDED.linked_at;
  EXCEPTION WHEN unique_violation THEN
    -- another account took this chat between the check and the insert
    r_status := 'CHAT_TAKEN'; r_locale := NULL;
    RETURN NEXT;
    RETURN;
  END;
  UPDATE telegram_link_tokens SET used_at = p_now WHERE id = v_token.id;
  SELECT u.locale INTO v_locale FROM users u WHERE u.id = v_token.user_id;
  r_status := 'LINKED'; r_locale := v_locale;
  RETURN NEXT;
END $$;

REVOKE ALL ON FUNCTION notification_create(uuid, text, jsonb, text, timestamptz),
                       notification_delivery_load(uuid, text),
                       notification_delivery_finish(uuid, text, text, text),
                       notification_reviewers_of_review(uuid),
                       telegram_link_issue(bytea, timestamptz, timestamptz),
                       telegram_link_consume(bytea, bigint, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION notification_create(uuid, text, jsonb, text, timestamptz),
                          notification_reviewers_of_review(uuid),
                          telegram_link_issue(bytea, timestamptz, timestamptz),
                          telegram_link_consume(bytea, bigint, timestamptz) TO e9x_app;
GRANT EXECUTE ON FUNCTION notification_delivery_load(uuid, text),
                          notification_delivery_finish(uuid, text, text, text) TO e9x_worker;
