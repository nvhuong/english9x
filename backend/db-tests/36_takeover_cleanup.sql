-- Final review I1 (and plan fix 11): what an attacker chose while holding an unverified account dies when the real owner arrives, and an
-- unverified account cannot choose any of it in the first place. Runs through the real functions as the real role.
INSERT INTO users(id,email,password_hash,consent_at,locale,email_verified_at) VALUES
 ('e0000000-0000-0000-0000-000000000001','victim@x.test','attacker-hash',now(),'vi',NULL),
 ('e0000000-0000-0000-0000-000000000002','owner@x.test','owner-hash',now(),'vi',now()),
 ('e0000000-0000-0000-0000-000000000003','resetter@x.test','old-hash',now(),'vi',now()),
 ('e0000000-0000-0000-0000-000000000004','joiner@x.test','hash',now(),'vi',NULL),
 ('e0000000-0000-0000-0000-000000000005','verified@x.test','hash',now(),'vi',now());
INSERT INTO organizations(id,name,type) VALUES ('aaaaaaaa-0000-0000-0000-00000000000a','Center A','CENTER');
INSERT INTO join_codes(org_id,code_hash,created_by,expires_at)
SELECT 'aaaaaaaa-0000-0000-0000-00000000000a', sha256('join-1'::bytea), 'e0000000-0000-0000-0000-000000000005', now() + interval '1 day';

-- state as it stands on a database from before this migration, or written as owner: an attacker's chat, an unused token, EMAIL off
INSERT INTO telegram_links(user_id,chat_id,linked_at) VALUES
 ('e0000000-0000-0000-0000-000000000001',666001,now()),
 ('e0000000-0000-0000-0000-000000000002',666003,now()),
 ('e0000000-0000-0000-0000-000000000003',666004,now());
INSERT INTO telegram_link_tokens(user_id,token_hash,created_at,expires_at) VALUES
 ('e0000000-0000-0000-0000-000000000001',sha256('tg-attacker'::bytea),now(),now()+interval '1 hour'),
 ('e0000000-0000-0000-0000-000000000002',sha256('tg-owner'::bytea),now(),now()+interval '1 hour'),
 ('e0000000-0000-0000-0000-000000000003',sha256('tg-reset'::bytea),now(),now()+interval '1 hour');
INSERT INTO notification_prefs(user_id,event_type,channel,enabled,updated_at) VALUES
 ('e0000000-0000-0000-0000-000000000001','SCORE_OFFICIAL','EMAIL',false,now()),
 ('e0000000-0000-0000-0000-000000000002','SCORE_OFFICIAL','EMAIL',false,now()),
 ('e0000000-0000-0000-0000-000000000003','SCORE_OFFICIAL','EMAIL',false,now());
INSERT INTO password_resets(user_id,token_hash,created_at,expires_at) VALUES
 ('e0000000-0000-0000-0000-000000000003',sha256('reset-1'::bytea),now(),now()+interval '1 hour');

SET ROLE e9x_app;
SELECT set_config('app.user_id','e0000000-0000-0000-0000-000000000004',false), set_config('app.org_id','',false),
       set_config('app.e9x_staff','',false), set_config('app.org_admin','',false);

-- an unverified account gets no link token, chooses no preference, joins nothing
DO $$
DECLARE refused boolean := false; v_outcome text;
BEGIN
  BEGIN
    PERFORM telegram_link_issue(sha256('tg-joiner'::bytea), now(), now() + interval '1 hour');
  EXCEPTION WHEN SQLSTATE 'E9X02' THEN refused := true; END;
  ASSERT refused, 'telegram_link_issue refuses an unverified account';
  refused := false;
  BEGIN
    INSERT INTO notification_prefs(user_id,event_type,channel,enabled,updated_at)
    VALUES ('e0000000-0000-0000-0000-000000000004','SCORE_OFFICIAL','EMAIL',false,now());
  EXCEPTION WHEN insufficient_privilege THEN refused := true; END;
  ASSERT refused, 'an unverified account cannot write a preference';
  SELECT r_outcome INTO v_outcome FROM org_join_code_redeem(sha256('join-1'::bytea), now());
  ASSERT v_outcome = 'EMAIL_NOT_VERIFIED', 'org_join_code_redeem refuses an unverified account, got ' || coalesce(v_outcome, 'null');
END $$;

-- the same three calls succeed for a verified account: the refusals above are about verification, nothing else
SELECT set_config('app.user_id','e0000000-0000-0000-0000-000000000005',false);
DO $$
DECLARE v_outcome text;
BEGIN
  PERFORM telegram_link_issue(sha256('tg-verified'::bytea), now(), now() + interval '1 hour');
  INSERT INTO notification_prefs(user_id,event_type,channel,enabled,updated_at)
  VALUES ('e0000000-0000-0000-0000-000000000005','SCORE_OFFICIAL','EMAIL',false,now());
  SELECT r_outcome INTO v_outcome FROM org_join_code_redeem(sha256('join-1'::bytea), now());
  ASSERT v_outcome = 'JOINED', 'a verified account joins, got ' || coalesce(v_outcome, 'null');
END $$;

-- LINKED_NEUTRALISED: everything the attacker chose goes, and the unused token no longer links anything
SELECT set_config('app.user_id','',false);
DO $$
DECLARE v_outcome text; n int; v_status text;
BEGIN
  SELECT r_outcome INTO v_outcome FROM auth_oauth_login('google','sub-victim','victim@x.test',true,'Real Owner','vi',now());
  ASSERT v_outcome = 'LINKED_NEUTRALISED', 'outcome ' || v_outcome;
  SELECT r_status INTO v_status FROM telegram_link_consume(sha256('tg-attacker'::bytea), 666002, now());
  ASSERT v_status = 'INVALID', 'the attacker''s unused token is dead after neutralisation, got ' || v_status;
END $$;
RESET ROLE;
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM telegram_links WHERE user_id = 'e0000000-0000-0000-0000-000000000001';
  ASSERT n = 0, 'the attacker''s chat is gone';
  SELECT count(*) INTO n FROM telegram_link_tokens WHERE user_id = 'e0000000-0000-0000-0000-000000000001';
  ASSERT n = 0, 'the attacker''s link tokens are gone';
  SELECT count(*) INTO n FROM notification_prefs WHERE user_id = 'e0000000-0000-0000-0000-000000000001';
  ASSERT n = 0, 'the attacker''s preferences are gone';
END $$;

-- ...and email is on again by default: the owner, once consented, is told by mail and only by mail
SET ROLE e9x_app;
DO $$
DECLARE v_channels text[];
BEGIN
  PERFORM auth_consent_record('e0000000-0000-0000-0000-000000000001', now());
  SELECT array_agg(c.r_channel::text) INTO v_channels
    FROM notification_create('e0000000-0000-0000-0000-000000000001','SCORE_OFFICIAL','{}','after-takeover',now()) c;
  ASSERT v_channels = ARRAY['EMAIL'], 'after neutralisation email is enabled and Telegram is not planned: ' || coalesce(v_channels::text, 'null');
END $$;

-- LINKED (a verified account): link and tokens go, the owner's own preferences stay
DO $$
DECLARE v_outcome text; v_status text;
BEGIN
  SELECT r_outcome INTO v_outcome FROM auth_oauth_login('google','sub-owner','owner@x.test',true,'Owner','vi',now());
  ASSERT v_outcome = 'LINKED', 'outcome ' || v_outcome;
  SELECT r_status INTO v_status FROM telegram_link_consume(sha256('tg-owner'::bytea), 666005, now());
  ASSERT v_status = 'INVALID', 'link token dead after LINKED, got ' || v_status;
END $$;
RESET ROLE;
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM telegram_links WHERE user_id = 'e0000000-0000-0000-0000-000000000002';
  ASSERT n = 0, 'LINKED removes the chat';
  SELECT count(*) INTO n FROM telegram_link_tokens WHERE user_id = 'e0000000-0000-0000-0000-000000000002';
  ASSERT n = 0, 'LINKED removes the link tokens';
  SELECT count(*) INTO n FROM notification_prefs WHERE user_id = 'e0000000-0000-0000-0000-000000000002' AND enabled = false;
  ASSERT n = 1, 'LINKED keeps the preferences';
END $$;

-- password reset: link and tokens go, preferences stay
SET ROLE e9x_app;
DO $$
DECLARE v_status text; n int;
BEGIN
  SELECT count(*) INTO n FROM auth_password_reset_confirm(sha256('reset-1'::bytea), 'new-hash', now());
  ASSERT n = 1, 'the reset went through';
  SELECT r_status INTO v_status FROM telegram_link_consume(sha256('tg-reset'::bytea), 666006, now());
  ASSERT v_status = 'INVALID', 'link token dead after a reset, got ' || v_status;
END $$;
RESET ROLE;
DO $$
DECLARE n int;
BEGIN
  SELECT count(*) INTO n FROM telegram_links WHERE user_id = 'e0000000-0000-0000-0000-000000000003';
  ASSERT n = 0, 'a reset removes the chat';
  SELECT count(*) INTO n FROM telegram_link_tokens WHERE user_id = 'e0000000-0000-0000-0000-000000000003';
  ASSERT n = 0, 'a reset removes the link tokens';
  SELECT count(*) INTO n FROM notification_prefs WHERE user_id = 'e0000000-0000-0000-0000-000000000003' AND enabled = false;
  ASSERT n = 1, 'a reset keeps the preferences';
END $$;

-- the access-token check: one number for one account, the rotated refresh carries it, and a credentials change moves it
SET ROLE e9x_app;
DO $$
DECLARE v bigint; v_after bigint;
BEGIN
  SELECT auth_credentials_version('e0000000-0000-0000-0000-000000000005') INTO v;
  ASSERT v IS NOT NULL, 'an existing account has a version, with no session context at all';
  ASSERT auth_credentials_version('e0000000-0000-0000-0000-00000000dead') IS NULL, 'no version for an account that does not exist';
  SELECT auth_credentials_version('e0000000-0000-0000-0000-000000000003') INTO v_after;
  ASSERT v_after = 2, 'the reset above moved the version, got ' || v_after;
END $$;
RESET ROLE;
